package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.connections.fakes.CallGate
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.PlannerTexts
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-06 steps for the `@m9-06` scenarios of `20_topology_planner_check.feature`: the check on the planner screen. The REAL view model and the REAL
 * planner screen (Compose UI test API, [PlannerScreens]) over the scripted [FakeDeclareCheck] of the driver and a virtual clock. The rules of the check
 * are `core` (PlanCheck, tested with the real management client in `@m9-05`); what is decided here is only what the screen says and when. A text is
 * checked on the screen next to the state, so the screen cannot drift from it.
 *
 * A connection switch is what the window does: a new view model for the new connection (`PlannerDriver.restart`). The view model that is left behind
 * is not cancelled by the driver (the window cancels its job), so a late answer for it does arrive, and the scenario shows it does not reach the screen.
 */
class PlannerCheckScreenSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    private var gate: CallGate? = null

    /** A plan can only be saved with a folder; the default state of every @m9-06 scenario has one (as `@m9-05` does). */
    @Before(value = "@m9-06", order = 20000)
    fun defaultFolder() { if (PlannerWorld.driver.migrationsFolder.isEmpty()) PlannerWorld.driver.chooseMigrationsFolder("/work/migrations", "0004") }

    private fun hhmm(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    private fun portOf(profile: String): FakeDeclareCheck = d.declareChecks.getOrPut(profile) { FakeDeclareCheck() }

    private fun nameOf(action: MigrationAction): String? = when (action) {
        is MigrationAction.DeclareQueue -> action.name
        is MigrationAction.DeclareExchange -> action.name
        else -> null
    }

    /** Scripts every step of the plan on screen: [finding] for a queue, [exchangeFinding] for an exchange, "not there" for a binding (its ends are in the plan). */
    private fun script(port: FakeDeclareCheck, finding: BrokerFinding = BrokerFinding.Absent, exchangeFinding: BrokerFinding = BrokerFinding.Same) {
        d.plan.steps.forEach { step ->
            when (val name = nameOf(step.action)) {
                null -> port.answer(step.action, BrokerFinding.Absent)
                else -> port.answerNamed(name, if (step.action is MigrationAction.DeclareExchange) exchangeFinding else finding)
            }
        }
    }

    /** The plan of the check scenarios: a queue, an exchange and a binding of the two, with the port of the current connection already in place. */
    private fun threeStepPlan() {
        portOf(d.profile)
        d.newPlan() // the view model is built with the port
        d.addWithWizard(d.queueForm("orders", "quorum"))
        d.addWithWizard(d.exchangeForm("events", "direct"))
        d.addWithWizard(d.bindingForm("events", "orders", "order.created"))
        script(portOf(d.profile))
    }

    // ---- The summary counts the results ----

    @Given("a plan whose check found {int} steps that will be created, {int} that is already there and {int} that exists with different settings")
    fun planWithFindings(created: Int, there: Int, different: Int) {
        val port = portOf(d.profile)
        d.newPlan()
        (1..created).forEach { d.addWithWizard(d.queueForm("created-$it", "quorum")); port.answerNamed("created-$it", BrokerFinding.Absent) }
        (1..there).forEach { d.addWithWizard(d.queueForm("there-$it", "quorum")); port.answerNamed("there-$it", BrokerFinding.Same) }
        (1..different).forEach { d.addWithWizard(d.queueForm("different-$it", "quorum")); port.answerNamed("different-$it", BrokerFinding.Different) }
        assertEquals(created + there + different, d.plan.steps.size)
        d.vm.check()
        assertIs<CheckState.Done>(d.state.check, "the check did not finish: ${d.state.check}")
    }

    @Then("the planner says {string} with the time and {string}")
    fun summaryIs(head: String, counts: String) {
        val done = assertIs<CheckState.Done>(d.state.check)
        assertEquals(d.nowMillis, done.atMillis, "the time is the clock's")
        val text = screen.textOf("planner:checkSummary").orEmpty()
        assertTrue(text.contains("$head at ${hhmm(d.nowMillis)}: $counts"), "the summary says: \"$text\"")
        assertTrue(text.contains(PlannerTexts.CHECK_CAVEAT), "the line that the results describe the broker today is missing: \"$text\"")
    }

    // ---- A check result goes stale when the plan changes ----

    @Given("a checked plan with {int} steps")
    fun checkedPlan(count: Int) {
        assertEquals(3, count, "this step builds the plan of three: a queue, an exchange and a binding")
        threeStepPlan()
        d.vm.check()
        assertIs<CheckState.Done>(d.state.check, "the check did not finish: ${d.state.check}")
    }

    @Then("the results of the first {int} steps are shown as stale")
    fun staleResults(count: Int) {
        val done = assertIs<CheckState.Done>(d.state.check)
        assertTrue(done.stale, "the state does not say stale")
        (0 until count).forEach { i ->
            assertTrue(screen.isDrawn("planStepResult:$i"), "step ${i + 1} lost its result")
            assertEquals("stale", screen.stateDescription("planStepResult:$i")?.lowercase(), "the pill of step ${i + 1} is not marked stale on the screen")
        }
    }

    @Then("the new step shows no result")
    fun newStepNoResult() {
        val step = d.plan.steps.last()
        assertNull(d.state.stepCheck(step.id), "the state gives the new step a result")
        assertFalse(screen.isDrawn("planStepResult:${d.plan.steps.lastIndex}"), "the screen draws a result on the new step")
    }

    // ---- A running check can be seen and does not block the plan ----

    @When("the check is at step {int}")
    fun checkAtStep(position: Int) {
        val port = portOf(d.profile)
        d.restart() // the view model of this connection, built with its port; the plan comes back from the draft
        script(port)
        val held = nameOf(d.plan.steps[position - 1].action) ?: error("step $position has no name to hold the read on")
        gate = port.holdOn(held)
        d.vm.check()
        assertEquals(CheckState.Running(position, d.plan.steps.size), d.state.check, "the check is not at step $position")
    }

    @Then("I can still read the steps")
    fun canReadSteps() {
        val before = d.saveEnabled()
        assertTrue(screen.isDrawn("planStep:0"), "the step list is not drawn while the check runs")
        assertTrue(screen.textOf("planStep:0").orEmpty().contains(d.plan.steps[0].title), "the first row says: ${screen.textOf("planStep:0")}")
        assertTrue(screen.isEnabled("planner:addStepBtn"), "Add step is disabled while the check runs")
        assertTrue(before, "Save as migration is disabled while the check runs; the gate says ${d.saveGate()}")
        assertFalse(screen.isEnabled("planner:checkBtn"), "a second check can be started while one runs")
        d.addWithWizard(d.queueForm("late-step", "quorum"))
        assertEquals(d.plan.steps.size, d.titles().size)
        assertTrue(d.titles().last().contains("late-step"), "the plan could not be changed while the check runs: ${d.titles()}")
    }

    // ---- Switching the connection drops the check result ----

    @Given("a plan checked against {string}")
    fun planChecked(profile: String) {
        assertEquals(d.profile, profile)
        threeStepPlan()
        d.vm.check()
        assertIs<CheckState.Done>(d.state.check)
    }

    @When("I connect to {string}")
    fun connectTo(profile: String) {
        portOf(profile)
        d.profile = profile
        d.restart() // the window builds a new view model for the new connection
    }

    @Then("the steps show no result")
    fun noResults() {
        assertEquals(CheckState.None, d.state.check)
        assertTrue(d.plan.steps.isNotEmpty(), "the plan is gone, so \"no result\" would prove nothing")
        assertEquals(emptyList(), screen.tagsWithPrefix("planStepResult:"), "a step still shows a result")
        assertFalse(screen.isDrawn("planner:checkSummary"), "the summary of the old connection is still drawn")
    }

    @Then("the check button names {string}")
    fun buttonNames(profile: String) {
        assertEquals("Check against $profile", d.state.checkLabel)
        assertEquals("Check against $profile", screen.textOf("planner:checkBtn").orEmpty().trim())
    }

    // ---- A late check result of an old connection is dropped ----

    @Given("a check of the plan against {string} is running")
    fun checkRunning(profile: String) {
        assertEquals(d.profile, profile)
        threeStepPlan()
        val port = portOf(profile)
        val held = nameOf(d.plan.steps[1].action) ?: error("step 2 has no name")
        gate = port.holdOn(held)
        d.vm.check()
        assertIs<CheckState.Running>(d.state.check, "the check is not running: ${d.state.check}")
    }

    @When("I connect to {string} before it answers")
    fun connectBeforeAnswer(profile: String) {
        connectTo(profile)
        // The old connection answers now, after the switch.
        gate?.release() ?: error("no check was held")
    }

    @Then("the answer for {string} is not shown")
    fun answerNotShown(profile: String) {
        assertEquals(CheckState.None, d.state.check, "the answer for $profile reached the planner of ${d.profile}")
        assertEquals(emptyList(), screen.tagsWithPrefix("planStepResult:"), "a step shows the answer for $profile")
        assertFalse(screen.isDrawn("planner:checkSummary"), "the summary of $profile is drawn")
        assertFalse(screen.isDrawn("planner:checkProgress"), "the progress of $profile is drawn")
        assertEquals(emptyList(), portOf(d.profile).calls, "the new connection was read for the plan of $profile")
    }
}
