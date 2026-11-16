package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-07 step 2: `StepForms.formOf(DeclareStream)`. A stream has no arguments, so every `DeclareStream` is editable; a response stream recognised by `PlanPairs` is edited as an ordinary stream. */
class StepFormsTest {
    @Test
    fun `a declared stream is a stream form with no tick`() {
        assertEquals(StreamForm(name = "telemetry"), StepForms.formOf(MigrationAction.DeclareStream(name = "telemetry")))
        assertEquals(false, (StepForms.formOf(MigrationAction.DeclareStream(name = "x")) as StreamForm).withResponse)
    }

    @Test
    fun `every declared stream is editable, whatever its name`() {
        for (name in listOf("a", "orders-dln", "Orders_EU", "x y", "dln", "-dln")) {
            val step = PlanStep(1, MigrationAction.DeclareStream(name = name))
            assertTrue(step.isEditable, "stream \"$name\"")
            assertEquals(StreamForm(name = name), StepForms.formOf(step.action))
        }
    }

    @Test
    fun `a response stream of a pair is edited as an ordinary stream and the tick is only for adding`() {
        val plan = planOf(MigrationAction.DeclareStream(name = "telemetry"), MigrationAction.DeclareStream(name = "telemetry-dln"))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan))
        val form = StepForms.formOf(plan.steps[1].action)
        assertEquals(StreamForm(name = "telemetry-dln"), form)
        assertEquals(emptyList(), form!!.actions(ctx(plan = TopologyPlan())).drop(1), "one action, no second stream")
    }

    @Test
    fun `a form holds the stream of another virtual host and the plan keeps that action as written`() {
        val form = StepForms.formOf(MigrationAction.DeclareStream(name = "t", vhost = "other"))
        assertEquals(StreamForm(name = "t"), form)
    }

    @Test
    fun `a delete of a stream has no form`() {
        assertNull(StepForms.formOf(MigrationAction.DeleteStream(name = "telemetry")))
        assertTrue(!PlanStep(1, MigrationAction.DeleteStream(name = "telemetry")).isEditable)
    }
}
