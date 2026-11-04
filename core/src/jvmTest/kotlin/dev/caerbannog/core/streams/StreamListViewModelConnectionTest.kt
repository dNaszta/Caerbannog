package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-04 step 2: the view model follows `ActiveConnection.state` (Disconnected, Connecting, Connected, Failed) and the lost
 * connection rule D2. D2 is PROPOSED, not confirmed by the product owner: it is built as a flagged default in one place, these
 * tests pin that default (an Unreachable data call makes the link Failed("Connection lost"), rows stay stale, no automatic
 * reconnect) and must be changed together with that one place if the owner decides otherwise.
 * Contract: [StreamListHarness]; `ConnectionFailureReason.CONNECTION_LOST`; `ActiveConnection.connectionLost(source)` (see StreamSourceTest).
 */
class StreamListViewModelConnectionTest {

    @Test
    fun `before any connection the state is Not connected with no rows, even when the list is shown`() = runTest {
        val s = StreamListHarness(this)
        s.vm.setShown(true)
        s.settle()

        assertEquals(StreamListScreen.NotConnected, s.state.screen)
        assertEquals(StreamListTexts.NOT_CONNECTED, s.state.message)
        assertEquals("Not connected", s.state.message)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.catalog.calls.toList())
    }

    @Test
    fun `while Connecting the state is Connecting`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        val gate = s.h.gateway.holdOpens("uat-eu")
        s.vm.setShown(true)
        val connect = launch { s.h.active.connect(s.h.uat) }
        s.h.gateway.awaitOpenStarted()
        s.settle()

        assertEquals(StreamListScreen.Connecting, s.state.screen)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.catalog.calls.toList(), "no data call before Connected")

        gate.release()
        connect.join()
        s.settle()
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `a failed connection shows the reason and Retry, and Retry connects and then loads`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.h.gateway.failWith("uat-eu", FakeConnectionGateway.Failure.BROKER_NOT_REACHABLE)
        s.vm.setShown(true)
        s.h.active.connect(s.h.uat)
        s.settle()

        assertEquals(StreamListScreen.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), s.state.screen)
        assertEquals("The broker could not be reached", s.state.message)
        assertEquals("Retry", s.state.action)
        assertEquals(emptyList(), s.state.rows)

        s.h.gateway.succeed("uat-eu")
        s.vm.retry()
        s.settle()

        assertEquals(listOf("uat-eu"), s.reconnects, "Retry is a connect of the same profile")
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertEquals(2, s.state.rows.size)
    }

    @Test
    fun `Retry does nothing when the link is not Failed`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()
        val opens = s.h.gateway.openCalls.size

        s.vm.retry()
        s.settle()

        assertEquals(opens, s.h.gateway.openCalls.size)
        assertEquals(emptyList(), s.reconnects)
    }

    // --- D2 (proposed, flagged default) -----------------------------------------------------------------

    @Test
    fun `an unreachable broker on a tick makes the link Failed Connection lost, keeps the rows stale, no reconnect`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        val rows = s.state.rows

        s.catalog.unreachable = true
        s.pass(5)

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals("Connection lost", ConnectionFailureReason.CONNECTION_LOST)
        assertEquals(rows, s.state.rows, "the last rows stay")
        assertTrue(s.state.stale)
        assertEquals("Connection lost", s.state.banner)
        assertEquals("Retry", s.state.action)
        assertEquals(emptyList(), s.h.gateway.heldNow, "the dead connection was released")

        val calls = s.catalog.calls.size
        s.pass(600)
        assertEquals(calls, s.catalog.calls.size, "the timer stopped with the lost connection")
        assertEquals(1, s.h.gateway.openCalls.size, "no automatic reconnect")
        assertEquals(emptyList(), s.reconnects)
    }

    @Test
    fun `Retry after a lost connection reconnects, loads fresh rows and clears the stale mark and the banner`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a", messages = 3)
        s.open()
        s.catalog.unreachable = true
        s.pass(5)
        assertEquals("Connection lost", s.state.banner)

        s.catalog.unreachable = false
        s.addStream("a", messages = 8)
        s.vm.retry()
        s.settle()

        assertEquals(listOf("uat-eu"), s.reconnects)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
        assertFalse(s.state.stale)
        assertNull(s.state.banner)
        assertEquals("8", s.state.rows.single().messages)
        s.pass(5)
        assertEquals(true, s.count("listStreams") >= 3, "the timer runs again")
    }

    @Test
    fun `an unreachable broker on the very first load is Failed Connection lost with Retry`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.catalog.unreachable = true
        s.open()

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals(StreamListScreen.Failed("Connection lost"), s.state.screen)
        assertEquals("Retry", s.state.action)
        assertEquals(emptyList(), s.state.rows)
    }

    @Test
    fun `an unreachable stream port alone is not a lost connection`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.offsets.silent = true
        s.open()
        s.pass(30)

        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
        assertNull(s.state.banner)
        assertFalse(s.state.stale)
    }

    @Test
    fun `an unreachable broker on a manual refresh is lost too, and the old rows stay`() = runTest {
        val s = StreamListHarness(this)
        s.streams(2)
        s.open()
        val before = s.state.rows

        s.catalog.unreachable = true
        s.vm.refreshNow()
        s.settle()

        assertEquals(before, s.state.rows)
        assertTrue(s.state.stale)
        assertEquals("Connection lost", s.state.banner)
    }

    @Test
    fun `the state after Disconnect then Connect shows the new rows, not the old ones`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("old")
        s.open()
        s.h.active.disconnect()
        s.settle()
        assertEquals(emptyList(), s.state.rows)

        s.addStream("new")
        s.connect()

        assertEquals(listOf("new", "old"), s.names())
    }
}
