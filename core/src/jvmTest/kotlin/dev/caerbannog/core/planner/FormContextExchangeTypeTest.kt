package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-11 step 2: `FormContext.exchangeType(name): String?` is the one place that reads an exchange's type; `asksRoutingKey` uses it (brief, Design notes). */
class FormContextExchangeTypeTest {
    private fun exchange(name: String, type: String) = MigrationAction.DeclareExchange(name = name, type = type)

    @Test
    fun `unknown is null`() {
        assertNull(ctx().exchangeType("documents"))
        assertNull(ctx(plan = planOf(exchange("other", "headers"))).exchangeType("documents"))
        assertNull(ctx(known = KnownTopology(exchanges = mapOf("other" to "headers"))).exchangeType("documents"))
    }

    @Test
    fun `the plan gives the type, then the broker`() {
        assertEquals("headers", ctx(plan = planOf(exchange("documents", "headers"))).exchangeType("documents"))
        assertEquals("topic", ctx(known = KnownTopology(exchanges = mapOf("documents" to "topic"))).exchangeType("documents"))
    }

    @Test
    fun `the plan wins over the broker and the last declaration of a name wins`() {
        val known = KnownTopology(exchanges = mapOf("documents" to "topic"))
        assertEquals("fanout", ctx(plan = planOf(exchange("documents", "fanout")), known = known).exchangeType("documents"))
        assertEquals("direct", ctx(plan = planOf(exchange("documents", "headers"), exchange("documents", "direct"))).exchangeType("documents"))
    }

    @Test
    fun `only exchange declarations count`() {
        val plan = planOf(MigrationAction.DeclareQueue(name = "documents"), MigrationAction.DeclareStream(name = "documents"))
        assertNull(ctx(plan = plan).exchangeType("documents"))
    }

    @Test
    fun `the routing key is asked unless the type is known to be fanout or headers`() {
        for ((type, asks) in mapOf("fanout" to false, "headers" to false, "direct" to true, "topic" to true)) {
            assertEquals(asks, ctx(plan = planOf(exchange("e", type))).asksRoutingKey("e"), "plan $type")
            assertEquals(asks, ctx(known = KnownTopology(exchanges = mapOf("e" to type))).asksRoutingKey("e"), "broker $type")
        }
        assertTrue(ctx().asksRoutingKey("e"), "unknown type: the field is shown")
        assertFalse(ctx(plan = planOf(exchange("e", "headers"), exchange("x", "direct"))).asksRoutingKey("e"))
    }
}
