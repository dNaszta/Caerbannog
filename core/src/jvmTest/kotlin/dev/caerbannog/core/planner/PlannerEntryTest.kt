package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** M9-02: what the Migrations screen offers as the entry to the planner, from what the draft store answered (scenarios "A draft survives a restart", "There is one draft at a time"). */
class PlannerEntryTest {
    private val queue = MigrationAction.DeclareQueue("orders")

    @Test
    fun `no draft offers New plan`() {
        val entry = PlannerEntry.of(DraftLoad.None)
        assertEquals(PlannerEntry.NewPlan, entry)
        assertEquals("New plan", entry.label)
    }

    @Test
    fun `a draft with steps offers Continue plan with the step count`() {
        val entry = PlannerEntry.of(DraftLoad.Loaded(TopologyPlan.of("d", listOf(queue, queue.copy(name = "b")), defaultVhost = "/")))
        assertEquals(PlannerEntry.ContinuePlan(stepCount = 2), entry)
        assertEquals("Continue plan", entry.label)
    }

    @Test
    fun `a description-only draft is still a draft to continue`() {
        assertEquals(PlannerEntry.ContinuePlan(stepCount = 0), PlannerEntry.of(DraftLoad.Loaded(TopologyPlan(description = "later"))))
    }

    @Test
    fun `an unreadable draft is reported with its path and offers neither New nor Continue`() {
        val entry = PlannerEntry.of(DraftLoad.Unreadable("/home/u/.caerbannog/planner-draft.json", "The file is not a valid plan"))
        assertEquals(PlannerEntry.DraftUnreadable("/home/u/.caerbannog/planner-draft.json"), entry)
    }
}
