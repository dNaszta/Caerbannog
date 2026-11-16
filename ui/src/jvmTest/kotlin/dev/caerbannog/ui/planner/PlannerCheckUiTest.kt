package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.PlanCheck
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StepCheck
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-06 step 2 (red until the screen draws the check): mockup section 9 and the check parts of the main block, on the REAL planner screen over a REAL
 * view model with a scripted [FakeDeclareCheck] (nothing else is faked, so what the screen draws is what the app would be given). 1280 x 1100 for the
 * plans of three steps; the seven-row plan of mockup section 9 is drawn at 2200 x 1200 (list and card side by side), so the lazy step list composes
 * every row.
 *
 * Tags fixed here (the architect confirms; the brief names the first five): `planStepResult:<i>`, `planStepBlocked:<i>`, `planner:checkSummary`,
 * `planner:checkStale`, `planner:checkProgress`, and NEW: `planStepReason:<i>` (the reason line of a step) and `planner:checkAgainBtn`.
 * A stale result pill says so in its semantics (`stateDescription` "stale"), because a dimmed colour cannot be asserted and a screen reader needs it.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerCheckUiTest {
    private val full = PlannerContext(controlLevel = TopologyControlLevel.FULL_CONTROL)
    private val declareAndEdit = PlannerContext(controlLevel = TopologyControlLevel.DECLARE_AND_EDIT)

    private fun hhmm(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    private fun DesktopComposeUiTest.showAndSize(r: UiRig) = showPlanner(r)

    private fun DesktopComposeUiTest.press(tag: String) { node(tag).performClick(); waitForIdle() }

    private fun DesktopComposeUiTest.stateDescription(tag: String): String? =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun DesktopComposeUiTest.drawnTags(prefix: String): List<String> =
        onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()

    private val nameOf: (MigrationAction) -> String? = {
        when (it) {
            is MigrationAction.DeclareQueue -> it.name
            is MigrationAction.DeclareExchange -> it.name
            is MigrationAction.DeclareStream -> it.name
            is MigrationAction.DeleteQueue -> it.name
            else -> null
        }
    }

    private fun FakeDeclareCheck.scriptAll(r: UiRig, finding: BrokerFinding = BrokerFinding.Absent) = apply {
        r.state.plan.steps.forEach { step ->
            val name = nameOf(step.action)
            if (name != null) answerNamed(name, finding) else answer(step.action, finding)
        }
    }

    private fun threeStepRig(port: FakeDeclareCheck, context: PlannerContext = PlannerContext()) =
        UiRig(context = context, declareCheck = port) { threeSteps() }.also { port.scriptAll(it) }

    /** Mockup section 9 as a plan from a file (a delete step cannot come from the wizard): one of each result, two of them blocked. */
    private fun sevenResults(port: FakeDeclareCheck): UiRig {
        port.answerNamed("invoices", BrokerFinding.Absent)
            .answerNamed("events", BrokerFinding.Same)
            .answerNamed("orders", BrokerFinding.Different)
            .answerNamed("evnts", BrokerFinding.Absent) // the exchange of the binding below, which nobody declares
            .answerNamed("telemetry", BrokerFinding.Unreadable("The broker did not answer."))
            .answerNamed("legacy-orders", BrokerFinding.Same)
            .answerNamed("gone-orders", BrokerFinding.Absent)
        return UiRig(context = declareAndEdit, declareCheck = port) {
            store.putDraft(
                "",
                listOf(
                    MigrationAction.DeclareQueue("invoices"),
                    MigrationAction.DeclareExchange("events"),
                    MigrationAction.DeclareQueue("orders"),
                    MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created"),
                    MigrationAction.DeclareStream("telemetry"),
                    MigrationAction.DeleteQueue("legacy-orders"),
                    MigrationAction.DeleteQueue("gone-orders"),
                ),
            )
        }
    }

    private fun UiRig.results(): List<StepCheck> = (state.check as CheckState.Done).results

    // ---- the button ----

    @Test
    fun `the button names the connection it checks against`() = runDesktopComposeUiTest(1280, 1100) {
        val r = UiRig(context = PlannerContext(connectionName = "uat-eu"), declareCheck = FakeDeclareCheck()) { queue("orders") }
        showAndSize(r)
        assertEquals("Check against uat-eu", textOf("planner:checkBtn").trim())
        r.close()
    }

    @Test
    fun `with no connection the button just says Check`() = runDesktopComposeUiTest(1280, 1100) {
        val r = UiRig(context = PlannerContext(connectionName = null)) { queue("orders") }
        showAndSize(r)
        assertEquals("Check", textOf("planner:checkBtn").trim())
        node("planner:checkBtn").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `the button is enabled with a connection, a port and a step, and pressing it sends the check event`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = threeStepRig(port)
        showAndSize(r)
        node("planner:checkBtn").assertIsEnabled()
        press("planner:checkBtn")
        assertTrue("check" in r.events.calls, "the button did not send the check event: ${r.events.calls}")
        assertTrue(r.state.check is CheckState.Done)
        r.close()
    }

    @Test
    fun `an empty plan disables the button, and the state says why`() = runDesktopComposeUiTest(1280, 1100) {
        val empty = UiRig(declareCheck = FakeDeclareCheck())
        showAndSize(empty)
        node("planner:checkBtn").assertIsNotEnabled()
        assertEquals(PlannerTexts.ADD_A_STEP_FIRST, empty.state.checkDisabledReason)
        empty.close()
    }

    @Test
    fun `the button is disabled without a port, and the reason is the fixed no-check text`() = runDesktopComposeUiTest(1280, 1100) {
        val r = UiRig { queue("orders") }
        showAndSize(r)
        node("planner:checkBtn").assertIsNotEnabled()
        assertEquals(PlanCheck.NO_CHECK, r.state.checkDisabledReason)
        r.close()
    }

    @Test
    fun `a connection that cannot run migrations shows its own reason first and draws no check at all`() = runDesktopComposeUiTest(1280, 1100) {
        val reason = "This broker runs RabbitMQ 3.x: it is a migration source only"
        val r = UiRig(context = PlannerContext(unavailableReason = reason), declareCheck = FakeDeclareCheck())
        showAndSize(r)
        assertEquals(reason, r.state.checkDisabledReason)
        assertNotDrawn("planner:checkBtn")
        assertNotDrawn("planner:checkSummary")
        r.close()
    }

    // ---- before and after ----

    @Test
    fun `before a check no row shows a result and nothing says a check ran`() = runDesktopComposeUiTest(1280, 1100) {
        val r = threeStepRig(FakeDeclareCheck())
        showAndSize(r)
        assertEquals(emptyList(), drawnTags("planStepResult:"))
        assertEquals(emptyList(), drawnTags("planStepBlocked:"))
        assertNotDrawn("planner:checkSummary")
        assertNotDrawn("planner:checkStale")
        assertNotDrawn("planner:checkProgress")
        r.close()
    }

    @Test
    fun `every result of mockup section 9 is drawn as its pill on its own row`() = runDesktopComposeUiTest(2200, 1200) {
        val r = sevenResults(FakeDeclareCheck())
        showAndSize(r)
        val port = r.declareCheck as FakeDeclareCheck
        press("planner:checkBtn")
        assertEquals(7, r.results().size, "the check did not finish: ${r.state.check}; reads ${port.calls.map { it.description }}")
        assertEquals(
            listOf(
                "Will be created", "Already there", "Exists with different settings", "Needs something that is missing",
                "Could not be checked", "Will be deleted", "Already gone",
            ),
            (0..6).map { textOf("planStepResult:$it").trim() },
        )
        r.close()
    }

    @Test
    fun `a blocked step shows the Blocked pill AND its result pill, the others only the result`() = runDesktopComposeUiTest(2200, 1200) {
        val r = sevenResults(FakeDeclareCheck())
        showAndSize(r)
        press("planner:checkBtn")
        // The two delete steps are refused at the level Declare and edit.
        (5..6).forEach { i ->
            assertEquals("Blocked", textOf("planStepBlocked:$i").trim(), "step ${i + 1}")
            assertDrawn("planStepResult:$i")
        }
        (0..4).forEach { assertNotDrawn("planStepBlocked:$it") }
        r.close()
    }

    @Test
    fun `the reason line says why, in the fixed words of the check`() = runDesktopComposeUiTest(2200, 1200) {
        val r = sevenResults(FakeDeclareCheck())
        showAndSize(r)
        press("planner:checkBtn")
        val steps = r.results()
        assertTrue(textOf("planStepReason:2").contains(PlanCheck.DIFFERENT_NOTE), "the Different note is the reason line of that step: ${textOf("planStepReason:2")}")
        assertTrue(textOf("planStepReason:3").contains("The exchange evnts is not on the broker and is not declared earlier in this plan."), textOf("planStepReason:3"))
        assertTrue(textOf("planStepReason:4").contains("The broker did not answer."), textOf("planStepReason:4"))
        assertTrue(textOf("planStepReason:5").contains("Destructive actions require Full control level"), textOf("planStepReason:5"))
        assertNotNull(steps[5].blocked)
        listOf(0, 1).forEach { assertNotDrawn("planStepReason:$it") }
        r.close()
    }

    @Test
    fun `the summary says what was checked, when, how many and that it is not a promise`() = runDesktopComposeUiTest(2200, 1200) {
        val r = sevenResults(FakeDeclareCheck())
        showAndSize(r)
        press("planner:checkBtn")
        val text = textOf("planner:checkSummary")
        assertTrue(text.contains("Checked against uat-eu at ${hhmm(CHECK_TIME)}: "), "connection and the clock's time: \"$text\"")
        assertTrue(
            text.contains(
                "1 will be created, 1 already there, 1 exists with different settings, 1 needs something that is missing, 1 could not be checked",
            ),
            text,
        )
        assertTrue(text.contains("2 blocked"), text)
        assertTrue(text.contains(PlannerTexts.CHECK_CAVEAT), "the caveat line is part of the summary: \"$text\"")
        r.close()
    }

    @Test
    fun `the time in the summary is the injected clock's, not the system clock`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = UiRig(declareCheck = port, nowMillis = 86_400_000L * 365 * 30) { queue("orders") }.also { port.scriptAll(it) }
        showAndSize(r)
        press("planner:checkBtn")
        assertTrue(textOf("planner:checkSummary").contains(" at ${hhmm(86_400_000L * 365 * 30)}: "), textOf("planner:checkSummary"))
        r.close()
    }

    // ---- progress ----

    @Test
    fun `while a check runs the screen says which step, the plan can still be read and changed, and Check cannot be pressed twice`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = threeStepRig(port)
        showAndSize(r)
        val gate = port.holdOn("events")
        press("planner:checkBtn")
        assertTrue(textOf("planner:checkProgress").contains("Checking step 2 of 3 against uat-eu"), textOf("planner:checkProgress"))
        (0..2).forEach { assertDrawn("planStep:$it") }
        node("planner:addStepBtn").assertIsEnabled()
        node("planner:saveBtn").assertIsEnabled() // the check never enables or disables Save
        node("planner:checkBtn").assertIsNotEnabled()
        assertNotDrawn("planner:checkSummary")
        gate.release()
        waitForIdle()
        assertNotDrawn("planner:checkProgress")
        assertDrawn("planner:checkSummary")
        r.close()
    }

    @Test
    fun `a step that is added while the check runs is drawn at once and shows no result`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = threeStepRig(port)
        showAndSize(r)
        val gate = port.holdOn("events")
        press("planner:checkBtn")
        r.queue("invoices")
        waitForIdle()
        assertDrawn("planStep:3")
        gate.release()
        waitForIdle()
        assertNotDrawn("planStepResult:3")
        assertDrawn("planStepResult:0")
        assertDrawn("planner:checkStale")
        r.close()
    }

    // ---- stale ----

    @Test
    fun `a fresh result is not stale`() = runDesktopComposeUiTest(1280, 1100) {
        val r = threeStepRig(FakeDeclareCheck())
        showAndSize(r)
        press("planner:checkBtn")
        assertNotDrawn("planner:checkStale")
        (0..2).forEach { assertNull(stateDescription("planStepResult:$it"), "row $it is marked stale on a fresh result") }
        r.close()
    }

    @Test
    fun `a change of the plan says so, keeps the pills as stale and shows no result on the new step`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = threeStepRig(port)
        showAndSize(r)
        press("planner:checkBtn")
        val before = (0..2).map { textOf("planStepResult:$it") }
        r.queue("invoices")
        waitForIdle()
        assertEquals("The plan changed since the check. Check again.", textOf("planner:checkStale").trim().removeSuffix("Check again").trim())
        assertEquals(before, (0..2).map { textOf("planStepResult:$it") }, "the pills of the first three steps stay")
        (0..2).forEach { assertEquals("stale", stateDescription("planStepResult:$it")?.lowercase(), "row $it is not marked stale") }
        assertNotDrawn("planStepResult:3")
        r.close()
    }

    @Test
    fun `Check again runs the check anew and the stale marks go`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = threeStepRig(port)
        showAndSize(r)
        press("planner:checkBtn")
        r.queue("invoices")
        port.scriptAll(r)
        waitForIdle()
        press("planner:checkAgainBtn")
        assertEquals(2, r.events.calls.count { it == "check" }, "Check again must send the check event: ${r.events.calls}")
        assertNotDrawn("planner:checkStale")
        assertDrawn("planStepResult:3")
        (0..3).forEach { assertNull(stateDescription("planStepResult:$it")) }
        r.close()
    }

    @Test
    fun `every kind of change makes the screen say stale`() = runDesktopComposeUiTest(1280, 1100) {
        listOf<Pair<String, UiRig.() -> Unit>>(
            "remove" to { vm.removeStep(state.plan.steps.last().id) },
            "move" to { vm.moveStepDown(state.plan.steps.first().id) },
            "vhost" to { vm.setVirtualHost("billing") },
        ).forEach { (_, change) ->
            val r = threeStepRig(FakeDeclareCheck())
            showAndSize(r)
            press("planner:checkBtn")
            r.change()
            waitForIdle()
            assertDrawn("planner:checkStale")
            r.close()
        }
    }

    @Test
    fun `a pill follows its step when the step moves`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = UiRig(declareCheck = port) { queue("a"); queue("b") }
        port.answerNamed("a", BrokerFinding.Absent).answerNamed("b", BrokerFinding.Same)
        showAndSize(r)
        press("planner:checkBtn")
        assertEquals(listOf("Will be created", "Already there"), (0..1).map { textOf("planStepResult:$it").trim() })
        r.vm.moveStepDown(r.state.plan.steps.first().id)
        waitForIdle()
        assertEquals(listOf("Already there", "Will be created"), (0..1).map { textOf("planStepResult:$it").trim() }, "the pills stayed on their rows, not with their steps")
        r.close()
    }

    // ---- the limited states keep their rules ----

    @Test
    fun `at the Read-only level a checked step shows Blocked with the level's reason`() = runDesktopComposeUiTest(1280, 1100) {
        val port = FakeDeclareCheck()
        val r = UiRig(context = PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY), declareCheck = port) { queue("orders") }
        port.scriptAll(r)
        showAndSize(r)
        press("planner:checkBtn")
        assertEquals("Blocked", textOf("planStepBlocked:0").trim())
        assertTrue(textOf("planStepReason:0").contains("Topology control level is Read-only"), textOf("planStepReason:0"))
        assertDrawn("planStepResult:0")
        r.close()
    }

    @Test
    fun `the check line says nothing about Save and does not change Save`() = runDesktopComposeUiTest(1280, 1100) {
        val r = threeStepRig(FakeDeclareCheck())
        showAndSize(r)
        node("planner:saveBtn").assertIsEnabled()
        press("planner:checkBtn")
        node("planner:saveBtn").assertIsEnabled()
        assertFalse(textOf("planner:checkSummary").lowercase().contains("save"))
        r.close()
    }
}
