package dev.caerbannog.core.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-06a step 2: the refresh rules of the tab (D3 per tab, D2, one load at a time, late results dropped), the same rules as the stream
 * list ([StreamListViewModelRefreshTest]) because the brief shares one driver. Virtual time, no sleeps. Contract: [ClientsHarness].
 * Pinned where the brief is silent:
 *  - the interval is read from `AppSettings.streamListRefresh` when the tab is shown, and again before every wait (a change made while
 *    the tab is shown is picked up by the next load at the latest; a wait already running is not shortened);
 *  - the first tick comes one interval after the first load ended.
 */
class StreamClientsViewModelRefreshTest {

    @Test
    fun `nothing is called while the tab is not shown, however long the connection is held`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        s.pass(600)

        assertEquals(emptyList(), s.catalog.calls.toList())
        assertEquals(emptyList(), s.offsets.calls.toList())
    }

    @Test
    fun `refreshNow while not shown calls nothing`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        s.vm.refreshNow()
        s.settle()
        assertEquals(emptyList(), s.catalog.calls.toList())
    }

    @Test
    fun `showing loads at once and the first tick comes one interval after that load`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        assertEquals(1, s.count("consumers"))

        s.pass(4)
        assertEquals(1, s.count("consumers"), "not before the interval")
        s.pass(1)
        assertEquals(2, s.count("consumers"))
        s.pass(5)
        assertEquals(3, s.count("consumers"))
        assertEquals(1, s.count("capabilities"), "capabilities are asked once per connection source")
    }

    @Test
    fun `the tab refreshes only while shown, leaving stops every call including the first offset`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.vm.hide()
        s.settle()
        val calls = s.allCalls()

        s.pass(60)

        assertEquals(calls, s.allCalls(), "the broker is not asked for publishers or consumers while the tab is hidden")
    }

    @Test
    fun `hide while a load runs cancels it and the late answer changes nothing`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        s.consumers(s.consumerAt("late", 48299))
        val gate = s.catalog.holdNext("consumers", ignoreCancellation = true)
        s.vm.show(ClientsHarness.STREAM)
        s.settle()
        s.vm.hide()
        s.settle()
        gate.release()
        s.settle()

        assertEquals(emptyList(), s.state.consumers)
        assertTrue(s.state.screen !is StreamClientsScreen.Content)
    }

    @Test
    fun `the interval is read when the tab starts, 30 seconds ticks every 30 seconds`() = runTest {
        val s = ClientsHarness(this, refresh = RefreshInterval.Every30)
        s.open()
        assertEquals(RefreshInterval.Every30, s.state.refreshInterval)

        s.pass(29)
        assertEquals(1, s.count("consumers"))
        s.pass(1)
        assertEquals(2, s.count("consumers"))
    }

    @Test
    fun `with the interval Off the tab loads once and refreshes only on demand`() = runTest {
        val s = ClientsHarness(this, refresh = RefreshInterval.Off)
        s.open()
        s.pass(600)
        assertEquals(1, s.count("consumers"))

        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.count("consumers"))
    }

    @Test
    fun `a change of the app setting is picked up by the next load at the latest`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.settings.streamListRefresh = RefreshInterval.Every60

        s.pass(5) // the wait that was already running ends, this load is the last one at the old cadence
        val afterNext = s.count("consumers")
        assertEquals(2, afterNext)
        s.pass(55)
        assertEquals(afterNext, s.count("consumers"), "now 60 seconds apart")
        s.pass(5)
        assertEquals(afterNext + 1, s.count("consumers"))
        assertEquals(RefreshInterval.Every60, s.state.refreshInterval)
    }

    @Test
    fun `a change of the app setting to Off stops the timer after the next load`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.settings.streamListRefresh = RefreshInterval.Off
        s.pass(5)
        val calls = s.allCalls()
        s.pass(600)
        assertEquals(calls, s.allCalls())
    }

    @Test
    fun `a manual refresh loads at once and the next tick is a full interval later`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.pass(3)
        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.count("consumers"))

        s.pass(4)
        assertEquals(2, s.count("consumers"))
        s.pass(1)
        assertEquals(3, s.count("consumers"))
    }

    @Test
    fun `a load that is still running makes the next ticks skip and a manual refresh does nothing`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        val gate = s.catalog.holdNext("consumers")
        s.vm.show(ClientsHarness.STREAM)
        s.settle()
        assertEquals(1, s.count("consumers"))

        s.pass(60)
        s.vm.refreshNow()
        s.settle()
        assertEquals(1, s.count("consumers"), "one load at a time")
        assertEquals(1, s.count("streamDetail"))

        gate.release()
        s.settle()
        assertIs<StreamClientsScreen.Content>(s.state.screen)
        s.pass(5)
        assertEquals(2, s.count("consumers"), "the timer is back after the slow load")
    }

    @Test
    fun `a failed tick keeps the rows stale and the timer runs on`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        s.catalog.failCall("publishers")
        s.pass(5)
        assertTrue(s.state.stale)
        assertEquals(1, s.state.consumers.size)
        s.pass(5)
        assertEquals(3, s.count("publishers"))
    }

    @Test
    fun `capabilities are asked again after a reconnect, the new source has its own`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.h.active.connect(s.h.uat) // same profile, new source
        s.settle()
        assertEquals(2, s.count("capabilities"))
    }

    @Test
    fun `a late result of the old connection is dropped after a switch`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("uat-late", 48299))
        s.addStream(s.prodCatalog, s.prodOffsets)
        s.prodCatalog.setConsumers("/", ClientsHarness.STREAM, listOf(s.consumerAt("prod", 48000)))
        s.connect()
        val gate = s.catalog.holdNext("consumers", ignoreCancellation = true)
        s.vm.show(ClientsHarness.STREAM)
        s.settle()

        s.h.active.connect(s.h.prod)
        s.settle()
        assertEquals(listOf("prod"), s.state.consumers.map { it.name })
        gate.release()
        s.settle()

        assertEquals(listOf("prod"), s.state.consumers.map { it.name }, "the answer of the replaced source must not overwrite the new one")
    }

    @Test
    fun `a late result of the old source is dropped after a same-profile reconnect`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("old-source", 48299))
        s.connect()
        val gate = s.catalog.holdNext("consumers", ignoreCancellation = true)
        s.vm.show(ClientsHarness.STREAM)
        s.settle()
        s.consumers(s.consumerAt("new-source", 48299))

        s.h.active.connect(s.h.uat)
        s.settle()
        gate.release()
        s.settle()

        assertEquals(listOf("new-source"), s.state.consumers.map { it.name })
    }

    @Test
    fun `close stops the timer and every later call is harmless`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.vm.close()
        s.settle()
        val calls = s.allCalls()

        s.pass(60)
        s.vm.show(ClientsHarness.STREAM)
        s.vm.refreshNow()
        s.vm.retry()
        s.vm.hide()
        s.settle()

        assertEquals(calls, s.allCalls())
    }

    // --- RefreshTarget ----------------------------------------------------------------------------

    @Test
    fun `updatedAtMillis is the clock at the last successful load, in the state and in the RefreshTarget flow`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        s.pass(7)
        assertNull(s.vm.updatedAtMillis.value, "null before the first load")
        s.vm.show(ClientsHarness.STREAM)
        s.settle()
        assertEquals(7_000L, s.state.updatedAtMillis)
        assertEquals(7_000L, s.vm.updatedAtMillis.value)

        s.pass(5)
        assertEquals(12_000L, s.vm.updatedAtMillis.value)

        s.catalog.failCall("consumers")
        s.pass(5)
        assertEquals(12_000L, s.vm.updatedAtMillis.value, "a failed load is not an update")
    }

    @Test
    fun `as a RefreshTarget, refreshNow loads the shown tab`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        val target: RefreshTarget = s.vm
        target.refreshNow()
        s.settle()
        assertEquals(2, s.count("consumers"))
        assertFalse(s.state.stale)
    }

    @Test
    fun `hide resets the age so a hidden tab claims no update`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.vm.hide()
        s.settle()
        assertNull(s.vm.updatedAtMillis.value)
        assertNull(s.state.updatedAtMillis)
    }
}
