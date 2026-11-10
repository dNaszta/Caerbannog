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
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.HeldAnalysis
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06c: the wiring of the live source in the composition root. `AppComposition.liveSource` follows connect, disconnect and switch of the real
 * `ActiveConnection`; the view model made by `createMigrateFromThreeViewModel` reads it; the popup entry of the chip model opens the screen with the live source
 * requested. Same safety as [CompositionRootNavigationFeaturesTest]: temp folder, in-memory secrets, fake gateway, no broker.
 */
class CompositionRootLiveSourceTest {

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
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private class VersionedGateway(val versions: Map<String, String>) : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = FakeStreamCatalog()
            override val offsets = FakeStreamOffsets()
            override val topology = FakeTopologyCatalog()
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = versions[profile.name]
            override fun close() = Unit
        })
    }

    private val queue = QueueDispatcher()
    private val gateway = VersionedGateway(mapOf("legacy-eu" to "3.13.7", "new-eu" to "4.1.0"))

    private fun compose(): AppComposition = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue,
        migrationHistoryFile = File(dir, "history.json").toPath(),
    ).also { composition ->
        for (name in gateway.versions.keys) {
            composition.viewModel.addConnection(ConnectionProfileInput(name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret"))
        }
    }

    private fun connect(c: AppComposition, name: String) {
        c.control.connect(name)
        queue.runAll()
    }

    @Test
    fun `no live source before a connection, and none for a 4x connection`() {
        val c = compose()
        try {
            queue.runAll()
            assertNull(c.liveSource.current.value)
            connect(c, "new-eu")
            assertNull(c.liveSource.current.value)
        } finally { c.close() }
    }

    @Test
    fun `the live source follows connect, switch and disconnect`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            assertEquals("legacy-eu", assertNotNull(c.liveSource.current.value, "a connected 3.x broker is a live source").connectionName)
            connect(c, "new-eu")
            assertNull(c.liveSource.current.value, "the 4.x connection is the held one now")
            connect(c, "legacy-eu")
            assertNotNull(c.liveSource.current.value)
            c.control.disconnect("legacy-eu")
            queue.runAll()
            assertNull(c.liveSource.current.value)
        } finally { c.close() }
    }

    @Test
    fun `the view model of the screen reads the held provider, so its picker follows the connection`() {
        val c = compose()
        try {
            val vm = c.createMigrateFromThreeViewModel(CoroutineScope(queue))
            queue.runAll()
            assertEquals(SourceOption.File, vm.picker.value.options.first())
            assertTrue(vm.picker.value.options.last() is SourceOption.LiveDisabled, "no 3.x broker: live is disabled: ${vm.picker.value}")
            connect(c, "legacy-eu")
            assertEquals(SourceOption.Live("legacy-eu"), vm.picker.value.options.last())
            connect(c, "new-eu")
            assertTrue(vm.picker.value.options.last() is SourceOption.LiveDisabled)
        } finally { c.close() }
    }

    @Test
    fun `the popup entry opens the screen with the live source requested, and the screen selects it`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            assertTrue(c.control.state.value.rows.first { it.name == "legacy-eu" }.offersMigration, "the popup row of the connected 3.x broker offers it")
            c.control.openMigrateFromThree()
            queue.runAll()
            assertEquals(dev.caerbannog.ui.appframe.AppDestination.MigrateFromThree, c.navigation.screen.value)
            assertEquals(SourceKind.Live, c.navigation.requestedSource.value)
            // What MigrateFromThreeWindowContent does with the request.
            val vm = c.createMigrateFromThreeViewModel(CoroutineScope(queue))
            queue.runAll()
            c.navigation.requestedSource.value?.let { vm.chooseSource(it); c.navigation.sourceRequestHandled() }
            queue.runAll()
            assertEquals(SourceKind.Live, vm.picker.value.selected)
            assertNull(c.navigation.requestedSource.value)
        } finally { c.close() }
    }

    @Test
    fun `a request does not change a report that is on screen`() {
        val document = (DefinitionsParser.parse(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders")))) as DefinitionsParseResult.Parsed).document
        val holder = AnalysisHolder().also { it.hold(HeldAnalysis("legacy.json", document, CompatibilityAnalyzer.analyze(document))) }
        val c = createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue, migrationHistoryFile = File(dir, "history.json").toPath(),
            migrateFromThree = MigrateFromThreeParts(FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), holder),
        )
        try {
            val vm = c.createMigrateFromThreeViewModel(CoroutineScope(queue))
            queue.runAll()
            assertIs<MigrateFromThreeState.Report>(vm.state.value, "a window over a held analysis starts at the report")
            c.navigation.openMigrateFromThree(SourceKind.Live)
            c.navigation.requestedSource.value?.let { vm.chooseSource(it) }
            queue.runAll()
            assertIs<MigrateFromThreeState.Report>(vm.state.value, "the held report stays on screen")
            assertEquals(SourceKind.File, vm.picker.value.selected)
        } finally { c.close() }
    }
}
