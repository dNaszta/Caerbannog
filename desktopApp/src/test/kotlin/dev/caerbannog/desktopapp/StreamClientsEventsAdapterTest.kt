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
 * M2-06b step 2 (readiness item 3): the real mapping from the stream screen's events (`StreamClientsEvents`, ui) to the
 * `StreamClientsViewModel` and `AppNavigation`, via `AppComposition.streamClientsEvents` (the adapter; desktopApp has no Compose
 * dependency, so the screen itself is covered in `ui`). show/hide/refreshNow/retry reach the view model, back closes the stream
 * screen (`navigation.closeStream`). Same safety as the other wiring tests (temp folder, in-memory secrets, fake gateway, queue
 * dispatcher with `runAll()`; no keychain, no broker). The Main wiring (`StreamScreen` when `selectedStream` is set, the list's
 * `onOpenStream = navigation::openStream`) needs a window and is not tested here; `openStream` itself is pinned in `AppNavigationStreamTest`.
 */
class StreamClientsEventsAdapterTest {

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

    private fun compose(queue: QueueDispatcher) = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue,
        navigation = AppNavigation(AppDestination.Streams),
    ).also {
        it.viewModel.addConnection(input)
        catalog.addStream(stream("orders-events", messages = 10))
        gateway.offsetsOf("uat-eu").setFirst("/", "orders-events", 0)
        catalog.setConsumers("/", "orders-events", listOf(consumer("billing", 9, 9)))
    }

    private fun AppComposition.connect(queue: QueueDispatcher) {
        control.connect("uat-eu")
        queue.runAll()
        assertEquals(LinkState.Connected("uat-eu"), active.state.value, "precondition: connected")
    }

    private fun consumerCalls() = synchronized(catalog.calls) { catalog.calls.count { it == "consumers" } }

    @Test
    fun `show through the adapter shows the tab for that stream and loads its rows`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.navigation.openStream("orders-events")

            c.streamClientsEvents.show("orders-events")
            queue.runAll()

            assertEquals("orders-events", c.streamClients.state.value.stream)
            assertEquals(listOf("billing"), c.streamClients.state.value.consumers.map { it.name })
        } finally {
            c.close()
        }
    }

    @Test
    fun `hide through the adapter stops the calls and clears the rows`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.streamClientsEvents.show("orders-events")
            queue.runAll()
            assertEquals(1, c.streamClients.state.value.consumers.size, "precondition: the tab loaded its rows")
            val before = consumerCalls()

            c.streamClientsEvents.hide()
            queue.runAll()
            c.streamClientsEvents.refreshNow()
            queue.runAll()

            assertEquals(emptyList(), c.streamClients.state.value.consumers, "data cleared on hide")
            assertEquals(before, consumerCalls(), "no broker call while the tab is not shown, also not for a refresh")
        } finally {
            c.close()
        }
    }

    @Test
    fun `refreshNow through the adapter reloads the shown tab`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.streamClientsEvents.show("orders-events")
            queue.runAll()
            val before = consumerCalls()
            catalog.setConsumers("/", "orders-events", listOf(consumer("billing", 9, 9), consumer("audit", 5, 4)))

            c.streamClientsEvents.refreshNow()
            queue.runAll()

            assertEquals(before + 1, consumerCalls(), "one more load")
            assertEquals(listOf("billing", "audit"), c.streamClients.state.value.consumers.map { it.name })
        } finally {
            c.close()
        }
    }

    @Test
    fun `retry through the adapter loads again after a failed first load`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            catalog.failCall("consumers")
            c.streamClientsEvents.show("orders-events")
            queue.runAll()
            assertTrue(c.streamClients.state.value.screen is StreamClientsScreen.Failed, "precondition: ${c.streamClients.state.value}")

            c.streamClientsEvents.retry()
            queue.runAll()

            assertTrue(c.streamClients.state.value.screen is StreamClientsScreen.Content, "state: ${c.streamClients.state.value}")
        } finally {
            c.close()
        }
    }

    @Test
    fun `back through the adapter closes the stream screen, the list comes back, Streams stays the screen`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.connect(queue)
            c.navigation.openStream("orders-events")
            assertEquals("orders-events", c.navigation.selectedStream.value)

            c.streamClientsEvents.back()

            assertNull(c.navigation.selectedStream.value)
            assertEquals(AppDestination.Streams, c.navigation.screen.value, "the drawer keeps Streams")
        } finally {
            c.close()
        }
    }

    @Test
    fun `back with no stream open does nothing`() {
        val queue = QueueDispatcher()
        val c = compose(queue)
        try {
            c.streamClientsEvents.back()
            assertNull(c.navigation.selectedStream.value)
            assertEquals(AppDestination.Streams, c.navigation.screen.value)
        } finally {
            c.close()
        }
    }
}
