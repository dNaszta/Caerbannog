package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07 step 2: `StepForms.formOf(DeclareStream)`. A stream WITHOUT settings is editable; a response stream recognised by `PlanPairs` is edited as an
 * ordinary stream.
 * M9-14c step 2b (brief table: the comment "A stream has no arguments here" in `StepForms.formOf` is wrong after the task): a `DeclareStream` with
 * settings (`arguments` not empty) has NO form, so an opened file's stream step is shown read-only with its detail and written back unchanged;
 * the stream form writes a name only and would DROP the settings on edit (A2: no loss).
 */
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

    // ---- M9-14c: a stream with settings is read-only ----

    private val withSettings = MigrationAction.DeclareStream(name = "audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D")))

    @Test
    fun `a stream with settings has no form and its step is not editable`() {
        assertNull(StepForms.formOf(withSettings))
        assertTrue(!PlanStep(1, withSettings).isEditable)
    }

    @Test
    fun `each of the four settings alone makes the stream read-only`() {
        for ((key, value) in listOf(
            "x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Whole(5),
            "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(5), "x-queue-leader-locator" to ArgumentValue.Text("balanced"),
        )) {
            assertNull(StepForms.formOf(MigrationAction.DeclareStream(name = "audit", arguments = mapOf(key to value))), key)
        }
    }

    @Test
    fun `a stream without settings is unchanged, an empty arguments map is no settings`() {
        assertEquals(StreamForm(name = "audit"), StepForms.formOf(MigrationAction.DeclareStream(name = "audit", arguments = emptyMap())))
    }

    @Test
    fun `the plan keeps a stream with settings exactly as written, in another vhost too`() {
        val other = withSettings.copy(vhost = "other")
        val plan = planOf(withSettings, other)
        assertEquals(listOf<MigrationAction>(withSettings, other), plan.steps.map { it.action })
        assertTrue(plan.steps.none { it.isEditable })
    }

    @Test
    fun `a stream with settings is still part of a pair by its name`() {
        val plan = planOf(withSettings.copy(name = "telemetry"), MigrationAction.DeclareStream(name = "telemetry-dln"))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan))
        val both = planOf(withSettings.copy(name = "telemetry"), withSettings.copy(name = "telemetry-dln"))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(both))
    }
}
