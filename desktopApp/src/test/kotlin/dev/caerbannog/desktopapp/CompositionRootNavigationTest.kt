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
import dev.caerbannog.ui.appframe.AppDestination
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
 * M2-05c step 2: the real navigation callback. "Manage connections" and "New connection" in the chip popup call
 * `ConnectionControlEvents.openConnections`; in the composition root that must switch the window to the Connections screen, and
 * the drawer items of the frame (`AppDestination`) switch the screen through [AppNavigation.open]. Same safety and style as
 * [CompositionRootControlWiringTest] (temp folder, in-memory secrets, fake gateway, queue dispatcher with `runAll()`, never
 * `~/.caerbannog`, never the keychain, no broker). Contract: `AppComposition.navigation: AppNavigation` with `screen: StateFlow<AppDestination>`
 * and `open(destination)`; `createAppComposition(navigation = ...)` replaces the old `onOpenConnections` parameter.
 *
 * Pinned (the brief is silent, my proposal): the window STARTS on Connections (what it shows today, and a first run has no profile
 * to list streams of); Connect from the chip does not move the user.
 */
class CompositionRootNavigationTest {

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
    fun `the window starts on the Connections screen`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            assertEquals(AppDestination.Connections, composition.navigation.screen.value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `opening a drawer destination shows that screen`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.navigation.open(AppDestination.Streams)
            assertEquals(AppDestination.Streams, composition.navigation.screen.value)

            composition.navigation.open(AppDestination.Connections)
            assertEquals(AppDestination.Connections, composition.navigation.screen.value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Topology and Migrations have no screen yet, opening them changes nothing`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.navigation.open(AppDestination.Streams)
            composition.navigation.open(AppDestination.Topology)
            assertEquals(AppDestination.Streams, composition.navigation.screen.value)
            composition.navigation.open(AppDestination.Migrations)
            assertEquals(AppDestination.Streams, composition.navigation.screen.value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Manage connections in the chip popup brings the Connections screen back from Streams, once the dispatcher ran`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.navigation.open(AppDestination.Streams)

            composition.control.openConnections()
            assertEquals(AppDestination.Streams, composition.navigation.screen.value, "the event hops onto the scope, nothing happens before it runs")
            queue.runAll()

            assertEquals(AppDestination.Connections, composition.navigation.screen.value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Connect from the chip does not move the user to another screen`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.navigation.open(AppDestination.Streams)
            composition.control.connect("uat-eu")
            queue.runAll()

            assertEquals(listOf("uat-eu"), gateway.openCalls)
            assertEquals(AppDestination.Streams, composition.navigation.screen.value)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `after the window closed a late Manage connections navigates nowhere`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        composition.navigation.open(AppDestination.Streams)
        queue.runAll()

        composition.close()
        composition.control.openConnections()
        queue.runAll()

        assertEquals(AppDestination.Streams, composition.navigation.screen.value)
    }
}
