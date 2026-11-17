package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the Retention block of the stream form (mockup "4. Stream", Retention), 1280 x 800, also at font scale 1.5 and on the fold.
 * RED until the block exists. Tags in [UiRig]. Behaviour pinned where the mockup is silent (the architect confirms it):
 *  - the block ("Keep messages for", "Keep at most (bytes)") is drawn while the form ADDS a stream, always, empty; it is NOT drawn while a step is edited (an edit
 *    replaces one step, and the policy step beside a stream is its own step: typing retention there could only be dropped);
 *  - `planForm:retentionResponseTick` ("Same retention for the response stream") is drawn only while the response stream tick is on, and then it is on;
 *  - a retention value the form refuses is under its field (`planForm:error:keepFor`, `planForm:error:keepAtMost`) and disables Next; an empty field is no error.
 */
@OptIn(ExperimentalTestApi::class)
class StreamRetentionBlockUiTest {
    private fun DesktopComposeUiTest.toStreamForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun UiRig.form() = assertIs<StreamForm>(assertIs<WizardState.Details>(state.wizard).form)

    private fun DesktopComposeUiTest.drawn(tag: String) =
        onAllNodes(SemanticsMatcher("tag is $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the block is drawn empty on a new stream, with the response tick hidden until the pair is ticked`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:keepFor").assertIsDisplayed()
        node("planForm:keepAtMost").assertIsDisplayed()
        assertEquals("", fieldText("planForm:keepFor"))
        assertEquals("", fieldText("planForm:keepAtMost"))
        assertTrue(!drawn("planForm:retentionResponseTick"), "the tick is drawn only while the response stream is ticked")
        assertTrue(!drawn("planForm:error:keepFor") && !drawn("planForm:error:keepAtMost"), "an empty field is no error")
        r.close()
    }

    @Test
    fun `ticking the pair draws the retention tick, on, and unticking the pair hides it again`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:responseTick").performClick()
        node("planForm:retentionResponseTick").performScrollTo().assertIsDisplayed().assertIsOn()
        assertTrue(textOf("planForm:retentionResponseTick").contains("Same retention for the response stream"), textOf("planForm:retentionResponseTick"))
        node("planForm:retentionResponseTick").performScrollTo().performClick()
        node("planForm:retentionResponseTick").assertIsOff()
        assertEquals(false, r.form().sameRetentionForResponse)
        node("planForm:responseTick").performScrollTo().performClick()
        assertTrue(!drawn("planForm:retentionResponseTick"))
        r.close()
    }

    @Test
    fun `typing sends the retention to the form`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:keepFor").performTextInput("7D")
        node("planForm:keepAtMost").performTextInput("1000000")
        assertEquals(StreamForm(name = "telemetry", keepFor = "7D", keepAtMost = "1000000"), r.form())
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `a duration the broker would refuse is said under its field and blocks Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:keepFor").performTextInput("7d")
        assertEquals("Enter a number and a unit, for example 7D", textOf("planForm:error:keepFor"))
        node("planWizard:next").assertIsNotEnabled()
        node("planForm:keepFor").performTextReplacement("7D")
        assertTrue(!drawn("planForm:error:keepFor"))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `a size that is not a whole number is said under its field`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:keepAtMost").performTextInput("1.5")
        assertEquals("Enter a whole number", textOf("planForm:error:keepAtMost"))
        node("planWizard:next").assertIsNotEnabled()
        node("planForm:keepAtMost").performTextReplacement("0")
        assertTrue(!drawn("planForm:error:keepAtMost"), "0 is let through")
        r.close()
    }

    @Test
    fun `the summary lists the stream and its retention policy, and Add to plan puts both in the list`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:keepFor").performTextInput("7D")
        node("planWizard:next").performClick()
        assertTrue(textOf("planWizard:summary").contains("Declare stream telemetry") && textOf("planWizard:summary").contains("Declare policy telemetry-retention"), textOf("planWizard:summary"))
        node("planWizard:add").performClick()
        assertEquals(listOf("DeclareStream", "DeclarePolicy"), r.state.plan.steps.map { it.action::class.simpleName })
        assertTrue(textOf("planStep:1").contains("apply-to=streams, priority=0; max-age=7D"), textOf("planStep:1"))
        r.close()
    }

    @Test
    fun `editing a stream step draws no retention block (an edit replaces one step)`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry") }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:edit").performClick()
        node("planForm:name").assertIsDisplayed()
        assertTrue(!drawn("planForm:keepFor") && !drawn("planForm:keepAtMost") && !drawn("planForm:retentionResponseTick"), "no retention block while editing")
        assertEquals(MigrationAction.DeclareStream("telemetry"), r.state.plan.steps.single().action)
        r.close()
    }

    @Test
    fun `at font scale 1_5 the block, the buttons and the errors are all reachable on the fold of 1280 x 800`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { PlannerOver(r) } }
        waitForIdle()
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performTextInput("telemetry")
        node("planForm:responseTick").performScrollTo().performClick()
        node("planForm:keepFor").performScrollTo().assertIsDisplayed()
        node("planForm:keepAtMost").performScrollTo().assertIsDisplayed()
        node("planForm:retentionResponseTick").performScrollTo().assertIsDisplayed()
        node("planForm:keepFor").performTextInput("7d")
        node("planForm:error:keepFor").performScrollTo().assertIsDisplayed()
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        r.close()
    }

    @Test
    fun `on a short window the retention fields scroll and the buttons stay on screen`() = runDesktopComposeUiTest(1280, 420) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performScrollTo().performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performScrollTo().performTextInput("telemetry")
        node("planForm:responseTick").performScrollTo().performClick()
        node("planForm:keepFor").performScrollTo().assertIsDisplayed()
        node("planForm:keepAtMost").performScrollTo().assertIsDisplayed()
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        r.close()
    }
}
