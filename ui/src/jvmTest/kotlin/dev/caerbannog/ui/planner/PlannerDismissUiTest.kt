package dev.caerbannog.ui.planner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.core.planner.PlannerTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-03 step 2: Dismiss (mockup section 11, O5, Q9). Asked only when the plan has a step; "Keep the plan", Esc and a click outside change
 * nothing; "Dismiss" discards the draft and the screen reports it through the state (`dismissed`), which the window turns into a return to
 * the Migrations screen. The planner never touches a migration file. Tags: `planner:question`, `planner:questionConfirmBtn`, `planner:questionCancelBtn`.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerDismissUiTest {

    private fun rig(prepare: UiRig.() -> Unit = {}) = UiRig(prepare = prepare)

    @Test
    fun `Dismiss on a plan with steps asks first, with the number of steps`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planner:dismissBtn").assertIsEnabled().performClick()
        assertEquals(PlannerQuestion.Dismiss(3), r.state.question)
        assertTrue(textOf("planner:question").contains(PlannerTexts.dismissQuestion(3)), textOf("planner:question"))
        assertTrue(r.store.hasDraft, "the draft was discarded before the answer")
        assertFalse(r.state.dismissed)
        r.close()
    }

    @Test
    fun `one step is told in the singular`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        node("planner:dismissBtn").performClick()
        assertTrue(textOf("planner:question").contains(PlannerTexts.dismissQuestion(1)), textOf("planner:question"))
        r.close()
    }

    @Test
    fun `Keep the plan changes nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planner:dismissBtn").performClick()
        node("planner:questionCancelBtn").performClick()
        assertNotDrawn("planner:question")
        assertEquals(3, r.state.plan.steps.size)
        assertTrue(r.store.hasDraft)
        assertFalse(r.state.dismissed)
        r.close()
    }

    @Test
    fun `Esc keeps the plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planner:dismissBtn").performClick()
        node("planner:question").requestFocus()
        node("planner:question").performKeyInput { pressKey(Key.Escape) }
        assertNotDrawn("planner:question")
        assertEquals(3, r.state.plan.steps.size)
        r.close()
    }

    @Test
    fun `Dismiss discards the draft, leaves a migration file untouched and reports it`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val filesBefore = r.folder.files.keys.toList()
        node("planner:dismissBtn").performClick()
        node("planner:questionConfirmBtn").performClick()
        assertTrue(r.state.dismissed)
        assertFalse(r.store.hasDraft)
        assertEquals(filesBefore, r.folder.files.keys.toList())
        assertEquals(emptyList(), r.folder.written)
        r.close()
    }

    @Test
    fun `an empty plan has no Dismiss to press and no question`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        assertFalse(r.state.canDismiss)
        assertNotDrawn("planner:question")
        r.close()
    }
}
