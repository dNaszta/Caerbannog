package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-06a step 2: the screens of the tab and what each load asks (scenarios 04: plugin missing, management tag, no tag, stream deleted;
 * mockup states of docs/ux/Clients.dc.html). Contract: [ClientsHarness]. Pinned where the brief is silent:
 *  - plugin missing keeps the header: `stream` and `lastOffset` are filled, only the tables are replaced by `message` = PLUGIN_MISSING;
 *  - a user without the management tag: no call after `capabilities`, `message` = NOT_MANAGEMENT_USER, no rows;
 *  - a screen without sections has `message` set, the Content screen has `message` null.
 */
class StreamClientsViewModelScreensTest {

    @Test
    fun `before any connection the tab says Not connected and calls nothing, even when shown`() = runTest {
        val s = ClientsHarness(this)
        s.vm.show(ClientsHarness.STREAM)
        s.settle()

        assertEquals(StreamClientsScreen.NotConnected, s.state.screen)
        assertEquals("Not connected", s.state.message)
        assertEquals(emptyList(), s.catalog.calls.toList())
    }

    @Test
    fun `while Connecting the tab says Connecting`() = runTest {
        val s = ClientsHarness(this)
        s.vm.show(ClientsHarness.STREAM)
        val gate = s.h.gateway.holdOpens("uat-eu")
        val connect = launch { s.h.active.connect(s.h.uat) }
        s.h.gateway.awaitOpenStarted()
        s.settle()

        assertEquals(StreamClientsScreen.Connecting, s.state.screen)
        assertEquals("Connecting...", s.state.message)
        assertEquals(emptyList(), s.catalog.calls.toList(), "no data call before Connected")
        gate.release()
        connect.join()
        s.settle()
        assertIs<StreamClientsScreen.Content>(s.state.screen)
    }

    @Test
    fun `while the first load runs the tab is Loading with the stream name`() = runTest {
        val s = ClientsHarness(this)
        s.connect()
        val gate = s.catalog.holdNext("publishers")
        s.vm.show(ClientsHarness.STREAM)
        s.settle()

        assertEquals(StreamClientsScreen.Loading, s.state.screen)
        assertEquals(ClientsHarness.STREAM, s.state.stream)
        assertEquals("loading...", s.state.message)
        assertEquals(emptyList(), s.state.publishers)
        gate.release()
        s.settle()
        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertNull(s.state.message)
    }

    @Test
    fun `a load asks capabilities once, then the stream, its first offset, publishers and consumers`() = runTest {
        val s = ClientsHarness(this)
        s.open()

        assertEquals(1, s.count("capabilities"))
        assertEquals(1, s.count("streamDetail"))
        assertEquals(1, s.count("publishers"))
        assertEquals(1, s.count("consumers"))
        assertEquals(listOf("//${ClientsHarness.STREAM}"), s.offsets.calls.toList(), "one first-offset call")
        assertEquals(0, s.count("listStreams"), "the tab never lists streams")
        assertEquals(0, s.count("publisherCounts"), "the per-stream publishers call is the tab's, not the vhost count")
    }

    // --- plugin missing --------------------------------------------------------------------------------

    @Test
    fun `plugin missing says so, keeps the stream name and last offset, and asks nothing about publishers or consumers`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.pluginPresent = false
        s.open()

