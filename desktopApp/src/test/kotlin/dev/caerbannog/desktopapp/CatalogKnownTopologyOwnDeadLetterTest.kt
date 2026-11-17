package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.QueueDeclaration
import dev.caerbannog.core.topology.QueueRow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M9-09 step 2, written first: `PlannerWiring.knownTopology` (`CatalogKnownTopologyReader`) fills `KnownQueue.ownDeadLetterExchange` from
 * `TopologyCatalog.queueDeclarations(vhost)` (the call the export already uses; a GET): the value of `x-dead-letter-exchange` when it is `ArgumentValue.Text`; null when the
 * argument is absent, when `typedArgumentsComplete` is false (the argument may be the lost one: no claim) or when that read failed (the rest of `KnownTopology` is still
 * returned: one failed extra read must not blank the names). Only the argument is read (`effective_policy_definition` never). Model: `CatalogKnownTopologyQueueDetailsTest`.
 * RED until the field and the wiring exist.
 */
class CatalogKnownTopologyOwnDeadLetterTest {
    private val topology = FakeTopologyCatalog()
    private val streams = FakeStreamCatalog()

    private fun row(name: String, type: String = "quorum", policy: String? = null) = QueueRow(
        name = name, vhost = "/", type = type, readyMessages = 0, unackedMessages = 0, consumers = 0, leader = "rabbit@n", members = listOf("rabbit@n"),
        online = listOf("rabbit@n"), policy = policy, state = "running",
    )

    private fun queue(
        name: String, arguments: Map<String, ArgumentValue> = emptyMap(), complete: Boolean = true, type: String = "quorum", policy: String? = null,
    ) = topology.addQueue(row(name, type, policy), QueueDeclaration(name, "/", type, arguments = arguments, typedArgumentsComplete = complete))

    private fun read() = assertNotNull(runBlocking { CatalogKnownTopologyReader(topology, streams).read("/") })

    private val dlx = "x-dead-letter-exchange"

    @Test
    fun `the argument as text is the queue's own dead-letter exchange, beside its type and policy`() {
        queue("orders", mapOf(dlx to ArgumentValue.Text("legacy-dlx")), policy = "limits")
        assertEquals(KnownQueue("quorum", "limits", ownDeadLetterExchange = "legacy-dlx"), read().queueDetails["orders"])
    }

    @Test
    fun `no argument is none`() {
        queue("orders", mapOf("x-max-length" to ArgumentValue.Whole(5)))
        assertNull(read().queueDetails.getValue("orders").ownDeadLetterExchange)
    }

    @Test
    fun `an argument that is not text is none`() {
        queue("orders", mapOf(dlx to ArgumentValue.Whole(1)))
        assertNull(read().queueDetails.getValue("orders").ownDeadLetterExchange)
    }

    @Test
    fun `when the typed arguments are not complete nothing is claimed, even with the argument present`() {
        queue("orders", mapOf(dlx to ArgumentValue.Text("legacy-dlx")), complete = false)
        queue("jobs", emptyMap(), complete = false)
        val known = read()
        assertNull(known.queueDetails.getValue("orders").ownDeadLetterExchange)
        assertNull(known.queueDetails.getValue("jobs").ownDeadLetterExchange)
    }

    @Test
    fun `each queue has its own value`() {
        queue("a", mapOf(dlx to ArgumentValue.Text("a-dlx")))
        queue("b")
        queue("c", mapOf(dlx to ArgumentValue.Text("c-dlx")), type = "classic")
        val d = read().queueDetails
        assertEquals("a-dlx", d.getValue("a").ownDeadLetterExchange)
        assertNull(d.getValue("b").ownDeadLetterExchange)
        assertEquals(KnownQueue("classic", null, ownDeadLetterExchange = "c-dlx"), d["c"])
    }

    @Test
    fun `a failed read of the declarations keeps every name, type and policy and claims no own exchange`() {
        queue("orders", mapOf(dlx to ArgumentValue.Text("legacy-dlx")), policy = "limits")
        streams.addStream(FakeStreamCatalog.stream("telemetry"))
        topology.failCall("queueDeclarations", CatalogResult.Unreachable)
        val known = read()
        assertEquals(1, topology.calls.count { it == "queueDeclarations" }, "the declarations were asked for, and refused: ${topology.calls}")
        assertEquals(setOf("orders"), known.queues)
        assertEquals(setOf("telemetry"), known.streams)
        assertEquals(KnownQueue("quorum", "limits"), known.queueDetails["orders"], "type and policy stay, the own exchange is not claimed")
        assertEquals(KnownQueue("stream", null), known.queueDetails["telemetry"])
    }

    @Test
    fun `a stream claims no own exchange`() {
        streams.addStream(FakeStreamCatalog.stream("telemetry"))
        assertEquals(KnownQueue("stream", null), read().queueDetails["telemetry"])
    }

    @Test
    fun `the declarations are read once per read, with no other call than the lists and that one`() {
        queue("orders", mapOf(dlx to ArgumentValue.Text("legacy-dlx")))
        read()
        assertEquals(1, topology.calls.count { it == "queueDeclarations" }, "calls: ${topology.calls}")
        assertEquals(emptyList(), topology.calls.filter { it in setOf("queueDetail", "policies", "operatorPolicies", "bindings") }, "calls: ${topology.calls}")
    }
}
