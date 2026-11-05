package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.consumer
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-06a step 2 (brief item 6): the Publishers and consumers tab in the composition root. Same safety and style as the other wiring tests
 * (temp folder, in-memory secrets, fake gateway of core-testing, queue dispatcher with `runAll()`; never `~/.caerbannog`, never the
 * keychain, no broker). Contract: `AppComposition.streamClients: StreamClientsViewModel` on the same UI scope; `AppComposition.navigation`
 * with `selectedStream`/`openStream`; the connection control follows the shown screen. The UI is not here, so a test plays its part:
 * it calls `navigation.openStream(name)` and `streamClients.show(name)` (what the screen's DisposableEffect does).
 * Pinned: which screen the control follows is decided by `navigation.selectedStream` (a stream open means the tab is the target).
 * Not observable without a UI and so not tested here: that the view model is closed BEFORE the scope is cancelled (a late call after
 * close reaches no broker either way); the events adapter (`StreamClientsEvents` is a `ui` type, 06b).
 */
class CompositionRootClientsWiringTest {

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

    private val gateway = FakeConnectionGateway()
    private val catalog get() = gateway.catalogOf("uat-eu")
    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")
    private val second = ConnectionProfileInput(name = "prod-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")

    private fun compose(queue: QueueDispatcher) = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue,
        navigation = AppNavigation(AppDestination.Streams),
    ).also {
        it.viewModel.addConnection(input)
        it.viewModel.addConnection(second)
        catalog.addStream(stream("orders-events", messages = 10))
        gateway.offsetsOf("uat-eu").setFirst("/", "orders-events", 0)
        catalog.setConsumers("/", "orders-events", listOf(consumer("billing", 9, 9)))
        gateway.catalogOf("prod-eu").addStream(stream("orders-events", messages = 4))
        gateway.offsetsOf("prod-eu").setFirst("/", "orders-events", 0)
    }

    private fun AppComposition.connect(queue: QueueDispatcher, name: String = "uat-eu") {
        control.connect(name)
        queue.runAll()
        assertEquals(LinkState.Connected(name), active.state.value, "precondition: connected")
    }

    private fun AppComposition.openTab(queue: QueueDispatcher, stream: String = "orders-events") {
        navigation.openStream(stream)
        streamClients.show(stream)
        queue.runAll()
    }

    private fun calls(call: String) = synchronized(catalog.calls) { catalog.calls.count { it == call } }

    @Test
    fun `opening a stream and showing the tab loads its consumers on the composition's scope`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.openTab(queue)

            assertEquals("orders-events", c.navigation.selectedStream.value)
            assertTrue(c.streamClients.state.value.screen is StreamClientsScreen.Content, "state: ${c.streamClients.state.value}")
            assertEquals(listOf("billing"), c.streamClients.state.value.consumers.map { it.name })
            assertEquals(0, calls("listStreams"), "the list is not shown and is not asked")
        } finally {
            c.close()
        }
    }

    @Test
    fun `the tab does not call the broker until it is shown`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.navigation.openStream("orders-events")
            queue.runAll()
            assertEquals(0, calls("consumers"))
        } finally {
            c.close()
        }
    }

    @Test
    fun `Disconnect clears the selected stream and the tab data`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.openTab(queue)
            assertEquals(1, c.streamClients.state.value.consumers.size)

            c.control.disconnect("uat-eu")
            queue.runAll()

            assertEquals(LinkState.Disconnected("uat-eu"), c.active.state.value)
            assertNull(c.navigation.selectedStream.value, "Disconnect of the held connection closes the stream screen")
            assertEquals(emptyList(), c.streamClients.state.value.consumers)
            assertEquals(StreamClientsScreen.NotConnected, c.streamClients.state.value.screen)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a connection switch keeps the selected stream and the tab shows the new broker's rows`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.openTab(queue)

            c.control.connect("prod-eu")
            queue.runAll()

            assertEquals("orders-events", c.navigation.selectedStream.value)
            assertEquals(emptyList(), c.streamClients.state.value.consumers, "prod has no consumers; the rows of uat must be gone")
            assertEquals("3", c.streamClients.state.value.lastOffset)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a lost connection keeps the selected stream open with stale rows`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.openTab(queue)
            catalog.unreachable = true
            c.control.refreshNow()
            queue.runAll()

            assertEquals("orders-events", c.navigation.selectedStream.value)
            assertTrue(c.streamClients.state.value.stale)
            assertEquals(1, c.streamClients.state.value.consumers.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun `Refresh now in the control reaches the tab while a stream is open, and the list otherwise`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.streamEvents.setShown(true)
            queue.runAll()
            assertEquals(1, calls("listStreams"))

            // the list is on screen: Refresh now loads the list
            c.control.refreshNow()
            queue.runAll()
            assertEquals(2, calls("listStreams"))
            assertEquals(0, calls("consumers"))

            // a stream is open: the list hides, the tab shows, Refresh now loads the tab and not the list
            c.streamEvents.setShown(false)
            c.openTab(queue)
            val consumers = calls("consumers")
            val lists = calls("listStreams")
            c.control.refreshNow()
            queue.runAll()
            assertEquals(consumers + 1, calls("consumers"), "Refresh now must reach the shown tab")
            assertEquals(lists, calls("listStreams"), "and not the hidden list")
        } finally {
            c.close()
        }
    }

    @Test
    fun `the control's age follows the tab while a stream is open`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            // the list was never shown: its age is null. Only the tab has loaded.
            c.openTab(queue)
            c.control.tick()
            queue.runAll()

            assertNotNull(c.streamClients.state.value.updatedAtMillis)
            val note = c.control.state.value.refresh.note
            assertNotNull(note, "Updated N s ago must come from the tab's last load")
            assertTrue(note.startsWith("Updated "), note)
        } finally {
            c.close()
        }
    }

    @Test
    fun `closing the composition stops the tab, a late show or refresh reaches no broker`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        c.connect(queue)
        c.openTab(queue)
        val before = calls("consumers")

        c.close()
        c.streamClients.show("orders-events")
        c.streamClients.refreshNow()
        queue.runAll()

        assertEquals(before, calls("consumers"), "the broker was asked after the window closed")
    }
}
