package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-01: one test per rule of `PlanValidation.problems`. A step is named by its id; "step N" in a message is its 1-based place. */
class PlanValidationTest {
    private fun queue(name: String, type: String = "quorum") = MigrationAction.DeclareQueue(name = name, type = type)
    private fun exchange(name: String, type: String = "direct") = MigrationAction.DeclareExchange(name = name, type = type)
    private fun bind(ex: String, q: String, key: String = "k", kind: BindingDestinationType = BindingDestinationType.QUEUE) =
        MigrationAction.DeclareBinding(exchange = ex, queue = q, routingKey = key, destinationType = kind)

    @Test
    fun `a clean plan has no problem`() {
        assertEquals(emptyList(), PlanValidation.problems(planOf(queue("orders"), exchange("events"), bind("events", "orders"))))
    }

    @Test
    fun `a queue declared twice with other settings blocks, and names the later step`() {
        val p = planOf(queue("orders"), queue("orders", "classic"))
        val problems = PlanValidation.problems(p)
        assertEquals(listOf("The plan already declares orders with other settings"), problems.map { it.message })
        assertTrue(problems.single().blocking)
        assertEquals(p.steps[1].id, problems.single().stepId)
    }

    @Test
    fun `an exchange declared twice with another type blocks`() {
        val problems = PlanValidation.problems(planOf(exchange("e"), exchange("e", "topic")))
        assertEquals(listOf("The plan already declares e with other settings"), problems.map { it.message })
        assertTrue(problems.single().blocking)
    }

    @Test
    fun `the same settings twice is a note that does not block`() {
        val problems = PlanValidation.problems(planOf(queue("orders"), queue("orders")))
        assertEquals(1, problems.size)
        assertEquals(false, problems.single().blocking)
    }

    @Test
    fun `a queue and a stream of one name block`() {
        val p = planOf(queue("orders"), MigrationAction.DeclareStream(name = "orders"))
        val problems = PlanValidation.problems(p)
        assertEquals(1, problems.size)
        assertTrue(problems.single().blocking)
        assertTrue("orders" in problems.single().message, problems.single().message)
        assertEquals(p.steps[1].id, problems.single().stepId)
    }

    @Test
    fun `a binding before the queue it needs blocks and names the later step`() {
        val p = planOf(bind("events", "invoices", "invoice.created"), queue("invoices"))
        val problems = PlanValidation.problems(p)
        assertEquals(listOf("The queue invoices is declared later, in step 2. Move this step below it."), problems.map { it.message })
        assertEquals(p.steps[0].id, problems.single().stepId)
        assertTrue(problems.single().blocking)
    }

    @Test
    fun `a binding before the exchange it needs blocks`() {
        val p = planOf(queue("q"), bind("events", "q"), exchange("events"))
        assertEquals(listOf("The exchange events is declared later, in step 3. Move this step below it."), PlanValidation.problems(p).map { it.message })
    }

    @Test
    fun `a binding to an exchange destination declared later blocks`() {
        val p = planOf(bind("a", "b", kind = BindingDestinationType.EXCHANGE), exchange("a"), exchange("b"))
        val messages = PlanValidation.problems(p).map { it.message }
        assertTrue("The exchange b is declared later, in step 3. Move this step below it." in messages, messages.toString())
    }

    @Test
    fun `a binding to something the plan does not declare is fine, the broker may have it`() {
        assertEquals(emptyList(), PlanValidation.problems(planOf(bind("events", "audit"))))
    }

    @Test
    fun `moving the binding below the declaration clears the problem`() {
        val p = planOf(bind("events", "invoices"), queue("invoices"))
        assertEquals(emptyList(), PlanValidation.problems(p.moveDown(p.steps[0].id)))
    }

    @Test
    fun `what the parser refuses is a blocking problem with the parser's own reason`() {
        val bad = MigrationAction.DeclareQueue(name = "..")
        val p = planOf(bad)
        val reason = (MigrationParser.parse(MigrationParser.exportToJson("draft", "", listOf(bad))) as MigrationValidationResult.Invalid).reason
        val problems = PlanValidation.problems(p)
        assertEquals(listOf(reason), problems.map { it.message }, "the parser's reason, unchanged")
        assertTrue(problems.single().blocking)
    }

    @Test
    fun `a plan that the parser accepts adds no round trip problem`() {
        val p = planOf(queue("orders"))
        val ok = MigrationParser.parse(MigrationParser.exportToJson("draft", "d", p.steps.map { it.action }))
        assertTrue(ok is MigrationValidationResult.Valid)
        assertEquals(emptyList(), PlanValidation.problems(p.withDescription("d")))
    }

    @Test
    fun `a name that the echo rule refuses never appears in a message`() {
        val hostile = "x\"<b>\n`y`"
        val p = planOf(
            queue(hostile), queue(hostile, "classic"),
            bind("e", hostile), queue("later-one"),
            MigrationAction.DeclareStream(name = hostile),
        )
        val messages = PlanValidation.problems(p).map { it.message }
        assertTrue(messages.isNotEmpty())
        for (m in messages) assertTrue(hostile !in m && m.none { it == '<' || it == '`' || it == '\n' }, "echoed a refused name: $m")
    }
}
