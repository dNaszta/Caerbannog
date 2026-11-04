package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamListRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * M2-04 step 2, the accessor decided in M2-03 part 2: `ActiveConnection` hands the stream list view model a small
 * `StreamSource`, only while Connected, never the `HeldConnection` (it is AutoCloseable: the UI must not be able to close it).
 * A source of a connection that was closed or superseded must not touch it: its calls answer `CatalogResult.Unreachable` /
 * `FirstOffset.Unavailable` and the closed connection (here: the fake) sees NO call. The view model ignores such answers by load
 * token, so they never turn into a D2 "Connection lost" of the new connection.
 * Contract (package `dev.caerbannog.core.connections`, commonMain):
 *   class StreamSource(val catalog: StreamCatalog, val offsets: StreamOffsets, val vhost: String)   -- NOT AutoCloseable
 *   val ActiveConnection.source: StreamSource?        -- non-null only in Connected; the vhost is the profile's virtual host
 *   fun ActiveConnection.connectionLost(source: StreamSource)   -- D2: if [source] is current, the link becomes
 *        Failed(name, ConnectionFailureReason.CONNECTION_LOST) and the held connection is closed once; otherwise nothing happens
 *   ConnectionFailureReason.CONNECTION_LOST = "Connection lost"  (public)
 * `catalog` and `offsets` of the source cover every call of the interfaces, so "touches nothing" is checked on all of them.
 */
class StreamSourceTest {

    @Test
    fun `there is no source unless Connected`() = runBlocking<Unit> {
        val h = Harness()
        assertNull(h.active.source, "never connected")

        val gate = h.gateway.holdOpens("uat-eu")
        val connect = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()
        assertIs<LinkState.Connecting>(h.active.state.value)
        assertNull(h.active.source, "Connecting")
        gate.release()
        connect.join()

        assertNotNull(h.active.source, "Connected")
        h.active.disconnect()
        assertNull(h.active.source, "Disconnected")

        h.gateway.failWith("prod-eu", FakeConnectionGateway.Failure.BROKER_NOT_REACHABLE)
        h.active.connect(h.prod)
        assertIs<LinkState.Failed>(h.active.state.value)
        assertNull(h.active.source, "Failed")
    }

    @Test
    fun `the source is not the held connection and cannot close it`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)

        val source = h.active.source!!

        assertFalse((source as Any) is AutoCloseable, "a source must not be closable by whoever receives it")
        assertFalse((source as Any) is HeldConnection)
        assertEquals("/", source.vhost)
        assertEquals(0, h.gateway.heldNow.single().closeCalls)
    }

    @Test
    fun `the vhost is the one of the profile`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat.copy(virtualHost = "/shop"))

        assertEquals("/shop", h.active.source!!.vhost)
    }

    @Test
    fun `while current the source reaches the catalog and the offsets of its connection`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.offsetsOf("uat-eu").setFirst("/", "s", 5)
        h.active.connect(h.uat)
        val source = h.active.source!!

        assertIs<CatalogResult.Success<*>>(source.catalog.listStreams(StreamListRequest(vhost = "/")))
        assertEquals(FirstOffset.Value(5), source.offsets.firstOffset("/", "s"))
        assertEquals(listOf("listStreams"), h.gateway.catalogOf("uat-eu").calls.toList())
        assertEquals(listOf("//s"), h.gateway.offsetsOf("uat-eu").calls.toList())
    }

    @Test
    fun `after Disconnect an old source answers Unreachable and Unavailable and touches nothing`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!
        val catalog = h.gateway.catalogOf("uat-eu")
        val offsets = h.gateway.offsetsOf("uat-eu")

        h.active.disconnect()

        assertOldSourceIsDead(old, catalog.calls, offsets.calls)
    }

    @Test
    fun `after a switch to another profile the old source is dead and the new one works`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!

        h.active.connect(h.prod)
        val fresh = h.active.source!!

        assertNotSame(old, fresh)
        assertOldSourceIsDead(old, h.gateway.catalogOf("uat-eu").calls, h.gateway.offsetsOf("uat-eu").calls)
        assertIs<CatalogResult.Success<*>>(fresh.catalog.listStreams(StreamListRequest(vhost = "/")))
        assertEquals(listOf("listStreams"), h.gateway.catalogOf("prod-eu").calls.toList())
    }

    @Test
    fun `a connect that supersedes the held connection of the same profile kills the old source too`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!
        val before = h.gateway.catalogOf("uat-eu").calls.size

        h.active.connect(h.uat)

        assertOldSourceIsDead(old, h.gateway.catalogOf("uat-eu").calls, h.gateway.offsetsOf("uat-eu").calls, expectedCatalogCalls = before)
        assertIs<CatalogResult.Success<*>>(h.active.source!!.catalog.listStreams(StreamListRequest(vhost = "/")))
    }

    @Test
    fun `a source of a profile that was changed or deleted is dead`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!

        h.active.profileChanged("uat-eu")

        assertEquals(CatalogResult.Unreachable, old.catalog.listStreams(StreamListRequest(vhost = "/")))
        assertNull(h.active.source)
    }

    private fun assertOldSourceIsDead(
        old: StreamSource,
        catalogCalls: List<String>,
        offsetCalls: List<String>,
        expectedCatalogCalls: Int = 0,
    ) = runBlocking<Unit> {
        assertEquals(CatalogResult.Unreachable, old.catalog.listStreams(StreamListRequest(vhost = "/")))
        assertEquals(CatalogResult.Unreachable, old.catalog.streamDetail("/", "s"))
        assertEquals(CatalogResult.Unreachable, old.catalog.publishers("/", "s"))
        assertEquals(CatalogResult.Unreachable, old.catalog.publisherCounts("/"))
        assertEquals(CatalogResult.Unreachable, old.catalog.consumers("/", "s"))
        assertEquals(CatalogResult.Unreachable, old.catalog.capabilities())
        assertEquals(FirstOffset.Unavailable, old.offsets.firstOffset("/", "s"))
        assertEquals(expectedCatalogCalls, catalogCalls.size, "the closed connection must not be touched: $catalogCalls")
        assertEquals(0, offsetCalls.size, "the closed connection must not be touched: $offsetCalls")
    }

    // --- D2: connection lost -----------------------------------------------------------------------------

    @Test
    fun `connectionLost with the current source fails the link, closes the connection once and kills the source`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val source = h.active.source!!

        h.active.connectionLost(source)
        h.active.connectionLost(source)

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), h.active.state.value)
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, "Connection lost")
        assertEquals(1, h.gateway.everOpened.single().closeCalls, "closed exactly once, however often it is reported")
        assertNull(h.active.source)
        assertEquals(CatalogResult.Unreachable, source.catalog.listStreams(StreamListRequest(vhost = "/")))
    }

    @Test
    fun `connectionLost with a superseded source changes nothing`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!
        h.active.connect(h.prod)

        h.active.connectionLost(old)

        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value)
        assertEquals("prod-eu", h.gateway.heldNow.single().profileName)
        assertNotNull(h.active.source)
    }

    @Test
    fun `connectionLost does not run a permission check or reconnect`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val checks = h.checker.calls.size

        h.active.connectionLost(h.active.source!!)

        assertEquals(checks, h.checker.calls.size)
        assertEquals(1, h.gateway.openCalls.size)
    }
}
