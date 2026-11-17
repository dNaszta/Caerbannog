package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExchangeFormTest {
    @Test
    fun `the default type is direct`() {
        assertEquals("direct", ExchangeForm(name = "events").type)
    }

    @Test
    fun `all four types build a declare exchange step`() {
        for (type in listOf("fanout", "direct", "topic", "headers")) {
            assertEquals(
                listOf(MigrationAction.DeclareExchange(name = "ex-1", type = type)),
                ExchangeForm(name = "ex-1", type = type).actions(ctx()),
                type,
            )
        }
    }

    @Test
    fun `problems as a table`() {
        assertEquals(listOf(FieldProblem("name", "Give the exchange a name")), ExchangeForm(name = "").problems(ctx()))
        assertEquals(listOf(FieldProblem("name", "a name must not be \".\" or \"..\"")), ExchangeForm(name = "..").problems(ctx()))
        assertEquals(emptyList(), ExchangeForm(name = "events").problems(ctx()))
    }

    @Test
    fun `the hint follows the style and never flags an exchange the broker has`() {
        assertEquals(
            listOf(FieldHint("name", "This name does not follow snake_case, for example orders_eu")),
            ExchangeForm(name = "orders-eu").hints(ctx(style = NamingStyle.SNAKE)),
        )
        val known = KnownTopology(exchanges = mapOf("Legacy_Events" to "topic"))
        assertEquals(emptyList(), ExchangeForm(name = "Legacy_Events").hints(ctx(known = known)))
    }

    @Test
    fun `formOf round trip and null for arguments, flags and deletes`() {
        for (type in listOf("fanout", "direct", "topic", "headers")) {
            val action = MigrationAction.DeclareExchange(name = "e", type = type)
            assertEquals(ExchangeForm(name = "e", type = type), StepForms.formOf(action))
        }
        assertNull(StepForms.formOf(MigrationAction.DeclareExchange(name = "e", durable = false)))
        assertNull(StepForms.formOf(MigrationAction.DeclareExchange(name = "e", autoDelete = true)))
        assertNull(StepForms.formOf(MigrationAction.DeclareExchange(name = "e", internal = true)))
        assertNull(StepForms.formOf(MigrationAction.DeclareExchange(name = "e", arguments = mapOf("alternate-exchange" to ArgumentValue.Text("x")))))
        assertNull(StepForms.formOf(MigrationAction.DeleteExchange(name = "e")))
    }

    // ---- M9-01b: the broker's name rules (S-H) ----

    private val reserved = "Names that start with \"amq.\" are reserved"

    @Test
    fun `an amq prefix is refused for every exchange type, in lower case only`() {
        for (type in listOf("fanout", "direct", "topic", "headers")) {
            assertEquals(listOf(FieldProblem("name", reserved)), ExchangeForm(name = "amq.orders", type = type).problems(ctx()), type)
        }
        assertEquals(listOf(FieldProblem("name", reserved)), ExchangeForm(name = "amq.").problems(ctx()))
        for (ok in listOf("amq", "AMQ.orders", "Amq.orders", "amqx", "amq-orders")) {
            assertEquals(emptyList(), ExchangeForm(name = ok).problems(ctx()), "name \"$ok\"")
        }
        assertEquals(emptyList(), ExchangeForm(name = "amq.topic", type = "topic").actions(ctx()), "the planner never declares a built-in exchange")
    }

    @Test
    fun `an exchange has no length rule, 255 and 256 bytes and more pass`() {
        for (name in listOf("a".repeat(255), "a".repeat(256), "é".repeat(128), "é".repeat(127) + "a", "a".repeat(300))) {
            assertEquals(emptyList(), ExchangeForm(name = name).problems(ctx()), "a name of ${name.length} characters")
            assertEquals(1, ExchangeForm(name = name).actions(ctx()).size)
        }
    }

    @Test
    fun `a space and a slash are not refused, and the first rule that applies speaks`() {
        for (ok in listOf("events eu", "events/eu")) assertEquals(emptyList(), ExchangeForm(name = ok).problems(ctx()), "name \"$ok\"")
        assertEquals(listOf(FieldProblem("name", "Give the exchange a name")), ExchangeForm(name = "").problems(ctx()))
        assertEquals(listOf(FieldProblem("name", reserved)), ExchangeForm(name = "amq." + "a".repeat(300)).problems(ctx()))
    }
}
