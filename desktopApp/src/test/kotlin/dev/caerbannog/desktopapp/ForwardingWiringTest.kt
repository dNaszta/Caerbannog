package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.forwarding.ShovelGateway
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.forwarding.fakes.ShovelCall
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-09 wiring: `AppComposition.createForwardingViewModel` builds the view model from the HELD 4.x connection: its shovel gateway, the profile's
 * virtual host as the destination, the level of the profile read at every call; a 3.x connection (or none) gives no destination and sends nothing.
 */
class ForwardingWiringTest {
    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private object Permissive : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() { while (true) (queue.poll() ?: return).run() }
    }

    private val fake = FakeShovelGateway().apply {
        sourceQueue("legacy-eu", "/", "orders", 120)
        destinationQueue("prod", "orders", DestinationQueueType.QUORUM)
        userTags("policymaker", "monitoring")
    }
    private val versions = mapOf("legacy-eu" to "3.13.7", "new-eu" to "4.1.0")
    private val gateway = object : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = FakeStreamCatalog()
            override val offsets = FakeStreamOffsets()
            override val topology = FakeTopologyCatalog()
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = versions[profile.name]
            override val shovels: ShovelGateway = fake
            override fun close() = Unit
        })
    }
    private val queue = QueueDispatcher()

    private fun compose(): AppComposition {
        val document = (DefinitionsParser.parse(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders")))) as DefinitionsParseResult.Parsed).document
        val holder = AnalysisHolder().also { it.hold(HeldAnalysis("legacy-eu", document, CompatibilityAnalyzer.analyze(document), SourceKind.Live)) }
        return createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue, migrationHistoryFile = File(dir, "history.json").toPath(),
            migrateFromThree = MigrateFromThreeParts(FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), holder),
        ).also { c ->
            c.viewModel.addConnection(ConnectionProfileInput(name = "legacy-eu", host = "localhost", virtualHost = "/", username = "svc", password = "pw"))
            c.viewModel.addConnection(
                ConnectionProfileInput(name = "new-eu", host = "localhost", virtualHost = "prod", username = "svc", password = "pw", topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT),
            )
        }
    }

    private fun connect(c: AppComposition, name: String) { c.control.connect(name); queue.runAll() }

    @Test
    fun `with the 4x connection held the view model plans through its shovel gateway, in the profile's virtual host`() {
        val c = compose()
        try {
            connect(c, "new-eu")
            val vm = c.createForwardingViewModel(CoroutineScope(queue))
            vm.open(); queue.runAll()
            val pick = assertIs<ForwardingScreenState.Pick>(vm.state.value, "state is ${vm.state.value}")
            assertNull(pick.forwardDisabledReason)
            vm.pick(QueueRow("/", "orders")); queue.runAll()
            assertIs<ForwardingScreenState.Confirm>(vm.state.value, "state is ${vm.state.value}")
            assertEquals(listOf<ShovelCall>(ShovelCall.DryRun), fake.calls)
            vm.setTyped("orders"); vm.confirm(); queue.runAll()
            val request = fake.calls.filterIsInstance<ShovelCall.Start>().single().request
            assertEquals(listOf("new-eu", "prod", "orders"), listOf(request.destinationConnection, request.destinationVhost, request.destinationQueue))
            vm.close(); queue.runAll()
        } finally { c.close() }
    }

    @Test
    fun `the level of the held profile is read at every call`() {
        val c = compose()
        try {
            connect(c, "new-eu")
            val vm = c.createForwardingViewModel(CoroutineScope(queue))
            vm.open(); queue.runAll()
            assertNull(assertIs<ForwardingScreenState.Pick>(vm.state.value).forwardDisabledReason)
            c.viewModel.updateConnection(
                "new-eu", ConnectionProfileInput(name = "new-eu", host = "localhost", virtualHost = "prod", username = "svc", password = null, topologyControlLevel = TopologyControlLevel.READ_ONLY),
            )
            queue.runAll()
            connect(c, "new-eu") // editing a profile ends its held connection; the SAME view model reads the new level
            vm.setQuery(""); queue.runAll()
            val reason = (vm.state.value as? ForwardingScreenState.Pick)?.forwardDisabledReason
            assertEquals(ForwardingTexts.block(ForwardingBlock.TopologyLocked, "new-eu", ""), reason, "state is ${vm.state.value}")
            vm.close()
        } finally { c.close() }
    }

    @Test
    fun `with only a 3x connection held there is no destination and no shovel request`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            val vm = c.createForwardingViewModel(CoroutineScope(queue))
            vm.open(); queue.runAll()
            assertEquals(ForwardingTexts.CONNECT_FIRST, assertIs<ForwardingScreenState.Pick>(vm.state.value, "state is ${vm.state.value}").forwardDisabledReason)
            vm.pick(QueueRow("/", "orders")); queue.runAll()
            assertTrue(fake.calls.isEmpty(), "a 3.x connection sent a shovel request: ${fake.calls}")
        } finally { c.close() }
    }
}
