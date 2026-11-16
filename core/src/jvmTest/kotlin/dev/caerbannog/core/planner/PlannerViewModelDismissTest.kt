package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-02: Dismiss (O5, Q9) and "Start a new plan" for an unreadable draft. Dismiss asks once when the plan has a step ("Dismiss this plan? Its N
 * steps are discarded. Nothing was sent to the broker."); an empty plan is dismissed at once. Moving a draft file aside asks first, with the real
 * aside name, and never deletes (the pattern of `MigrationsViewModel.startWithEmptyHistory`).
 */
class PlannerViewModelDismissTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    @Test
    fun `Dismiss with steps asks the question with the count and changes nothing yet`() {
        rig.threeSteps()
        vm.dismiss()
        assertEquals(PlannerQuestion.Dismiss(steps = 3), rig.state.question)
        assertEquals("Dismiss this plan? Its 3 steps are discarded. Nothing was sent to the broker.", rig.state.question!!.text)
        assertEquals(3, rig.state.plan.steps.size)
        assertTrue(rig.store.hasDraft)
    }

    @Test
    fun `one step reads as one step`() {
        rig.queue("a")
        vm.dismiss()
        assertEquals("Dismiss this plan? Its 1 step is discarded. Nothing was sent to the broker.", rig.state.question!!.text)
    }

    @Test
    fun `Keep the plan closes the question and the plan is as it was`() {
        rig.threeSteps()
        vm.dismiss()
        vm.cancelQuestion()
        assertNull(rig.state.question)
        assertEquals(3, rig.state.plan.steps.size)
        assertTrue(rig.store.hasDraft)
        assertEquals(0, rig.store.clears)
    }

    @Test
    fun `confirming discards the plan and the draft and touches no migration file`() {
        rig.threeSteps()
        vm.setDescription("d")
        vm.dismiss()
        vm.confirm()
        assertNull(rig.state.question)
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals("", rig.state.description)
        assertTrue(!rig.store.hasDraft)
        assertEquals(emptyList(), rig.folder.attempts)
        assertEquals(setOf("0004"), rig.folder.files.keys)
        assertTrue(rig.state.dismissed, "the screen goes back to the Migrations screen")
    }

    @Test
    fun `an empty plan is dismissed at once and no question is ever asked`() {
        val asked = mutableListOf<PlannerQuestion>()
        // Unconfined: every state is seen as it is published, so a question that flashed by would be recorded
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect { s -> s.question?.let { asked += it } } }
        vm.dismiss()
        job.cancel()
        assertEquals(emptyList(), asked)
        assertTrue(rig.state.dismissed)
        assertTrue(!rig.store.hasDraft)
    }

    @Test
    fun `a plan that has only a description is dismissed at once and its draft goes`() {
        vm.setDescription("later")
        vm.dismiss()
        assertNull(rig.state.question)
        assertTrue(!rig.store.hasDraft)
        assertTrue(rig.state.dismissed)
    }

    @Test
    fun `confirm with no question does nothing`() {
        rig.queue("a")
        vm.confirm()
        assertEquals(1, rig.state.plan.steps.size)
        assertTrue(rig.store.hasDraft)
    }

    @Test
    fun `a new plan after Dismiss is empty and a restart finds no draft`() {
        rig.queue("a")
        vm.dismiss(); vm.confirm()
        rig.restart()
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals(PlannerEntry.NewPlan, PlannerEntry.of(runBlocking { rig.store.load() }))
    }

    @Test
    fun `any change closes a waiting question, so Dismiss never discards steps that were not counted`() {
        rig.queue("a")
        vm.dismiss()
        rig.queue("b")
        assertNull(rig.state.question, "the count in the question is stale after a change")
    }

    // ---- unreadable draft: Start a new plan ----

    private fun unreadable() {
        rig.store.putUnreadable("garbage")
        rig.restart()
    }

    @Test
    fun `Start a new plan asks with the real aside name and moves nothing yet`() {
        unreadable()
        vm.startNewPlan()
        val question = assertIs<PlannerQuestion.StartNewPlan>(rig.state.question)
        assertEquals("planner-draft.json.unreadable-20261116-100000", question.asideFileName)
        assertEquals(
            "Move planner-draft.json to planner-draft.json.unreadable-20261116-100000 and start a new plan? The old file is kept.",
            question.text,
        )
        assertEquals(0, rig.store.moveAsides)
        assertEquals("garbage", rig.store.rawText)
    }

    @Test
    fun `confirming moves the file aside with its bytes and the plan is empty and usable`() {
        unreadable()
        vm.startNewPlan()
        vm.confirm()
        assertEquals("garbage", rig.store.asideFiles["planner-draft.json.unreadable-20261116-100000"])
        assertTrue(!rig.store.hasDraft)
        assertNull(rig.state.draftUnreadable)
        assertNull(rig.state.question)
        assertEquals(emptyList(), rig.state.plan.steps)
        rig.queue("orders")
        assertEquals(listOf("Declare quorum queue orders"), rig.titles(), "the wizard works again")
    }

    @Test
    fun `cancelling leaves the file and the report as they were`() {
        unreadable()
        vm.startNewPlan()
        vm.cancelQuestion()
        assertEquals(0, rig.store.moveAsides)
        assertEquals("garbage", rig.store.rawText)
        assertEquals(rig.store.path, rig.state.draftUnreadable?.path)
    }

    @Test
    fun `a move that fails says so and the file and the report stay`() {
        unreadable()
        rig.store.refuseMoveAside = true
        vm.startNewPlan()
        vm.confirm()
        assertEquals("The draft file could not be moved aside; it was not changed", rig.state.errorMessage)
        assertEquals("garbage", rig.store.rawText)
        assertEquals(rig.store.path, rig.state.draftUnreadable?.path)
        assertNull(rig.state.question)
    }

    @Test
    fun `Start a new plan without an unreadable draft asks nothing`() {
        rig.queue("a")
        vm.startNewPlan()
        assertNull(rig.state.question)
    }

    @Test
    fun `Dismiss is not the way out of an unreadable draft and never deletes the file`() {
        unreadable()
        vm.dismiss()
        assertEquals("garbage", rig.store.rawText)
        assertEquals(0, rig.store.clears)
    }

    @Test
    fun `the aside questions never touch a migration action`() {
        unreadable()
        vm.startNewPlan(); vm.confirm()
        assertTrue(rig.folder.attempts.isEmpty() && rig.folder.written.isEmpty())
        assertEquals(emptyList<MigrationAction>(), rig.state.plan.steps.map { it.action })
    }
}