        assertEquals(StreamClientsScreen.PluginMissing, s.state.screen)
        assertEquals(StreamClientsTexts.PLUGIN_MISSING, s.state.message)
        assertTrue(s.state.message!!.contains("rabbitmq_stream_management"))
        assertEquals(ClientsHarness.STREAM, s.state.stream)
        assertEquals("48299", s.state.lastOffset, "the rest of the screen keeps working")
        assertEquals(emptyList(), s.state.publishers)
        assertEquals(emptyList(), s.state.consumers)
        assertEquals(0, s.count("publishers") + s.count("consumers"), "capabilities already said the plugin is missing")
    }

    @Test
    fun `a plugin that disappears after the first load is PluginMissing from the call that says so`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        assertIs<StreamClientsScreen.Content>(s.state.screen)

        s.catalog.pluginPresent = false // capabilities are cached per connection source, so the data call is what finds out
        s.pass(5)

        assertEquals(StreamClientsScreen.PluginMissing, s.state.screen)
        assertEquals(StreamClientsTexts.PLUGIN_MISSING, s.state.message)
        assertEquals(emptyList(), s.state.consumers)
    }

    // --- tags ---------------------------------------------------------------------------------------

    @Test
    fun `a management tag user sees the notice and the rows`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.visibility = Visibility.OWN_CONNECTIONS_ONLY
        s.consumers(s.consumerAt("own", 48299))
        s.open()

        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals("Showing only your own connections. The monitoring tag shows all.", s.state.ownConnectionsNotice)
        assertEquals(listOf("own"), s.state.consumers.map { it.name })
    }

    @Test
    fun `the notice stays when both lists are empty, never a bare empty state`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.visibility = Visibility.OWN_CONNECTIONS_ONLY
        s.open()

        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(StreamClientsTexts.OWN_CONNECTIONS_NOTICE, s.state.ownConnectionsNotice)
        assertEquals(emptyList(), s.state.publishers)
        assertEquals(emptyList(), s.state.consumers)
    }

    @Test
    fun `a user with the monitoring tag sees no notice`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.visibility = Visibility.FULL
        s.open()
        assertNull(s.state.ownConnectionsNotice)
    }

    @Test
    fun `a user without the management tag gets the message, no rows and no data call after capabilities`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.catalog.visibility = Visibility.NONE
        s.open()

        assertEquals(StreamClientsScreen.CannotReadStatistics, s.state.screen)
        assertEquals("This user cannot read stream statistics. Ask for the monitoring tag.", s.state.message)
        assertEquals(CatalogMessages.NOT_MANAGEMENT_USER, s.state.message)
        assertEquals(emptyList(), s.state.publishers)
        assertEquals(emptyList(), s.state.consumers)
        assertNull(s.state.ownConnectionsNotice)
        assertEquals(listOf("capabilities"), s.catalog.calls.toList(), "nothing but the capability probe may be called")
    }

    // --- stream gone --------------------------------------------------------------------------------

    @Test
    fun `a stream that does not exist is StreamGone`() = runTest {
        val s = ClientsHarness(this)
        s.open("no-such-stream")

        assertEquals(StreamClientsScreen.StreamGone, s.state.screen)
        assertEquals("This stream no longer exists.", s.state.message)
        assertEquals(emptyList(), s.state.consumers)
    }

    @Test
    fun `a stream deleted while the tab is open becomes StreamGone on the next refresh and the rows go`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        assertEquals(1, s.state.consumers.size)

        s.catalog.deleteStream(ClientsHarness.STREAM)
        s.pass(5)

        assertEquals(StreamClientsScreen.StreamGone, s.state.screen)
        assertEquals("This stream no longer exists.", s.state.message)
        assertEquals(emptyList(), s.state.consumers)
        assertEquals(emptyList(), s.state.publishers)
        assertEquals(ClientsHarness.STREAM, s.state.stream, "the name stays for the header")
    }

    @Test
    fun `NotFound from the publishers call after a good detail call means the stream is gone too`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        val gate = s.catalog.holdNext("streamDetail") // the detail answer is computed before the stream vanishes
        s.vm.refreshNow()
        s.settle()
        s.catalog.deleteStream(ClientsHarness.STREAM)
        gate.release()
        s.settle()

        assertEquals(StreamClientsScreen.StreamGone, s.state.screen)
        assertEquals(emptyList(), s.state.consumers)
    }

    @Test
    fun `a stream that comes back is shown again on the next refresh`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.catalog.deleteStream(ClientsHarness.STREAM)
        s.pass(5)
        assertEquals(StreamClientsScreen.StreamGone, s.state.screen)

        s.addStream(s.catalog, s.offsets)
        s.pass(5)
        assertIs<StreamClientsScreen.Content>(s.state.screen)
    }

    // --- failures ----------------------------------------------------------------------------------

    @Test
    fun `an unexpected answer on the first load is Failed with the load text, and Retry loads again`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.failCall("consumers")
        s.open()

        assertEquals(StreamClientsScreen.Failed("Publishers and consumers could not be loaded."), s.state.screen)
        assertEquals(StreamClientsTexts.LOAD_FAILED, s.state.message)
        assertEquals(emptyList(), s.state.consumers)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value, "an unexpected answer is not a lost connection")

        s.catalog.clearFailures()
        s.vm.retry()
        s.settle()
        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(emptyList(), s.reconnects, "Retry of a failed load loads again, it does not reconnect")
    }

    @Test
    fun `an unexpected answer on a later load keeps the rows and marks them stale, the timer keeps running`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()

        s.catalog.failCall("consumers")
        s.pass(5)

        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(1, s.state.consumers.size)
        assertTrue(s.state.stale)
        s.catalog.clearFailures()
        s.pass(5)
        assertEquals(false, s.state.stale, "the next good load clears it")
    }

    @Test
    fun `an unreachable broker on the first load is Failed Connection lost with Retry`() = runTest {
        val s = ClientsHarness(this)
        s.catalog.unreachable = true
        s.open()

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals(StreamClientsScreen.Failed("Connection lost"), s.state.screen)
        assertEquals("Connection lost", s.state.message)
        assertEquals(emptyList(), s.state.consumers)
    }

    @Test
    fun `a failed connection shows the reason, Retry connects and then loads`() = runTest {
        val s = ClientsHarness(this)
        s.h.gateway.failWith("uat-eu", FakeConnectionGateway.Failure.BROKER_NOT_REACHABLE)
        s.vm.show(ClientsHarness.STREAM)
        s.h.active.connect(s.h.uat)
        s.settle()
        assertEquals(StreamClientsScreen.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), s.state.screen)

        s.h.gateway.succeed("uat-eu")
        s.vm.retry()
        s.settle()

        assertEquals(listOf("uat-eu"), s.reconnects)
        assertIs<StreamClientsScreen.Content>(s.state.screen)
    }

    @Test
    fun `Retry does nothing when the link is fine and the tab shows rows`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        val calls = s.allCalls()
        s.vm.retry()
        s.settle()
        assertEquals(emptyList(), s.reconnects)
        assertEquals(calls, s.allCalls())
    }

    // --- D2, lost connection -----------------------------------------------------------------------

    @Test
    fun `a lost connection keeps the rows stale with the banner, no reconnect, the timer stops`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        val rows = s.state.consumers

        s.catalog.unreachable = true
        s.pass(5)

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(rows, s.state.consumers, "the rows stay")
        assertTrue(s.state.stale)
        assertEquals("Connection lost", s.state.banner)
        val calls = s.allCalls()
        s.pass(600)
        assertEquals(calls, s.allCalls(), "no call after the loss")
        assertEquals(1, s.h.gateway.openCalls.size, "no automatic reconnect")
        assertEquals(emptyList(), s.reconnects)
    }

    @Test
    fun `Retry after a lost connection reconnects, loads fresh rows and clears the stale mark`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        s.catalog.unreachable = true
        s.pass(5)

        s.catalog.unreachable = false
        s.consumers(s.consumerAt("billing", 48000))
        s.vm.retry()
        s.settle()

        assertEquals(listOf("uat-eu"), s.reconnects)
        assertEquals(false, s.state.stale)
        assertNull(s.state.banner)
        assertEquals("299 behind", s.state.consumers.single().status.text)
    }

    @Test
    fun `an unreachable stream port alone is not a lost connection and the tab still shows its rows`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.offsets.silent = true
        s.open()
        s.pass(30)

        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
        assertEquals("unavailable", s.state.lastOffset)
        assertEquals(1, s.state.consumers.size)
        assertNull(s.state.banner)
    }

    // --- connection changes ------------------------------------------------------------------------

    @Test
    fun `Disconnect clears the rows and says Not connected`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()
        s.h.active.disconnect()
        s.settle()

        assertEquals(StreamClientsScreen.NotConnected, s.state.screen)
        assertEquals(emptyList(), s.state.consumers)
        assertEquals(emptyList(), s.state.publishers)
        assertNull(s.state.updatedAtMillis)
        val calls = s.allCalls()
        s.pass(600)
        assertEquals(calls, s.allCalls())
    }

    @Test
    fun `switching to another connection keeps the shown stream, and a broker without it says the stream is gone`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()

        s.h.active.connect(s.h.prod) // the prod fake has no "orders-events"
        s.settle()

        assertEquals(ClientsHarness.STREAM, s.state.stream)
        assertEquals(StreamClientsScreen.StreamGone, s.state.screen)
        assertEquals(emptyList(), s.state.consumers, "the rows of the old broker are gone")
        assertEquals(1, s.prodCatalog.calls.count { it == "capabilities" })
    }

    @Test
    fun `switching to a broker that has the stream shows that broker's rows`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("uat-only", 48299))
        s.addStream(s.prodCatalog, s.prodOffsets)
        s.prodCatalog.setConsumers("/", ClientsHarness.STREAM, listOf(s.consumerAt("prod-only", 48000)))
        s.open()

        s.h.active.connect(s.h.prod)
        s.settle()

        assertEquals(listOf("prod-only"), s.state.consumers.map { it.name })
    }

    // --- show and hide -----------------------------------------------------------------------------

    @Test
    fun `showing another stream clears the data of the first at once and loads the new one`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.addStream(s.catalog, s.offsets, name = "other", messages = 10)
        s.catalog.setConsumers("/", "other", listOf(s.consumerAt("o-cons", 9)))
        s.open()
        assertEquals(listOf("billing"), s.state.consumers.map { it.name })

        val gate = s.catalog.holdNext("consumers")
        s.vm.show("other")
        s.settle()

        assertEquals("other", s.state.stream)
        assertEquals(emptyList(), s.state.consumers, "nothing of the previous stream while the new one loads")
        assertEquals(StreamClientsScreen.Loading, s.state.screen)
        gate.release()
        s.settle()
        assertEquals(listOf("o-cons"), s.state.consumers.map { it.name })
        assertEquals("9", s.state.lastOffset)
    }

    @Test
    fun `hide clears the data, shows nothing and calls nothing`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.publishers(dev.caerbannog.core.connections.fakes.FakeStreamCatalog.publisher("p", 5))
        s.open()

        s.vm.hide()
        s.settle()

        assertEquals(emptyList(), s.state.consumers)
        assertEquals(emptyList(), s.state.publishers)
        assertTrue(s.state.screen !is StreamClientsScreen.Content, "a hidden tab holds no rows, was ${s.state.screen}")
        val calls = s.allCalls()
        s.pass(600)
        assertEquals(calls, s.allCalls())
    }

    @Test
    fun `show again after hide loads at once`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        s.vm.hide()
        s.settle()
        val before = s.count("consumers")

        s.vm.show(ClientsHarness.STREAM)
        s.settle()

        assertEquals(before + 1, s.count("consumers"))
        assertIs<StreamClientsScreen.Content>(s.state.screen)
    }

    @Test
    fun `showing the same stream twice does not start a second load`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        val before = s.count("consumers")
        s.vm.show(ClientsHarness.STREAM)
        s.settle()
        assertEquals(before, s.count("consumers"))
    }
}
