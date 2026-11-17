package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.topology.QueueRow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * M9-08 slice 2, written first: `CatalogKnownTopologyReader` fills `KnownTopology.queueDetails` from the `QueueRow` it already reads (`type`, `policy`), so the
 * check notes know which policy the broker applies to which queue. Same list calls as before, no new request. A broker STREAM has type `stream` and no policy (a
 * `StreamSummary` carries none): the stated limitation, no check note for a stream that already exists. `queues` and `streams` stay as they were.
 */
class CatalogKnownTopologyQueueDetailsTest {
    private val topology = FakeTopologyCatalog()
    private val streams = FakeStreamCatalog()

    private fun row(name: String, type: String, policy: String?) = QueueRow(
        name = name, vhost = "/", type = type, readyMessages = 0, unackedMessages = 0, consumers = 0, leader = "rabbit@n", members = listOf("rabbit@n"),
        online = listOf("rabbit@n"), policy = policy, state = "running",
    )

    private fun read() = assertNotNull(runBlocking { CatalogKnownTopologyReader(topology, streams).read("/") })

    @Test
    fun `a queue carries its type and the name of the policy that applies`() {
        topology.addQueue(row("orders", "quorum", "limits")).addQueue(row("invoices", "classic", null)).addQueue(row("audit", "quorum", null))
        val known = read()
        assertEquals(KnownQueue("quorum", "limits"), known.queueDetails["orders"])
        assertEquals(KnownQueue("classic", null), known.queueDetails["invoices"])
        assertEquals(KnownQueue("quorum", null), known.queueDetails["audit"])
        assertEquals(setOf("orders", "invoices", "audit"), known.queues, "the names are what they were")
    }

    @Test
    fun `a stream has the type stream and no policy name, and stays in streams`() {
        streams.addStream(FakeStreamCatalog.stream("telemetry"))
        val known = read()
        assertEquals(KnownQueue("stream", null), known.queueDetails["telemetry"])
        assertEquals(setOf("telemetry"), known.streams)
        assertEquals(emptySet(), known.queues)
    }

    @Test
    fun `every page of the queues is in the details`() {
        (1..600).forEach { topology.addQueue(row("queue-%03d".format(it), "quorum", if (it % 2 == 0) "p$it" else null)) }
        val known = read()
        assertEquals(600, known.queueDetails.size)
        assertEquals("p600", known.queueDetails["queue-600"]?.policy)
        assertEquals(null, known.queueDetails["queue-599"]?.policy)
    }
}
