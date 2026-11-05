package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.CatalogResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-08b step 2: which screen the topology view model is in, and what the screen says (brief 08b; scenarios "A vhost with no queues",
 * "A user without the management tag", "A lost connection keeps the last queues on screen", R2 "A virtual host the user cannot see says so").
 * Virtual time, fakes only. Contract: [TopologyHarness].
 */
class TopologyViewModelScreensTest {

    @Test
    fun `before any connection the screen says Not connected and nothing is called`() = runTest {
        val s = TopologyHarness(this)
        s.vm.setShown(true)
        s.settle()
        assertIs<TopologyScreen.NotConnected>(s.state.screen)
        assertEquals(TopologyTexts.NOT_CONNECTED, s.state.message)
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `while Connect is waiting the screen says Connecting`() = runTest {
        val s = TopologyHarness(this)
        val gate = s.h.gateway.holdOpens("uat-eu")
        s.vm.setShown(true)
        val connecting = launch { s.h.active.connect(s.profile) }
        s.h.gateway.awaitOpenStarted()
        s.settle()
        assertIs<TopologyScreen.Connecting>(s.state.screen)
        assertEquals(TopologyTexts.CONNECTING, s.state.message)
        gate.release()
        connecting.join()
    }

    @Test
    fun `a load that is still running shows Loading with the loading text, never a blank`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        val gate = s.topology.holdNext("queues")
        s.open()
        assertIs<TopologyScreen.Loading>(s.state.screen)
        assertEquals(TopologyTexts.LOADING, s.state.message)
        assertEquals(emptyList(), s.state.queues)
        gate.release()
        s.settle()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertNull(s.state.message)
    }

