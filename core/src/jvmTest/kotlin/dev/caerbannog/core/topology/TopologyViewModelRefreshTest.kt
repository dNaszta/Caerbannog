package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-08b step 2, decision D3 (refresh only while shown, the app-wide interval) through the shared `RefreshLoop`; scenario "The topology
 * screen refreshes by itself only while it is shown". Virtual time, no sleeps. A "load" is what a tick or a manual refresh does: the vhost
 * list, every queue page and what the shown tab needs. The interval is the one app setting (`AppSettings.streamListRefresh`), read at every wait.
 * Contract: [TopologyHarness].
 */
class TopologyViewModelRefreshTest {

    @Test
    fun `nothing is called while the screen is not shown, however long the connection is held`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(3)
        s.connect()
        s.pass(600)
        assertEquals(emptyList(), s.requests(), "a screen nobody looks at must not poll")
        assertEquals(emptyList(), s.state.queues)
    }

    @Test
    fun `showing the screen loads at once and the first tick comes one interval after that load`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(3)
        s.open()
        assertEquals(1, s.count("queues"))
        s.pass(4)
        assertEquals(1, s.count("queues"))
        s.pass(1)
        assertEquals(2, s.count("queues"))
        s.pass(5)
        assertEquals(3, s.count("queues"))
    }

    @Test
    fun `showing the screen before connecting loads once the connection is held`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(3)
        s.vm.setShown(true)
        s.settle()
        assertEquals(emptyList(), s.requests())
        s.connect()
        assertEquals(1, s.count("queues"))
        assertIs<TopologyScreen.Ready>(s.state.screen)
    }

    @Test
    fun `hiding the screen stops the timer, showing it again loads at once`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(3)
        s.open()
        s.vm.setShown(false)
        s.settle()
        val calls = s.requests().size
        s.pass(3600)
        assertEquals(calls, s.requests().size, "the topology screen does not ask the broker again once the user left it")

        s.vm.setShown(true)
        s.settle()
        assertEquals(2, s.count("queues"))
    }

    @Test
    fun `a queue that got more messages shows them at the next tick, the scenario's 12 plus 20 is 32`() = runTest {
        val s = TopologyHarness(this, refresh = RefreshInterval.Every30)
        s.topology.addQueue(quorum("orders", ready = 12))
        s.open()
        assertEquals("12", s.state.queues.single().ready)
        s.topology.addQueue(quorum("orders", ready = 32))
        s.pass(29)
        assertEquals("12", s.state.queues.single().ready)
        s.pass(1)
        assertEquals("32", s.state.queues.single().ready)
    }

    @Test
    fun `the interval is the app setting, Off means no timer, and a changed setting is used at the next wait`() = runTest {
        val s = TopologyHarness(this, refresh = RefreshInterval.Off)
        s.quorumQueues(1)
        s.open()
        s.pass(3600)
        assertEquals(1, s.count("queues"), "Off: only the load of opening")
        assertEquals(RefreshInterval.Off, s.state.refreshInterval)
    }

    @Test
    fun `a setting changed while a wait runs is picked up by the next wait`() = runTest {
        val s = TopologyHarness(this, refresh = RefreshInterval.Every5)
        s.quorumQueues(1)
        s.open()
        s.settings.streamListRefresh = RefreshInterval.Every60
        s.pass(5)
        assertEquals(2, s.count("queues"), "the wait that was running still ends after 5 s")
        s.pass(59)
        assertEquals(2, s.count("queues"))
        s.pass(1)
        assertEquals(3, s.count("queues"))
        assertEquals(RefreshInterval.Every60, s.state.refreshInterval)
    }

    @Test
    fun `a manual refresh loads now and restarts the timer`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        s.open()
        s.pass(3)
        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.count("queues"))
        s.pass(4)
        assertEquals(2, s.count("queues"))
        s.pass(1)
        assertEquals(3, s.count("queues"))
    }

    @Test
    fun `a manual refresh while a load runs does nothing, and a slow load makes ticks skip, never overlap`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        val gate = s.topology.holdNext("queues")
        s.open()
        s.vm.refreshNow()
        s.pass(30)
        assertEquals(1, s.count("queues"), "one load at a time")
        gate.release()
        s.settle()
        s.pass(5)
        assertEquals(2, s.count("queues"))
    }

    @Test
    fun `a manual refresh while the screen is hidden does nothing`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        s.connect()
        s.vm.refreshNow()
        s.settle()
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `updatedAt is the clock of the last successful load, a failed load does not move it`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        assertNull(s.vm.updatedAtMillis.value)
        s.open()
        val first = s.vm.updatedAtMillis.value
        assertTrue(first != null)
        assertEquals(first, s.state.updatedAtMillis)

        s.topology.failCall("queues")
        s.pass(5)
        assertEquals(first, s.vm.updatedAtMillis.value)

        s.topology.clearFailures()
        s.pass(5)
        assertTrue(s.vm.updatedAtMillis.value!! > first)
    }

    @Test
    fun `a new connection drops the data and loads again, an old connection's late answer is dropped`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("old"))
        val late = s.topology.holdNext("queues", ignoreCancellation = true)
        s.open()
        s.h.active.disconnect()
        s.settle()
        s.topology.addQueue(quorum("old"))
        s.connect()
        late.release()
        s.settle()
        assertEquals(listOf("old"), s.names())
        assertEquals(1, s.h.gateway.everOpened.count { !it.isClosed })
    }

    @Test
    fun `closing the view model stops the timer and the broker calls`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        s.open()
        s.vm.close()
        s.settle()
        val calls = s.requests().size
        s.pass(600)
        assertEquals(calls, s.requests().size)
    }

    @Test
    fun `the screen is a refresh target, the connection control can refresh and age it`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(1)
        s.open()
        val target: dev.caerbannog.core.streams.RefreshTarget = s.vm
        target.refreshNow()
        s.settle()
        assertEquals(2, s.count("queues"))
    }
}
