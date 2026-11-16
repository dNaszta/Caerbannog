package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-06 step 4 (round 1), exploratory checks of the check in the view model: what the scenarios and the step 2 tests do not say.
 * Each test is a way to break the change that was tried; they all pass on the code of 48854cc.
 */
class PlannerViewModelCheckExploratoryTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private fun rig(port: FakeDeclareCheck, prepare: PlannerRig.() -> Unit = {}) = PlannerRig(declareCheck = port).also { rigs += it; it.prepare() }

    private fun FakeDeclareCheck.scriptQueues(r: PlannerRig, finding: BrokerFinding = BrokerFinding.Absent) = apply {
        r.state.plan.steps.forEach { (it.action as? MigrationAction.DeclareQueue)?.let { q -> answerNamed(q.name, finding) } }
    }

    @Test
    fun `removing the last step during a run and adding another that gets its id shows no result on the new step`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptQueues(r)
        val gate = port.holdOn("a")
        r.vm.check()
        val (_, b) = r.ids()
        r.vm.removeStep(b)
        r.queue("c")
        assertEquals(b, r.ids().last(), "the freed id was not given again, so this test proves nothing")
        gate.release()
        val done = assertIs<CheckState.Done>(r.state.check)
        assertTrue(done.stale)
        assertNull(r.state.stepCheck(b), "the new step inherits the result of the step that was removed during the run")
    }

    @Test
    fun `saving the plan while a check runs drops the check and the late answer`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a") }
        port.scriptQueues(r)
        val gate = port.holdOn("a")
        r.vm.check()
        r.vm.saveAsMigration()
        assertEquals("0005", r.state.saved?.version, "the save did not happen: ${r.state.saveGate}")
        gate.release()
        assertEquals(CheckState.None, r.state.check, "the answer for a plan that was saved is shown")
        assertTrue(r.state.isEmpty)
    }

    @Test
    fun `a plan of 400 steps is checked, step by step, and ends with 400 results`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { (1..400).forEach { queue("queue-$it") } }
        port.scriptQueues(r)
        val gate = port.holdOn("queue-399")
        r.vm.check()
        assertEquals(CheckState.Running(399, 400), r.state.check)
        gate.release()
        val done = assertIs<CheckState.Done>(r.state.check)
        assertEquals(400, done.results.size)
        assertEquals(r.ids(), done.results.map { it.stepId })
        assertEquals(400, port.calls.size, "one read per step")
    }

    @Test
    fun `rapid checks - a thousand calls while it runs start one run, and calls after the result start the next one only`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptQueues(r)
        val gate = port.holdOn("b")
        repeat(1000) { r.vm.check() }
        assertEquals(2, port.calls.size, "more than one run started: ${port.calls.map { it.description }}")
        gate.release()
        assertIs<CheckState.Done>(r.state.check)
        r.vm.check()
        assertEquals(4, port.calls.size, "exactly one more run")
    }

    @Test
    fun `an edit of a step during a run is not lost - the result arrives stale for the edited step too`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptQueues(r)
        val gate = port.holdOn("b")
        r.vm.check()
        val first = r.ids().first()
        r.vm.editStep(first); r.vm.updateForm(QueueForm(name = "a2")); r.vm.next(); r.vm.addToPlan()
        gate.release()
        val done = assertIs<CheckState.Done>(r.state.check)
        assertTrue(done.stale, "the result of a plan that changed during the run must not look fresh")
        assertEquals(r.ids(), done.results.map { it.stepId }, "the edited step keeps its id and its (old) result, marked stale")
    }

    @Test
    fun `a description typed during a run does not stale the result`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a") }
        port.scriptQueues(r)
        val gate = port.holdOn("a")
        r.vm.check()
        r.vm.setDescription("while it runs")
        gate.release()
        assertFalse(assertIs<CheckState.Done>(r.state.check).stale)
    }

    @Test
    fun `keeping the plan after a Dismiss question leaves the running check running`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a") }
        port.scriptQueues(r)
        val gate = port.holdOn("a")
        r.vm.check()
        r.vm.dismiss()
        r.vm.cancelQuestion()
        gate.release()
        assertIs<CheckState.Done>(r.state.check)
    }

    @Test
    fun `a hostile name never reaches the missing sentence or any check text beyond the echo rule`() {
        val hostile = listOf("<b>x</b>", "a".repeat(200), "‮evil", "../etc/passwd", "a b", "x\ny")
        hostile.forEach { name ->
            val port = FakeDeclareCheck().answerNamed(name, BrokerFinding.Absent)
            val r = rig(port)
            r.store.putDraft("", listOf(MigrationAction.DeclareBinding(name, "q", "k")))
            r.restart()
            r.vm.check()
            val done = assertIs<CheckState.Done>(r.state.check, "name $name")
            val texts = done.results.flatMap { it.reasons } + PlannerTexts.checkSummary("uat-eu", "10:00", done.results) +
                done.results.map { PlannerTexts.resultLabel(it.finding) }
            texts.forEach { t -> assertFalse(t.contains(name), "the name \"${name.take(20)}\" is echoed in \"${t.take(80)}\"") }
        }
    }

    @Test
    fun `a secret-looking name is not in the summary, the progress or the label`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a") }
        port.scriptQueues(r)
        r.vm.check()
        val done = assertIs<CheckState.Done>(r.state.check)
        val all = listOf(
            PlannerTexts.checkSummary("uat-eu", "10:00", done.results), PlannerTexts.checkProgress(1, 1, "uat-eu"),
            PlannerTexts.checkLabel("uat-eu"), PlannerTexts.CHECK_CAVEAT, PlannerTexts.CHECK_STALE,
        )
        all.forEach { assertFalse(Regex("(?i)password|amqp://|secret|token").containsMatchIn(it), it) }
    }
}
