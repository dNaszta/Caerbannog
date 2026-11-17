package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M9-09 step 2, written first: `FormContext.queueType(name): String?` (next to `exchangeType`: the last `DeclareQueue` of the plan wins, then
 * `known.queueDetails[name]?.type`; a `DeclareStream` of the plan gives `"stream"`; null when neither knows it) and `FormContext.queueSuggestions()`
 * (built like `destinationSuggestions`: this plan's queues first, then `known.queues`, no name twice, STREAMS LEFT OUT). RED until they exist.
 */
class FormContextQueueTypeTest {
    private fun queue(name: String, type: String) = MigrationAction.DeclareQueue(name = name, type = type)
    private fun brokerQueue(type: String) = KnownTopology(queueDetails = mapOf("orders" to KnownQueue(type, null)))

    @Test
    fun `unknown is null`() {
        assertNull(ctx().queueType("orders"))
        assertNull(ctx(plan = planOf(queue("other", "classic"))).queueType("orders"))
        assertNull(ctx(known = brokerQueue("quorum")).queueType("other"))
    }

    @Test
    fun `the broker gives the type when the plan does not`() {
        assertEquals("quorum", ctx(known = brokerQueue("quorum")).queueType("orders"))
        assertEquals("classic", ctx(known = brokerQueue("classic")).queueType("orders"))
        assertEquals("stream", ctx(known = brokerQueue("stream")).queueType("orders"))
    }

    @Test
    fun `the plan wins over the broker`() {
        assertEquals("classic", ctx(plan = planOf(queue("orders", "classic")), known = brokerQueue("quorum")).queueType("orders"))
    }

    @Test
    fun `the last declaration of a name in the plan wins`() {
        assertEquals("quorum", ctx(plan = planOf(queue("orders", "classic"), queue("orders", "quorum"))).queueType("orders"))
        assertEquals("classic", ctx(plan = planOf(queue("orders", "quorum"), queue("orders", "classic"))).queueType("orders"))
    }

    @Test
    fun `a stream of the plan is a stream, whatever the broker says`() {
        assertEquals("stream", ctx(plan = planOf(MigrationAction.DeclareStream(name = "orders"))).queueType("orders"))
        assertEquals("stream", ctx(plan = planOf(MigrationAction.DeclareStream(name = "orders")), known = brokerQueue("quorum")).queueType("orders"))
    }

    @Test
    fun `only queue and stream declarations count`() {
        val plan = planOf(MigrationAction.DeclareExchange(name = "orders", type = "fanout"))
        assertNull(ctx(plan = plan).queueType("orders"))
    }

    // ---- suggestions ----

    @Test
    fun `queues of this plan first, then the broker's, no name twice, streams left out`() {
        val plan = planOf(queue("a", "quorum"), MigrationAction.DeclareStream(name = "s-plan"), queue("b", "classic"), queue("a", "quorum"))
        val known = KnownTopology(queues = setOf("b", "c", "d"), streams = setOf("s-broker"))
        assertEquals(
            listOf(
                Suggestion("a", SuggestionSource.PLAN), Suggestion("b", SuggestionSource.PLAN),
                Suggestion("c", SuggestionSource.BROKER), Suggestion("d", SuggestionSource.BROKER),
            ),
            ctx(plan = plan, known = known).queueSuggestions(),
        )
    }

    @Test
    fun `no suggestion without a plan queue or a broker read`() {
        assertEquals(emptyList(), ctx().queueSuggestions())
        assertEquals(emptyList(), ctx(plan = planOf(MigrationAction.DeclareStream(name = "s"))).queueSuggestions())
    }
}
