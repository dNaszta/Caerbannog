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
        assertEquals(PlannerEntry.ContinuePlan(stepCount = 2, description = "d"), entry)
        assertEquals("Continue plan", entry.label)
    }

    @Test
    fun `a description-only draft is still a draft to continue`() {
        assertEquals(PlannerEntry.ContinuePlan(stepCount = 0, description = "later"), PlannerEntry.of(DraftLoad.Loaded(TopologyPlan(description = "later"))))
    }

    @Test
    fun `an unreadable draft is reported with its path and offers neither New nor Continue`() {
        val entry = PlannerEntry.of(DraftLoad.Unreadable("/home/u/.caerbannog/planner-draft.json", "The file is not a valid plan"))
        assertEquals(PlannerEntry.DraftUnreadable("/home/u/.caerbannog/planner-draft.json"), entry)
    }

    // ---- M9-03 (a): the draft card shows title, description and step count ----

    @Test
    fun `Continue plan carries the description of the loaded plan, empty when there is none`() {
        val entry = PlannerEntry.of(DraftLoad.Loaded(TopologyPlan.of("Orders with dead-lettering", listOf(queue), defaultVhost = "/")))
        assertEquals("Orders with dead-lettering", (entry as PlannerEntry.ContinuePlan).description)
        assertEquals("", (PlannerEntry.of(DraftLoad.Loaded(TopologyPlan.of("", listOf(queue), defaultVhost = "/"))) as PlannerEntry.ContinuePlan).description)
    }

    @Test
    fun `the draft card says how many steps and that it is not a migration yet, singular for one`() {
        assertEquals(PlannerTexts.draftCardSteps(10), PlannerEntry.ContinuePlan(10, "x").stepsText)
        assertEquals("10 steps \u00b7 not a migration yet", PlannerEntry.ContinuePlan(10, "x").stepsText)
        assertEquals("1 step \u00b7 not a migration yet", PlannerEntry.ContinuePlan(1, "x").stepsText)
        assertEquals("0 steps \u00b7 not a migration yet", PlannerEntry.ContinuePlan(0, "x").stepsText)
    }

    @Test
    fun `the card texts are constants of PlannerTexts, so the wording is one edit`() {
        assertEquals("Draft plan", PlannerTexts.DRAFT_CARD_TITLE)
        assertEquals("Not saved", PlannerTexts.DRAFT_CARD_PILL)
        assertEquals(PlannerTexts.DRAFT_UNREADABLE, PlannerEntry.DraftUnreadable("/x").cardText)
    }
}
