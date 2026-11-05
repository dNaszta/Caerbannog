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
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamRowKind
import dev.caerbannog.core.streams.StreamSort
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
 * M2-05c step 2: the real mapping from the stream list screen's events (`StreamListEvents`, ui) to the view model, in the composition
 * root (`AppComposition.streamEvents`). The screen reports, it knows no view model; a wrong mapping (Next as Previous, a filter
 * that is dropped, a screen that never says "shown") would pass every Compose test. Same safety as the other wiring tests (temp
 * folder, in-memory secrets, fake gateway and catalog of core-testing, queue dispatcher with `runAll()`; never `~/.caerbannog`,
 * never the keychain, no broker).
 */
class CompositionRootStreamEventsTest {

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
    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")

    private fun compose(queue: QueueDispatcher, streams: Int) = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue,
    ).also {
        it.viewModel.addConnection(input)
        repeat(streams) { n -> gateway.catalogOf("uat-eu").addStream(stream("s%03d".format(n))) }
    }

    private fun AppComposition.connect(queue: QueueDispatcher) {
        control.connect("uat-eu")
        queue.runAll()
        assertEquals(LinkState.Connected("uat-eu"), active.state.value, "precondition: connected")
    }

    private fun listCalls() = synchronized(gateway.catalogOf("uat-eu").calls) { gateway.catalogOf("uat-eu").calls.count { it == "listStreams" } }

    @Test
    fun `the broker is not asked until the screen says it is shown, then the list loads`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 3)
        try {
            composition.connect(queue)
            assertEquals(0, listCalls(), "the screen is not shown: no broker call (D3)")

            composition.streamEvents.setShown(true)
            queue.runAll()

            assertEquals(listOf("s000", "s001", "s002"), composition.streamList.state.value.rows.map { it.name })
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Next and Previous move the page, the right way round`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 120)
        try {
            composition.connect(queue)
            composition.streamEvents.setShown(true)
            queue.runAll()
            assertEquals(1, composition.streamList.state.value.page)

            composition.streamEvents.nextPage()
            queue.runAll()
            assertEquals(2, composition.streamList.state.value.page)
            assertEquals("s050", composition.streamList.state.value.rows.first().name)

            composition.streamEvents.previousPage()
            queue.runAll()
            assertEquals(1, composition.streamList.state.value.page)
        } finally {
            composition.close()
        }
    }

    // M2-07: a wrong mapping (toggle dropped, the kind filter mapped to nothing) would pass every Compose test.
    @Test
    fun `the expand toggle and the kind chips reach the view model (M2-07)`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 1)
        try {
            val catalog = gateway.catalogOf("uat-eu")
            for (p in listOf("orders-0", "orders-1")) {
                catalog.addStream(stream(p))
                gateway.offsetsOf("uat-eu").setFirst("/", p, 0)
            }
            catalog.superStream("orders", listOf("orders-0", "orders-1"))
            composition.connect(queue)
            composition.streamEvents.setShown(true)
            queue.runAll()
            assertEquals(listOf("orders", "s000"), composition.streamList.state.value.rows.map { it.name })

            composition.streamEvents.toggleExpanded("orders")
            queue.runAll()
            assertEquals(listOf("orders", "orders-0", "orders-1", "s000"), composition.streamList.state.value.rows.map { it.name })

            composition.streamEvents.setKindFilter(StreamKindFilter.Streams)
            queue.runAll()
            assertEquals(StreamKindFilter.Streams, composition.streamList.state.value.kindFilter)
            assertEquals(listOf("s000"), composition.streamList.state.value.rows.map { it.name })

            composition.streamEvents.setKindFilter(StreamKindFilter.SuperStreams)
            queue.runAll()
            assertEquals(StreamRowKind.SuperStream, composition.streamList.state.value.rows.first().kind)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `the filter and the sort reach the view model`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 12)
        try {
            composition.connect(queue)
            composition.streamEvents.setShown(true)
            queue.runAll()

            composition.streamEvents.setFilter("s00")
            queue.runAll()
            assertEquals("s00", composition.streamList.state.value.filter)
            assertEquals(10, composition.streamList.state.value.totalStreams)

            composition.streamEvents.setSort(StreamSort.NameDescending)
            queue.runAll()
            assertEquals("s009", composition.streamList.state.value.rows.first().name)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Refresh now asks the broker again, and the screen leaving stops the asking`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 2)
        try {
            composition.connect(queue)
            composition.streamEvents.setShown(true)
            queue.runAll()
            val afterFirstLoad = listCalls()
            assertTrue(afterFirstLoad >= 1, "the first load asked the broker")

            composition.streamEvents.refreshNow()
            queue.runAll()
            assertTrue(listCalls() > afterFirstLoad, "Refresh now made another list call")

            composition.streamEvents.setShown(false)
            queue.runAll()
            val whileHidden = listCalls()
            composition.streamEvents.refreshNow()
            queue.runAll()
            assertEquals(whileHidden, listCalls(), "nothing is asked while the screen is not shown")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `Create stream does nothing in M2-05, no broker call and no change of the list`() {
        val queue = QueueDispatcher()
        val composition = compose(queue, streams = 2)
        try {
            composition.connect(queue)
            composition.streamEvents.setShown(true)
            queue.runAll()
            val before = composition.streamList.state.value
            val calls = gateway.catalogOf("uat-eu").calls.toList()

            composition.streamEvents.createStream()
            queue.runAll()

            assertEquals(before, composition.streamList.state.value)
            assertEquals(calls, gateway.catalogOf("uat-eu").calls.toList(), "the dialog is M3: no call to the broker")
            assertEquals(StreamListScreen.Rows, composition.streamList.state.value.screen)
        } finally {
            composition.close()
        }
    }
}
