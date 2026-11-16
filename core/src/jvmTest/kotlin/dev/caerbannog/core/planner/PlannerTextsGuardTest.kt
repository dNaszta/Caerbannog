package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-01: fixed texts are plain text: no markup, no placeholder left unfilled; a name goes into a message only as a plain word. */
class PlannerTextsGuardTest {
    private fun allTexts(): List<String> {
        val forms = listOf(
            QueueForm(name = ""), QueueForm(name = ".."), ExchangeForm(name = ""), ExchangeForm(name = "."),
            BindingForm(exchange = "", destination = ""),
        )
        val formTexts = forms.flatMap { it.problems(ctx()) }.map { it.message }
        val validation = PlanValidation.problems(
            planOf(
                MigrationAction.DeclareBinding(exchange = "e", queue = "q"), MigrationAction.DeclareQueue(name = "q"),
                MigrationAction.DeclareQueue(name = "q", type = "classic"), MigrationAction.DeclareQueue(name = "dup"),
                MigrationAction.DeclareStream(name = "dup"),
            ),
        ).map { it.message }
        val counts = (1..3).flatMap { listOf(PlannerTexts.summaryTitle(it), PlannerTexts.saveBlocked(it)) }
        val hints = NamingStyle.entries.mapNotNull { PlanNaming.hint("Orders_EU", it) }
        return formTexts + validation + counts + hints + PlannerTexts.WIZARD_KINDS
    }

    @Test
    fun `no text holds markup or an unfilled placeholder`() {
        for (t in allTexts()) {
            assertTrue(t.none { it in "<>`*" }, "markup character in \"$t\"")
            assertTrue("{" !in t && "}" !in t && "%s" !in t && "\$" !in t && "null" !in t, "unfilled placeholder in \"$t\"")
            assertTrue(t.isNotBlank())
        }
    }

    @Test
    fun `the counted texts say one or many`() {
        assertEquals("This adds 1 step", PlannerTexts.summaryTitle(1))
        assertEquals("This adds 4 steps", PlannerTexts.summaryTitle(4))
        assertEquals("Fix the 1 problem in the plan first", PlannerTexts.saveBlocked(1))
        assertEquals("Fix the 2 problems in the plan first", PlannerTexts.saveBlocked(2))
    }

    @Test
    fun `the wizard kinds are the seven of the spec, in the order of the mockup, and none deletes`() {
        assertEquals(
            listOf("Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue", "MQTT route"),
            PlannerTexts.WIZARD_KINDS,
        )
    }
}
