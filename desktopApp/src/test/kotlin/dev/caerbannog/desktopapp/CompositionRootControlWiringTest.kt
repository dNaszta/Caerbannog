package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import java.util.Collections
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-05b step 2: the app bar model in the composition root. Same safety and style as [CompositionRootStreamListWiringTest]
 * (temp folder, in-memory secrets, a fake gateway from core-testing; never `~/.caerbannog`, never the keychain, no broker), and the
 * same QUEUE dispatcher with `runAll()`: the control model works on the dispatcher it is given, so nothing happens until `runAll()`.
 * Contract: `AppComposition.control: ConnectionControlModel`, built from the composition's own view models and `uiDispatcher` scope.
 */
class CompositionRootControlWiringTest {

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

    private object Silent : StreamCatalog {
        override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = CatalogResult.Unexpected
        override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = CatalogResult.Unexpected
        override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = CatalogResult.Unexpected
        override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = CatalogResult.Unexpected
        override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = CatalogResult.Unexpected
        override suspend fun superStreams(vhost: String): CatalogResult<List<dev.caerbannog.core.streams.SuperStreamLayout>> = CatalogResult.Unexpected
        override suspend fun capabilities(): CatalogResult<Capabilities> = CatalogResult.Unexpected
    }

    /** Counts the opens by profile name; every open succeeds. The stream list is never shown here, so the catalog is never asked. */
    private class CountingGateway : ConnectionGateway {
        val openCalls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override suspend fun open(profile: ConnectionProfile): OpenResult {
            openCalls += profile.name
            return OpenResult.Opened(object : HeldConnection {
                override val catalog = Silent
                override val offsets = object : StreamOffsets {
                    override suspend fun firstOffset(vhost: String, stream: String) = FirstOffset.Unavailable
                }
                override val topology = FakeTopologyCatalog()
                override val messages = FakeStreamMessageReader()
                override fun close() = Unit
            })
        }
    }

    private val gateway = CountingGateway()
    private val settingsFile get() = File(dir, "settings.json")
    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")

    private fun compose(queue: QueueDispatcher) = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(settingsFile), uiDispatcher = queue,
    ).also { it.viewModel.addConnection(input) }

    @Test
    fun `the chip model connects on the dispatcher it is given, and shows the connection once it ran`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            queue.runAll()
            assertEquals(ChipState.NoConnection, composition.control.state.value.chip.kind)

            composition.control.connect("uat-eu")
            assertEquals(emptyList(), gateway.openCalls, "nothing runs before the dispatcher does (the event hops onto the scope)")
            queue.runAll()

            assertEquals(listOf("uat-eu"), gateway.openCalls)
            assertEquals(LinkState.Connected("uat-eu"), composition.active.state.value)
            assertEquals("uat-eu", composition.control.state.value.chip.text)
            assertEquals(ChipState.Connected, composition.control.state.value.chip.kind)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `choosing an interval in the chip writes the settings file`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.control.setRefreshInterval(RefreshInterval.Every30)
            queue.runAll()

            assertTrue(settingsFile.exists(), "no settings file was written at $settingsFile")
            assertTrue(Regex("\"streamListRefreshSeconds\"\\s*:\\s*30\\b").containsMatchIn(settingsFile.readText()), "settings file: <${settingsFile.readText()}>")
            assertEquals(RefreshInterval.Every30, composition.control.state.value.refresh.selected)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `the chip shows the interval of the last run`() {
        settingsFile.writeText("""{"streamListRefreshSeconds": 60}""")
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            queue.runAll()

            assertEquals(RefreshInterval.Every60, composition.control.state.value.refresh.selected)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `closing the composition stops the chip model, a late Connect opens nothing`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        queue.runAll()

        composition.close()
        composition.control.connect("uat-eu")
        queue.runAll()

        assertEquals(emptyList(), gateway.openCalls, "the broker was asked after the window closed")
    }
}
