package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-11 step 2: `PlanValidation` checks no binding for "declared twice" today (queues, exchanges and streams only). This pins that two bindings with the
 * same exchange, destination and key but other arguments give no problem and no note, so headers bindings that differ only in their headers stay legal.
 * It does NOT ask for a new "declared twice" note on identical bindings (out of scope of M9-11; a separate idea if wanted).
 */
class PlanValidationBindingIdentityTest {
    private fun headersBinding(vararg args: Pair<String, ArgumentValue>) =
        MigrationAction.DeclareBinding(exchange = "documents", queue = "reports", routingKey = "", arguments = mapOf("x-match" to ArgumentValue.Text("all")) + args)

    private val base = arrayOf(MigrationAction.DeclareExchange(name = "documents", type = "headers"), MigrationAction.DeclareQueue(name = "reports"))

    @Test
    fun `two bindings that differ only in their headers are no problem`() {
        val plan = planOf(*base, headersBinding("format" to ArgumentValue.Text("pdf")), headersBinding("format" to ArgumentValue.Text("csv")))
        assertEquals(emptyList(), PlanValidation.problems(plan))
    }

    @Test
    fun `a number and the same text are two bindings and no problem`() {
        val plan = planOf(*base, headersBinding("pages" to ArgumentValue.Whole(10)), headersBinding("pages" to ArgumentValue.Text("10")))
        assertEquals(emptyList(), PlanValidation.problems(plan))
    }

    @Test
    fun `two bindings that differ only in the match rule are no problem`() {
        val any = headersBinding("format" to ArgumentValue.Text("pdf")).let { it.copy(arguments = it.arguments + ("x-match" to ArgumentValue.Text("any"))) }
        val plan = planOf(*base, headersBinding("format" to ArgumentValue.Text("pdf")), any)
        assertEquals(emptyList(), PlanValidation.problems(plan))
    }

    @Test
    fun `a headers binding with arguments next to a plain binding of the same key is no problem`() {
        val plain = MigrationAction.DeclareBinding(exchange = "documents", queue = "reports", routingKey = "")
        assertEquals(emptyList(), PlanValidation.problems(planOf(*base, plain, headersBinding("format" to ArgumentValue.Text("pdf")))))
    }
}
