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
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-08c step 2 (test developer): the Topology screen in the composition root, without a UI (a test plays the screen's part: it calls the events
 * the screen calls). Same safety as the other wiring tests: temp folder, in-memory secrets, fake gateway, `Dispatchers.Unconfined` as the UI
 * dispatcher (every call runs at once). Contract: `AppComposition.topologyEvents` (a `TopologyEventsAdapter`, the screen's `TopologyEvents` mapped
 * onto `AppComposition.topology`), `AppNavigation` opens `AppDestination.Topology`, and the connection control follows the SHOWN screen: while
 * Topology is the screen, "Refresh now" and "Updated N s ago" belong to the Topology view model (M2-06 item 6: one refresh target per shown screen).
 */
class CompositionRootTopologyScreenTest {

    @TempDir
    lateinit var dir: File

    private class MapSecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
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

    private val topology = FakeTopologyCatalog().addQueue(quorum("orders", ready = 7)).addQueue(quorum("legacy").copy(type = "classic"))
    private val streams = FakeStreamCatalog()

    private val gateway = object : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = streams
            override val offsets = object : StreamOffsets {
                override suspend fun firstOffset(vhost: String, stream: String) = FirstOffset.Unavailable
            }
            override val topology = this@CompositionRootTopologyScreenTest.topology
            override fun close() = Unit
        })
    }

    private fun compose() = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = Dispatchers.Unconfined,
    ).also {
        it.viewModel.addConnection(ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret"))
    }

    private fun AppComposition.connect() = runBlocking { viewModel.connect("uat-eu") }

    private fun calls(name: String) = synchronized(topology.calls) { topology.calls.count { it == name } }

    // --- the screen enters and leaves (D3) ---

    @Test
    fun `the screen's shown event starts the loads and its hidden event stops them`() {
        val c = compose()
        try {
            c.connect()
            assertEquals(0, calls("queues"), "connected but the screen not shown: no call")
            c.topologyEvents.setShown(true)
            assertEquals(1, calls("queues"))
            assertEquals(listOf("orders"), c.topology.state.value.queues.map { it.name })

            c.topologyEvents.setShown(false)
            val before = topology.calls.size
            c.topologyEvents.refreshNow()
            assertEquals(before, topology.calls.size, "a hidden screen does not refresh")
        } finally {
            c.close()
        }
    }

    // --- every event reaches the view model ---

    @Test
    fun `every event of the screen is mapped onto the view model`() {
        val c = compose()
        try {
            c.connect()
            val e = c.topologyEvents
            e.setShown(true)

            e.setQueueFilter(QueueTypeFilter.OtherTypes)
            assertEquals(listOf("legacy"), c.topology.state.value.queues.map { it.name })
            e.setQueueFilter(QueueTypeFilter.Quorum)
            e.setSearch("ord")
            assertEquals("ord", c.topology.state.value.search)
            e.setSearch("")
            e.selectQueue("orders")
            assertEquals("orders", c.topology.state.value.selectedQueue?.name)
            e.selectQueue(null)
            assertNull(c.topology.state.value.selectedQueue)

            e.setTab(TopologyTab.Exchanges)
            assertEquals(TopologyTab.Exchanges, c.topology.state.value.tab)
            assertTrue(calls("exchanges") >= 1, "the Exchanges tab loads the exchanges")
            e.setTab(TopologyTab.Bindings)
            e.setShowDefaultExchange(true)
            assertTrue(c.topology.state.value.showDefaultExchange)
            assertTrue(c.topology.state.value.bindings.any { it.source == "(default exchange)" })

            topology.declareVhost("billing")
            e.refreshNow()
            e.selectVhost("billing")
            assertEquals("billing", c.topology.state.value.vhost)
            e.nextPage()
            e.previousPage()
        } finally {
            c.close()
        }
    }

    @Test
    fun `retry on a lost connection reconnects through the connections view model`() {
        val c = compose()
        try {
            c.connect()
            c.topologyEvents.setShown(true)
            topology.unreachable = true
            c.topologyEvents.refreshNow()
            assertEquals(dev.caerbannog.core.connections.LinkState.Failed("uat-eu", "Connection lost"), c.active.state.value)

            topology.unreachable = false
            c.topologyEvents.retry()
            assertEquals(dev.caerbannog.core.connections.LinkState.Connected("uat-eu"), c.active.state.value)
            assertEquals(listOf("orders"), c.topology.state.value.queues.map { it.name })
        } finally {
            c.close()
        }
    }

    // --- navigation and the connection control ---

    @Test
    fun `the drawer item Topology opens the Topology screen`() {
        val c = compose()
        try {
            c.navigation.open(AppDestination.Topology)
            assertEquals(AppDestination.Topology, c.navigation.screen.value)
        } finally {
            c.close()
        }
    }

    @Test
    fun `Refresh now in the control reaches the Topology view model while Topology is the screen, and the stream list otherwise`() {
        val c = compose()
        try {
            c.connect()
            c.streamEvents.setShown(true)
            c.control.refreshNow()
            assertEquals(2, synchronized(streams.calls) { streams.calls.count { it == "listStreams" } })
            assertEquals(0, calls("queues"))

            c.streamEvents.setShown(false)
            c.navigation.open(AppDestination.Topology)
            c.topologyEvents.setShown(true)
            val queueCalls = calls("queues")
            val lists = synchronized(streams.calls) { streams.calls.count { it == "listStreams" } }
            c.control.refreshNow()
            assertEquals(queueCalls + 1, calls("queues"), "Refresh now must reach the shown Topology screen")
            assertEquals(lists, synchronized(streams.calls) { streams.calls.count { it == "listStreams" } }, "and not the hidden stream list")
        } finally {
            c.close()
        }
    }

    @Test
    fun `the control's age follows the Topology view model while Topology is the screen`() {
        val c = compose()
        try {
            c.connect()
            // the stream list was never shown: its age is null. Only Topology has loaded.
            c.navigation.open(AppDestination.Topology)
            c.topologyEvents.setShown(true)
            c.control.tick()
            assertNotNull(c.topology.state.value.updatedAtMillis)
            assertNotNull(c.control.state.value.refresh.note.takeIf { it.contains("Updated") || it.contains("ago") }, "the note says when Topology was loaded: ${c.control.state.value.refresh.note}")
        } finally {
            c.close()
        }
    }

    @Test
    fun `closing the composition stops the Topology screen for good`() {
        val c = compose()
        c.connect()
        c.topologyEvents.setShown(true)
        c.close()
        val before = topology.calls.size
        c.topologyEvents.setShown(true)
        c.topologyEvents.refreshNow()
        assertEquals(before, topology.calls.size)
    }
}
