package dev.caerbannog.ui.planner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlanProblem
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.SaveGate
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.ui.appframe.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-03 step 2: the planner screen (mockup main block and sections 2, 6, 7, 10), 1280 x 800. RED until `PlannerScreen`, `PlannerEvents` and the
 * `core` additions exist. Tags and the kit: [UiRig]. Texts are quoted from [PlannerTexts] and from the state, never typed twice, so a
 * rewording after the owner's mockup review is a one-line edit in `PlannerTexts`. The screen decides nothing: what is enabled, what it says and
 * in which order come from [PlannerState].
 */
@OptIn(ExperimentalTestApi::class)
class PlannerScreenUiTest {

    private fun rig(prepare: UiRig.() -> Unit = {}) = UiRig(prepare = prepare)

    // ---- header and empty state ----

    @Test
    fun `the header shows the title, the description field, the virtual host, the naming style and the draft pill`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        node("planner:title").assertIsDisplayed()
        assertTrue(isEditable("planner:description"), "the description is a text field")
        assertTrue(textOf("planner:vhost").contains("/"), textOf("planner:vhost"))
        assertTrue(textOf("planner:namingChip").contains(NamingStyle.KEBAB.label), textOf("planner:namingChip"))
        assertEquals(r.state.draftLabel, textOf("planner:draftPill"))
        r.close()
    }

    @Test
    fun `an empty plan shows No steps yet, nothing to review, and Check, Save and Dismiss disabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        assertTrue(textOf("planner:emptyState").contains(PlannerTexts.NO_STEPS_YET), textOf("planner:emptyState"))
        assertTrue(textOf("planner:reviewEmpty").contains(PlannerTexts.NOTHING_TO_REVIEW))
        node("planner:addStepBtn").assertIsEnabled()
        node("planner:checkBtn").assertIsNotEnabled()
        node("planner:saveBtn").assertIsNotEnabled()
        node("planner:dismissBtn").assertIsNotEnabled()
        assertEquals(PlannerTexts.ADD_A_STEP_FIRST, textOf("planner:saveReason"))
        assertNotDrawn("planStep:0")
        r.close()
    }

    @Test
    fun `Check is drawn disabled with the reason, not hidden (until the check exists)`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planner:checkBtn").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(PlannerTexts.CHECK_LATER, r.state.checkDisabledReason)
        r.close()
    }

    @Test
    fun `Add step opens the wizard on page 1`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        assertDrawn("planWizard")
        assertDrawn("planWizard:kind:Queue")
        assertEquals(listOf("openWizard"), r.events.calls)
        r.close()
    }

    // ---- the step list ----

    @Test
    fun `the step list shows every step in order with its title and detail, 0-based tags`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        r.state.plan.steps.forEachIndexed { i, step ->
            val text = textOf("planStep:$i")
            assertTrue(text.contains(step.title), "row $i is \"$text\", expected the title \"${step.title}\"")
            step.detail?.let { assertTrue(text.contains(it), "row $i lacks its detail \"$it\": \"$text\"") }
        }
        assertNotDrawn("planStep:3")
        assertNotDrawn("planner:emptyState")
        node("planner:dismissBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `a long plan is a lazy list, the last row is reachable`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { (1..60).forEach { queue("queue-$it") } }
        showPlanner(r)
        assertDrawn("planStep:0")
        assertNotDrawn("planStep:59") // not composed yet: the list is lazy
        node("planner:stepList").performScrollToIndex(59)
        assertDrawn("planStep:59")
        r.close()
    }

    @Test
    fun `the row menu holds Edit, Move up, Move down and Remove`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planStepMenu:1").performClick()
        listOf("edit", "moveUp", "moveDown", "remove").forEach { assertDrawn("planStepMenu:1:$it") }
        r.close()
    }

    @Test
    fun `Move down in the menu swaps the step with the next one`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:moveDown").performClick()
        assertEquals(listOf(before[1], before[0], before[2]), r.state.plan.steps.map { it.title })
        assertTrue(textOf("planStep:0").contains(before[1]))
        r.close()
    }

    @Test
    fun `Move up in the menu swaps the step with the previous one`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStepMenu:2").performClick()
        node("planStepMenu:2:moveUp").performClick()
        assertEquals(listOf(before[0], before[2], before[1]), r.state.plan.steps.map { it.title })
        r.close()
    }

    @Test
    fun `Remove in the menu removes only that step, asks no question and sends nothing to the broker`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStepMenu:1").performClick()
        node("planStepMenu:1:remove").performClick()
        assertEquals(listOf(before[0], before[2]), r.state.plan.steps.map { it.title })
        assertNotDrawn("planner:question")
        r.close()
    }

    @Test
    fun `Edit in the menu opens the wizard on the form of that step, page 2`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:edit").performClick()
        assertDrawn("planWizard")
        assertEquals("orders", textOfField("planForm:name"))
        r.close()
    }

    @Test
    fun `Alt with Down moves the focused step down and the focus goes with it`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStep:0").requestFocus()
        node("planStep:0").performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionDown); keyUp(Key.AltLeft) }
        assertEquals(listOf(before[1], before[0], before[2]), r.state.plan.steps.map { it.title })
        node("planStep:1").assertIsFocused()
        r.close()
    }

    @Test
    fun `Alt with Up moves the focused step up`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStep:2").requestFocus()
        node("planStep:2").performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionUp); keyUp(Key.AltLeft) }
        assertEquals(listOf(before[0], before[2], before[1]), r.state.plan.steps.map { it.title })
        r.close()
    }

    @Test
    fun `Down without Alt moves nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStep:0").requestFocus()
        node("planStep:0").performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(before, r.state.plan.steps.map { it.title })
        r.close()
    }

    // ---- problems, notes, the save gate ----

    @Test
    fun `a step that needs something declared later shows the problem on its row and Save says why it is off`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { binding("events", "orders", "k"); queue("orders"); exchange("events") }
        showPlanner(r)
        val first = r.state.stepProblems(r.state.plan.steps[0].id)
        assertTrue(first.isNotEmpty())
        first.forEach { assertTrue(textOf("planStepProblem:0").contains(it.message), "row 0: ${textOf("planStepProblem:0")}") }
        assertTrue(textOf("planStepProblem:0").contains("Problem"), "the pill")
        assertNotDrawn("planStepProblem:1")
        node("planner:saveBtn").assertIsNotEnabled()
        assertEquals(PlannerTexts.saveBlocked(r.state.blockingCount), textOf("planner:saveReason"))
        r.close()
    }

    @Test
    fun `a note is drawn neutral on its row, without the Problem pill, and does not disable Save`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); queue("orders") }
        showPlanner(r)
        assertTrue(textOf("planStepNote:1").contains(PlannerTexts.declaredTwice("orders")), textOf("planStepNote:1"))
        assertFalse(textOf("planStepNote:1").contains("Problem"))
        assertNotDrawn("planStepProblem:1")
        node("planner:saveBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `a problem of the whole plan is drawn above the steps`() = runDesktopComposeUiTest(1280, 800) {
        val plan = TopologyPlan().append(listOf(MigrationAction.DeclareQueue("orders")))
        showState(
            PlannerState(
                plan = plan,
                problems = listOf(PlanProblem(null, "the parser refuses this plan", blocking = true)),
                saveGate = SaveGate.Disabled(PlannerTexts.saveBlocked(1)),
            ),
        )
        assertTrue(textOf("planner:wholePlanProblem:0").contains("the parser refuses this plan"))
        val top = node("planner:wholePlanProblem:0").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < node("planStep:0").fetchSemanticsNode().boundsInRoot.top, "the whole-plan problem is below the first step")
    }

    // ---- save, description, naming ----

    @Test
    fun `Save as migration sends the event and the plan is saved`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        node("planner:saveBtn").assertIsEnabled().performClick()
        assertTrue("saveAsMigration" in r.events.calls)
        assertEquals("0005", r.state.saved?.version)
        r.close()
    }

    @Test
    fun `typing in the description field sends the text to the view model`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        node("planner:description").performTextInput("Add orders")
        assertEquals("Add orders", r.state.description)
        assertTrue(r.events.calls.any { it.startsWith("setDescription:") })
        r.close()
    }

    @Test
    fun `leaving the screen flushes the pending description`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        var shown by mutableStateOf(true)
        setContent { if (shown) PlannerOver(r) }
        waitForIdle()
        node("planner:description").performTextInput("x")
        shown = false
        waitForIdle()
        assertTrue("flush" in r.events.calls, "calls: ${r.events.calls}")
        r.close()
    }

    @Test
    fun `the naming chip opens the five styles and choosing one sends it`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        node("planner:namingChip").performClick()
        NamingStyle.entries.forEach { assertDrawn("planner:namingOption:${it.name}") }
        node("planner:namingOption:SNAKE").performClick()
        assertEquals(NamingStyle.SNAKE, r.state.namingStyle)
        assertTrue(textOf("planner:namingChip").contains(NamingStyle.SNAKE.label))
        r.close()
    }

    @Test
    fun `the saved error shows both lines and keeps the plan on screen`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); folder.failWrite(1) }
        showPlanner(r)
        node("planner:saveBtn").performClick()
        val text = textOf("planner:error")
        assertTrue(text.contains(PlannerTexts.FILE_NOT_WRITTEN) && text.contains(PlannerTexts.FILE_NOT_WRITTEN_DETAIL), text)
        assertDrawn("planStep:0")
        r.close()
    }

    @Test
    fun `a draft that could not be written shows its own message`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        r.store.refuseWrites = true
        r.queue("orders")
        waitForIdle()
        assertTrue(textOf("planner:error").contains(PlannerTexts.DRAFT_NOT_SAVED))
        r.close()
    }

    // ---- the JSON view ----

    @Test
    fun `the JSON view shows the migration text and cannot be edited`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        assertDrawn("planner:reviewCard")
        val text = textOf("planner:jsonView")
        assertTrue(text.contains("declareQueue") && text.contains("orders"), text)
        assertFalse(isEditable("planner:jsonView"), "the JSON view must be read only")
        node("planner:jsonCopyBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the JSON view follows the plan when a step is added`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        r.exchange("events")
        waitForIdle()
        assertTrue(textOf("planner:jsonView").contains("declareExchange"), textOf("planner:jsonView"))
        r.close()
    }

    // ---- navigation and narrow window ----

    @Test
    fun `the drawer keeps Migrations selected and navigation leaves through onNavigate`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        val seen = mutableListOf<AppDestination>()
        showPlanner(r) { seen += it }
        assertTrue(node("navItem:Migrations").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected) == true, "Migrations is not the selected drawer item")
        node("navItem:Topology").performClick()
        assertEquals(listOf(AppDestination.Topology), seen)
        r.close()
    }

    @Test
    fun `at 1024 px the app bar buttons wrap and the title stays`() = runDesktopComposeUiTest(1024, 768) {
        val r = rig { queue("orders") }
        showPlanner(r)
        node("planner:title").assertIsDisplayed()
        listOf("planner:dismissBtn", "planner:checkBtn", "planner:saveBtn").forEach { node(it).assertIsDisplayed() }
        r.close()
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.textOfField(tag: String): String =
        node(tag).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty()
}
