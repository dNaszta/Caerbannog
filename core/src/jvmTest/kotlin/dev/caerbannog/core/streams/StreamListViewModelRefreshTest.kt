package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-04 step 2, decision D3 (refresh, default 5 s) with the "latest started wins" rule of M2-01 applied to data calls.
 * All in virtual time (`runTest`, the view model runs in `backgroundScope`): no sleeps, deterministic. Contract: [StreamListHarness].
 * A "load" is what a tick or a manual refresh does: the list calls, one publisherCounts call and the offsets of the visible rows.
 * Pinned beyond the brief text (flagged in the report): nothing at all is called while the list is not shown (the first load waits
 * for [StreamListViewModel.setShown]); a page, filter or sort change does NOT call the broker for the list (paging is in
 * memory) but asks the first offsets of newly visible rows at once and restarts the timer.
 */
class StreamListViewModelRefreshTest {

    private fun StreamListHarness.listCalls() = count("listStreams")

    @Test
    fun `nothing is called while the list is not shown, however long the connection is held`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.connect()
        s.pass(600)

        assertEquals(emptyList(), s.catalog.calls.toList(), "a list nobody looks at must not poll")
        assertEquals(emptyList(), s.offsets.calls.toList())
        assertEquals(emptyList(), s.state.rows)
    }

    @Test
    fun `showing the list loads it at once, and the first tick comes 5 seconds after that load`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        assertEquals(1, s.listCalls())

        s.pass(4)
        assertEquals(1, s.listCalls(), "4 s after the load: no tick yet")
        s.pass(1)
        assertEquals(2, s.listCalls(), "the first tick is 5 s after the first load")
        s.pass(5)
        assertEquals(3, s.listCalls())
    }

    @Test
    fun `showing the list before connecting loads once the connection is held`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.vm.setShown(true)
        s.settle()
        assertEquals(emptyList(), s.catalog.calls.toList(), "not connected: no call")

        s.connect()

        assertEquals(1, s.listCalls())
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `hiding the list stops the timer, showing it again loads at once`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()

        s.vm.setShown(false)
        s.settle()
        s.pass(60)
        assertEquals(1, s.listCalls(), "no tick while hidden")

        s.vm.setShown(true)
        s.settle()
        assertEquals(2, s.listCalls(), "shown again: a load at once")
        s.pass(5)
        assertEquals(3, s.listCalls())
    }

    @Test
    fun `with the interval off the list never refreshes by itself`() = runTest {
        val s = StreamListHarness(this, refresh = RefreshInterval.Off)
        s.streams(3)
        s.open()
        s.pass(600)

        assertEquals(1, s.listCalls())
    }

    @Test
    fun `a 30 second interval ticks every 30 seconds and not before`() = runTest {
        val s = StreamListHarness(this, refresh = RefreshInterval.Every30)
        s.addStream("orders-events", messages = 101, first = 0)
        s.open()
        s.addStream("orders-events", messages = 151, first = 0)

        s.pass(29)
        assertEquals("100", s.state.rows.single().lastOffset)
        s.pass(1)
        assertEquals("150", s.state.rows.single().lastOffset)
        assertEquals(2, s.listCalls())
    }

    @Test
    fun `changing the interval writes the setting and restarts the timer from the change`() = runTest {
        val s = StreamListHarness(this, refresh = RefreshInterval.Every60)
        s.streams(2)
        s.open()
        s.pass(30)

        s.vm.setRefreshInterval(RefreshInterval.Every10)
        s.settle()
        assertEquals(RefreshInterval.Every10, s.settings.streamListRefresh)
        assertEquals(RefreshInterval.Every10, s.state.refreshInterval)

        s.pass(9)
        assertEquals(1, s.listCalls())
        s.pass(1)
        assertEquals(2, s.listCalls(), "10 s after the change, not 30 s after the old timer started")
        s.pass(10)
        assertEquals(3, s.listCalls())
    }

    @Test
    fun `switching the interval to off stops the timer`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()

        s.vm.setRefreshInterval(RefreshInterval.Off)
        s.settle()
        s.pass(600)

        assertEquals(1, s.listCalls())
        assertEquals(RefreshInterval.Off, s.settings.streamListRefresh)
    }

    @Test
    fun `a tick is a data call, never a permission check and never a write to the session`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()
        val checks = s.h.checker.calls.size
        val sessionBefore = s.h.session.state.value

        s.pass(5)
        s.pass(5)
        s.vm.refreshNow()
        s.settle()

        assertEquals(checks, s.h.checker.calls.size, "no permission check from a tick or a refresh")
        assertEquals(sessionBefore, s.h.session.state.value)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
        assertEquals(1, s.h.gateway.openCalls.size, "and no reconnect")
    }

    // --- overlap -----------------------------------------------------------------------------------------

    @Test
    fun `a tick that is still running makes the next ticks skip`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        val gate = s.catalog.holdNext("listStreams")

        s.pass(5)
        assertEquals(2, s.listCalls(), "tick 2 started and is held")
        s.pass(60)
        assertEquals(2, s.listCalls(), "no second load while the first is running")

        gate.release()
        s.settle()
        assertFalse(s.state.stale)
        s.pass(5)
        assertEquals(3, s.listCalls(), "after the held tick finished, the timer goes on")
    }

    @Test
    fun `a manual refresh while a tick runs does not start a second load`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        val gate = s.catalog.holdNext("listStreams")
        s.pass(5)
        assertEquals(2, s.listCalls())

        s.vm.refreshNow()
        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.listCalls(), "one load at a time")

        gate.release()
        s.settle()
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `a manual refresh loads at once and the next tick is a full interval later`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        s.pass(3)

        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.listCalls())
        s.pass(4)
        assertEquals(2, s.listCalls(), "the timer restarted with the manual refresh")
        s.pass(1)
        assertEquals(3, s.listCalls())
    }

    // --- failure -----------------------------------------------------------------------------------------

    @Test
    fun `a failed tick keeps the rows, marks them stale and the timer keeps running`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a", messages = 3)
        s.open()
        val rows = s.state.rows
        assertFalse(s.state.stale)

        s.catalog.failListPage(1, CatalogResult.Unexpected)
        s.pass(5)
        assertEquals(rows, s.state.rows)
        assertTrue(s.state.stale)
        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertNull(s.state.banner, "a broker hiccup is not a lost connection")

        s.pass(5)
        assertEquals(3, s.listCalls(), "the timer did not stop")

        s.catalog.clearFailures()
        s.addStream("a", messages = 9)
        s.pass(5)
        assertFalse(s.state.stale)
        assertEquals("9", s.state.rows.single().messages)
    }

    @Test
    fun `updated N seconds ago counts from the last successful load`() = runTest {
        val s = StreamListHarness(this, refresh = RefreshInterval.Off)
        s.streams(2)
        assertNull(s.vm.updatedSecondsAgo())
        s.open()
        assertEquals(0L, s.vm.updatedSecondsAgo())

        s.pass(10)
        assertEquals(10L, s.vm.updatedSecondsAgo())

        s.catalog.failListPage(1, CatalogResult.Unexpected)
        s.vm.refreshNow()
        s.settle()
        s.pass(2)
        assertEquals(12L, s.vm.updatedSecondsAgo(), "a failed refresh does not reset the age")

        s.catalog.clearFailures()
        s.vm.refreshNow()
        s.settle()
        assertEquals(0L, s.vm.updatedSecondsAgo())
    }

    @Test
    fun `refresh updates the statistics`() = runTest {
        val s = StreamListHarness(this, refresh = RefreshInterval.Off)
        s.addStream("orders-events", messages = 101, first = 0)
        s.open()
        s.addStream("orders-events", messages = 151, first = 0)
        assertEquals("100", s.state.rows.single().lastOffset)

        s.vm.refreshNow()
        s.settle()

        assertEquals("150", s.state.rows.single().lastOffset)
    }

    // --- page, filter, sort -----------------------------------------------------------------------------

    @Test
    fun `a page change asks the offsets of the new rows at once, not the list, and restarts the timer`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.streams(12)
        s.open()
        s.pass(3)

        s.vm.nextPage()
        s.settle()
        assertEquals(1, s.listCalls())
        assertEquals(10, s.offsets.calls.size)
        assertEquals("6 to 10 of 12", s.state.rangeText)
        assertEquals("0", s.state.rows.first().firstOffset, "the offsets of the new rows are loaded at once")

        s.pass(4)
        assertEquals(1, s.listCalls(), "the timer restarted at the page change: 3 + 4 s would have ticked")
        s.pass(1)
        assertEquals(2, s.listCalls())
    }

    // --- close -------------------------------------------------------------------------------------------

    @Test
    fun `close stops the timer`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()

        s.vm.close()
        s.pass(600)

        assertEquals(1, s.listCalls())
    }

    // --- Disconnect, switch, supersede, late results ----------------------------------------------------

    @Test
    fun `Disconnect stops the timer, clears the rows and says Not connected`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()

        s.h.active.disconnect()
        s.settle()
        s.pass(600)

        assertEquals(1, s.listCalls(), "no tick after Disconnect")
        assertEquals(StreamListScreen.NotConnected, s.state.screen)
        assertEquals("Not connected", s.state.message)
        assertEquals(emptyList(), s.state.rows)
    }

    @Test
    fun `Disconnect while a tick runs cancels it, and the late answer changes nothing`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()
        val gate = s.catalog.holdNext("listStreams", ignoreCancellation = true)
        s.pass(5)
        assertEquals(2, s.listCalls())

        s.h.active.disconnect()
        s.settle()
        val after = s.state
        gate.release()
        s.settle()
        s.pass(60)

        assertEquals(after, s.state, "a late result of a dropped tick must not bring rows back")
        assertEquals(StreamListScreen.NotConnected, s.state.screen)
        assertEquals(2, s.listCalls())
    }

    @Test
    fun `connecting to another profile cancels the tick of the old one and loads the new one`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("uat-only")
        s.prodCatalog.addStream(stream("prod-only"))
        s.prodOffsets.setFirst("/", "prod-only", 0)
        s.open()
        val oldCalls = s.catalog.calls.size

        s.h.active.connect(s.h.prod)
        s.settle()

        assertEquals(listOf("prod-only"), s.names())
        s.pass(60)
        assertEquals(oldCalls, s.catalog.calls.size, "the old connection's catalog is never called again")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value)
    }

    @Test
    fun `a late result of a tick of the old connection is dropped after a switch`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("uat-only")
        s.prodCatalog.addStream(stream("prod-only"))
        s.prodOffsets.setFirst("/", "prod-only", 0)
        s.open()
        val gate = s.catalog.holdNext("listStreams", ignoreCancellation = true)
        s.pass(5)

        s.h.active.connect(s.h.prod)
        s.settle()
        assertEquals(listOf("prod-only"), s.names())
        gate.release()
        s.settle()

        assertEquals(listOf("prod-only"), s.names(), "the uat answer must not replace the prod rows")
        assertFalse(s.state.stale)
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value)
    }

    @Test
    fun `a late failure of the old connection never turns into a lost connection of the new one`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("uat-only")
        s.prodCatalog.addStream(stream("prod-only"))
        s.prodOffsets.setFirst("/", "prod-only", 0)
        s.open()
        s.catalog.unreachable = true
        val gate = s.catalog.holdNext("listStreams", ignoreCancellation = true)
        s.pass(5)

        s.h.active.connect(s.h.prod)
        s.settle()
        gate.release()
        s.settle()
        s.pass(30)

        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "D2 must not fire for a superseded connection")
        assertNull(s.state.banner)
        assertEquals(listOf("prod-only"), s.names())
    }

    @Test
    fun `a connect that supersedes the held one for the same profile starts a clean timer`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()
        s.pass(3)

        // The broker answers slowly, as a real one does: the view model sees Connecting before the new Connected.
        // (Connected("uat-eu") equals the old state, so a view model that only compared states would never notice.)
        val gate = s.h.gateway.holdOpens("uat-eu")
        val reconnect = launch { s.h.active.connect(s.h.uat) }
        s.h.gateway.awaitOpenStarted()
        s.settle()
        assertIs<StreamListScreen.Connecting>(s.state.screen)
        gate.release()
        reconnect.join()
        s.settle()
        val afterReconnect = s.listCalls()
        assertEquals(2, afterReconnect, "the new connection loads once")
        s.pass(4)
        assertEquals(2, s.listCalls(), "the old timer (3 s in) is gone, the new one is 4 s in")
        s.pass(1)
        assertEquals(3, s.listCalls())
        assertEquals(2, s.h.gateway.everOpened.size)
        assertEquals(1, s.h.gateway.heldNow.size)
    }
}
