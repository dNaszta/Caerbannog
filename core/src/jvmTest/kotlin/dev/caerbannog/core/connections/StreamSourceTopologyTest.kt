package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.QueuePage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M2-08b step 2: the fourth member of the source (brief Design notes "Source"): `StreamSource.topology`, the topology of the SAME held
 * connection, guarded like `catalog` (`GuardedTopology`): once the connection is closed or superseded every call answers
 * `CatalogResult.Unreachable` and the closed connection (the fake) sees NO call. Contract:
 *   class StreamSource(catalog, offsets, vhost, topology: TopologyCatalog)         -- no default
 *   interface HeldConnection { val topology: TopologyCatalog }                      -- the real gateway wires `ManagementTopologyCatalog`
 *   core-testing: `FakeConnectionGateway.topologyOf(profileName)`, `Held.topology`
 */
class StreamSourceTopologyTest {

    @Test
    fun `while current the source reaches the topology of its connection`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.topologyOf("uat-eu").addQueue(quorum("orders"))
        h.active.connect(h.uat)
        val source = h.active.source!!

        val page = source.topology.queues("/", 1)

        assertIs<CatalogResult.Success<QueuePage>>(page)
        assertEquals(listOf("orders"), page.value.rows.map { it.name })
        assertEquals(listOf("queues"), h.gateway.topologyOf("uat-eu").calls.toList())
    }

    @Test
    fun `the topology is the one of the same held connection, not a shared one`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.topologyOf("uat-eu").addQueue(quorum("uat-queue"))
        h.gateway.topologyOf("prod-eu").addQueue(quorum("prod-queue"))
        h.active.connect(h.prod)
        val rows = (h.active.source!!.topology.queues("/", 1) as CatalogResult.Success<QueuePage>).value.rows
        assertEquals(listOf("prod-queue"), rows.map { it.name })
        assertEquals(emptyList(), h.gateway.topologyOf("uat-eu").calls.toList())
    }

    @Test
    fun `a topology of a closed or superseded connection answers Unreachable on every call and touches nothing`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!
        val fake = h.gateway.topologyOf("uat-eu")

        h.active.disconnect()

        assertEquals(CatalogResult.Unreachable, old.topology.vhosts())
        assertEquals(CatalogResult.Unreachable, old.topology.queues("/", 1))
        assertEquals(CatalogResult.Unreachable, old.topology.exchanges("/"))
        assertEquals(CatalogResult.Unreachable, old.topology.bindings("/"))
        assertEquals(CatalogResult.Unreachable, old.topology.queueDetail("/", "q"))
        assertEquals(emptyList(), fake.calls.toList(), "the closed connection must not be touched")
    }

    @Test
    fun `after a switch or a reconnect the old topology is dead and the new one works`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val old = h.active.source!!
        h.active.connect(h.uat)
        val fresh = h.active.source!!

        assertEquals(CatalogResult.Unreachable, old.topology.queues("/", 1))
        assertIs<CatalogResult.Success<*>>(fresh.topology.queues("/", 1))
        assertEquals(listOf("queues"), h.gateway.topologyOf("uat-eu").calls.toList(), "only the fresh call reached the fake")
    }

    @Test
    fun `a connection lost to the broker kills the topology of that source too`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        val source = h.active.source!!
        h.active.connectionLost(source)
        assertEquals(CatalogResult.Unreachable, source.topology.queues("/", 1))
        assertNull(h.active.source)
    }

    @Test
    fun `the vhost of the source is the profile's, the topology takes the vhost per call`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.topologyOf("uat-eu").addQueue(quorum("inv", vhost = "billing"))
        h.active.connect(h.uat.copy(virtualHost = "/shop"))
        val source = h.active.source
        assertNotNull(source)
        assertEquals("/shop", source.vhost)
        assertEquals(listOf("inv"), (source.topology.queues("billing", 1) as CatalogResult.Success<QueuePage>).value.rows.map { it.name })
    }

    @Test
    fun `a user without the management tag reads NotAuthorised through the source`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.noManagementTag("uat-eu")
        h.active.connect(h.uat)
        assertEquals(CatalogResult.NotAuthorised, h.active.source!!.topology.vhosts())
    }
}