    @Test
    fun `queues make the screen Ready with the selected vhost, the tab and the quorum chip at their defaults`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals("/", s.state.vhost)
        assertEquals(TopologyTab.Queues, s.state.tab)
        assertEquals(QueueTypeFilter.Quorum, s.state.queueFilter)
        assertEquals("", s.state.search)
        assertEquals(listOf("q-00", "q-01"), s.names())
    }

    // --- scenario "A vhost with no queues" ---

    @Test
    fun `a vhost with no queues says there are no queues yet, and offers no action`() = runTest {
        val s = TopologyHarness(this)
        s.open()
        assertIs<TopologyScreen.Empty>(s.state.screen)
        assertEquals("There are no queues in this virtual host yet.", s.state.message)
        assertEquals(TopologyTexts.NO_QUEUES, s.state.message)
        assertNull(s.state.action, "nothing can be created in M2")
        assertEquals("", s.state.rangeText)
    }

    @Test
    fun `a vhost with only streams has no queues`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addStream("audit-log")
        s.open()
        assertIs<TopologyScreen.Empty>(s.state.screen)
    }

    @Test
    fun `a search that matches nothing is still Ready with no rows, not Empty`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(3)
        s.open()
        s.vm.setSearch("zzz")
        s.settle()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(emptyList(), s.state.queues)
        assertEquals(0, s.state.total)
        assertEquals("", s.state.rangeText)
    }

    @Test
    fun `the Bindings tab is Empty when only the hidden default exchange bindings exist`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertIs<TopologyScreen.Empty>(s.state.screen)
        assertEquals(TopologyTexts.NO_BINDINGS, s.state.message)
        s.vm.setShowDefaultExchange(true)
        s.settle()
        assertIs<TopologyScreen.Ready>(s.state.screen, "with the default exchange asked for there are rows")
    }

    // --- scenario "A user without the management tag" ---

    @Test
    fun `a user without the management tag gets the message, no rows, no switch, no banner`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.h.gateway.noManagementTag("uat-eu")
        s.open()
        assertIs<TopologyScreen.CannotRead>(s.state.screen)
        assertEquals("This user cannot read topology. Ask for the management tag.", s.state.message)
        assertEquals(TopologyTexts.NO_MANAGEMENT_TAG, s.state.message)
        assertEquals(emptyList(), s.state.queues)
        assertFalse(s.state.showVhostSwitch)
        assertEquals(emptyList(), s.state.vhosts)
        assertEquals(emptyList(), s.state.banners)
    }

    @Test
    fun `the no tag screen is not a failure and the connection stays Connected`() = runTest {
        val s = TopologyHarness(this)
        s.h.gateway.noManagementTag("uat-eu")
        s.open()
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
    }

    // --- a failed first load ---

    @Test
    fun `a first load the broker answers badly fails with a Retry that loads again`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.topology.failCall("queues", CatalogResult.Unexpected)
        s.open()
        assertIs<TopologyScreen.Failed>(s.state.screen)
        assertEquals(TopologyTexts.LOAD_FAILED, s.state.message)
        assertEquals("Retry", s.state.action)

        s.topology.clearFailures()
        s.vm.retry()
        s.settle()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(2, s.state.queues.size)
    }

    @Test
    fun `a later failed load keeps the rows and marks them stale, the next good load clears the mark`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        s.topology.failCall("queues", CatalogResult.Unexpected)
        s.pass(5)
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(2, s.state.queues.size)
        assertTrue(s.state.stale)
        assertNull(s.state.connectionBanner, "the broker answered: the connection is not lost")

        s.topology.clearFailures()
        s.pass(5)
        assertFalse(s.state.stale)
    }

    // --- R2: a virtual host the user cannot see ---

    @Test
    fun `the profile's own vhost the broker answers 404 for says so, drops the rows and keeps the switch`() = runTest {
        val s = TopologyHarness(this, vhost = "gone", declareProfileVhost = false)
        s.topology.declareVhost("billing")
        s.open()
        assertEquals(TopologyScreen.VhostUnavailable("gone"), s.state.screen)
        assertEquals(
            "Virtual host gone is not available to this user. It may have been deleted, or the user may have no permission on it.",
            s.state.message,
        )
        assertEquals(TopologyTexts.vhostUnavailable("gone"), s.state.message)
        assertEquals(emptyList(), s.state.queues)
        assertTrue(s.state.showVhostSwitch)
        assertFalse(s.state.stale, "stale is only for an unreachable broker (D2)")
        assertNull(s.state.connectionBanner)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
    }

    @Test
    fun `a virtual host that disappears while shown drops its rows at the next tick`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.topology.declareVhost("billing")
        s.open()
        s.vm.selectVhost("billing")
        s.settle()
        s.topology.addQueue(quorum("inv", vhost = "billing"))
        s.pass(5)
        assertEquals(listOf("inv"), s.names())

        s.topology.permittedVhosts = setOf("/")
        s.pass(5)

        assertEquals(TopologyScreen.VhostUnavailable("billing"), s.state.screen)
        assertEquals(emptyList(), s.state.queues)
        assertFalse(s.state.stale)
    }

    // --- D2: a lost connection (same rule as the stream list) ---

    @Test
    fun `an unreachable broker on a tick keeps the queues stale with the banner, Retry, and never reconnects by itself`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        val rows = s.state.queues

        s.topology.unreachable = true
        s.pass(5)

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals(rows, s.state.queues, "the last rows stay")
        assertTrue(s.state.stale)
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, s.state.connectionBanner)
        assertEquals("Retry", s.state.action)
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(emptyList<FakeConnectionGateway.Held>(), s.h.gateway.heldNow)

        s.pass(600)
        assertEquals(1, s.h.gateway.openCalls.size, "no automatic reconnect")
        assertEquals(emptyList(), s.reconnects)
    }

    @Test
    fun `Retry after a lost connection reconnects and loads fresh rows`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("orders", ready = 3))
        s.open()
        s.topology.unreachable = true
        s.pass(5)

        s.topology.unreachable = false
        s.topology.addQueue(quorum("orders", ready = 8))
        s.vm.retry()
        s.settle()

        assertEquals(listOf("uat-eu"), s.reconnects)
        assertEquals(listOf("8"), s.state.queues.map { it.ready })
        assertFalse(s.state.stale)
        assertNull(s.state.connectionBanner)
        assertNull(s.state.action)
    }

    @Test
    fun `an unreachable broker on the very first load is a lost connection with nothing to show`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.topology.unreachable = true
        s.open()
        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals(emptyList(), s.state.queues)
        assertIs<TopologyScreen.Failed>(s.state.screen)
        assertEquals("Retry", s.state.action)
    }

    @Test
    fun `a disconnect clears everything and says Not connected`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        s.h.active.disconnect()
        s.settle()
        assertIs<TopologyScreen.NotConnected>(s.state.screen)
        assertEquals(emptyList(), s.state.queues)
        assertEquals(emptyList(), s.state.vhosts)
        assertEquals("", s.state.vhost)
    }

    @Test
    fun `classic and quorum queues both make the screen Ready, the filter only changes the rows`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(classic("legacy"))
        s.open()
        assertIs<TopologyScreen.Ready>(s.state.screen, "a classic queue is a queue: the vhost is not empty")
        assertEquals(emptyList(), s.state.queues, "but the Quorum chip hides it")
    }
}
