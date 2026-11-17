package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-01b step 4 (test developer): the name rules typed into the REAL forms of the wizard (1280 x 800). The refusal text is drawn once, under the
 * field, and the button that moves on stays disabled; an accepted name draws no error; a name from an opened file is not refused (A2).
 */
@OptIn(ExperimentalTestApi::class)
class PlannerNameRulesExploratoryUiTest {
    private fun DesktopComposeUiTest.toForm(r: UiRig, kind: String) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:$kind").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.refusedOnce(field: String, message: String) {
        node("planForm:error:$field").assertIsDisplayed()
        assertEquals(message, textOf("planForm:error:$field"))
        assertEquals(1, onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().size, "the text \"$message\" must be drawn once")
        node("planWizard:next").assertIsNotEnabled()
    }

    private fun DesktopComposeUiTest.accepted(field: String) {
        assertNotDrawn("planForm:error:$field")
        node("planWizard:next").assertIsEnabled()
    }

    @Test
    fun `an amq prefix is refused in the queue, exchange and stream forms`() {
        for (kind in listOf("Queue", "Exchange", "Stream")) runDesktopComposeUiTest(1280, 800) {
            val r = UiRig()
            toForm(r, kind)
            node("planForm:name").performTextReplacement("amq.orders")
            refusedOnce("name", PlannerTexts.NAME_RESERVED)
            r.close()
        }
    }

    @Test
    fun `a queue name of 256 bytes made of two-byte characters is refused, 255 bytes is not`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r, "Queue")
        node("planForm:name").performTextReplacement("é".repeat(128))
        refusedOnce("name", PlannerTexts.QUEUE_NAME_TOO_LONG)
        node("planForm:name").performTextReplacement("é".repeat(127) + "a")
        accepted("name")
        r.close()
    }

    @Test
    fun `the length rule is for queues only`() {
        for (kind in listOf("Exchange", "Stream")) runDesktopComposeUiTest(1280, 800) {
            val r = UiRig()
            toForm(r, kind)
            node("planForm:name").performTextReplacement("a".repeat(300))
            accepted("name")
            r.close()
        }
    }

    @Test
    fun `a typed response stream name with the prefix is refused on its own field`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r, "Stream")
        node("planForm:name").performTextReplacement("telemetry")
        node("planForm:responseTick").performClick()
        accepted("responseName")
        node("planForm:responseName").performTextReplacement("amq.replies")
        refusedOnce("responseName", PlannerTexts.NAME_RESERVED)
        assertNotDrawn("planForm:error:name")
        node("planForm:responseName").performTextReplacement("amq-replies")
        accepted("responseName")
        r.close()
    }

    @Test
    fun `the dot style derives amq dln from the stream amq and the form refuses it on the response field`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        r.vm.setNamingStyle(dev.caerbannog.core.planner.NamingStyle.DOT)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performTextReplacement("amq")
        node("planForm:responseTick").performClick()
        refusedOnce("responseName", PlannerTexts.NAME_RESERVED)
        assertNotDrawn("planForm:error:name")
        r.close()
    }

    @Test
    fun `a space, a slash, a bare amq and an upper-case prefix are accepted`() {
        for (name in listOf("my queue", "a/b", "amq", "AMQ.x", "Amq.x")) runDesktopComposeUiTest(1280, 800) {
            val r = UiRig()
            toForm(r, "Queue")
            node("planForm:name").performTextReplacement(name)
            accepted("name")
            r.close()
        }
    }

    @Test
    fun `a name from an opened file is not refused by the plan`() = runDesktopComposeUiTest(1280, 800) {
        val store = FakePlanDraftStore()
        store.putDraft("file", listOf(MigrationAction.DeclareQueue("amq.fromfile"), MigrationAction.DeclareQueue("q".repeat(300))))
        val r = UiRig(store = store)
        showPlanner(r)
        node("planStep:0").assertIsDisplayed()
        assertNotDrawn("planStepProblem:0")
        assertNotDrawn("planStepProblem:1")
        assertNotDrawn("planner:wholePlanProblem:0")
        r.close()
    }
}
