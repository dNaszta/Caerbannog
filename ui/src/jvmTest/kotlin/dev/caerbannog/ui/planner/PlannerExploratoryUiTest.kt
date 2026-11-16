package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-03 step 4 (test developer, round 1): exploratory checks of the built screen. Window 1280 x 800 unless the test says otherwise.
 * A test marked `@Disabled` documents a finding of the report (the expectation is the spec's, the screen does not meet it yet).
 */
@OptIn(ExperimentalTestApi::class)
class PlannerExploratoryUiTest {

    private fun rig(context: PlannerContext = PlannerContext(), prepare: UiRig.() -> Unit = {}) = UiRig(context = context, prepare = prepare)

    private fun DesktopComposeUiTest.toBinding(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Binding").performScrollTo().performClick() // on a short window the kinds scroll
        node("planWizard:next").performClick()
    }

    // ---- the note for a name that is in no list ----

    @Test
    fun `a typed exchange that is in no list gets the note, a known one and an empty one do not`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { exchange("events") }
        toBinding(r)
        assertNotDrawn("planForm:note:exchange") // empty
        node("planForm:exchange").performTextInput("evnts")
        assertEquals(PlannerTexts.NOT_IN_PLAN_NOTE, textOf("planForm:note:exchange"))
        node("planForm:exchange").performTextClearance()
        node("planForm:exchange").performTextInput("events")
        assertNotDrawn("planForm:note:exchange") // in this plan
        node("planForm:destination").performTextInput("nowhere")
        assertEquals(PlannerTexts.NOT_IN_PLAN_NOTE, textOf("planForm:note:destination"))
        node("planWizard:next").assertIsEnabled() // the note is advice, it never blocks (Q7)
        r.close()
    }

    // ---- short window: the wizard scrolls and its buttons stay reachable ----

    @Test
    fun `on a short window the binding form scrolls and Back, Next and Cancel are still on screen`() = runDesktopComposeUiTest(1280, 420) {
        val r = rig { exchange("events"); queue("orders") }
        toBinding(r)
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        r.close()
    }

    @Test
    fun `on a short window the summary shows Add to plan`() = runDesktopComposeUiTest(1280, 420) {
        val r = rig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performTextInput("orders")
        node("planWizard:next").performClick()
        node("planWizard:add").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `on a short window the planner header and the first step stay reachable`() = runDesktopComposeUiTest(1280, 420) {
        val r = rig { threeSteps() }
        showPlanner(r)
        node("planner:title").assertIsDisplayed()
        node("planner:addStepBtn").assertIsDisplayed()
        node("planStep:0").assertIsDisplayed()
        r.close()
    }

    // ---- a virtual host the user sets ----

    @Test
    fun `setting the virtual host of the plan moves every step and shows the other-host banner`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        node("planner:vhost").performClick()
        node("planner:vhostApplyBtn").assertIsNotEnabled() // unchanged
        node("planner:vhostField").performTextClearance()
        node("planner:vhostField").performTextInput("/orders")
        node("planner:vhostApplyBtn").assertIsEnabled().performClick()
        assertEquals("/orders", r.state.plan.vhost)
        assertTrue(r.state.plan.steps.all { it.action.vhost == "/orders" })
        assertIs<PlannerBanner.VhostMismatch>(r.state.banner)
        assertTrue(textOf("planner:vhost").contains("/orders"))
        r.close()
    }

    @Test
    fun `a blank virtual host cannot be applied`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        node("planner:vhost").performClick()
        node("planner:vhostField").performTextClearance()
        node("planner:vhostField").performTextInput("   ")
        node("planner:vhostApplyBtn").assertIsNotEnabled()
        assertEquals("/", r.state.plan.vhost)
        r.close()
    }

    // ---- copy ----

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    @Test
    fun `Copy puts exactly the migration text on the clipboard`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        val clipboard = FakeClipboard()
        setContent { CompositionLocalProvider(LocalClipboard provides clipboard) { PlannerOver(r) } }
        waitForIdle()
        node("planner:jsonCopyBtn").performClick()
        waitForIdle()
        assertEquals(r.state.migrationText, clipboard.text)
        r.close()
    }

    @Test
    fun `an empty plan offers nothing to copy`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        assertNotDrawn("planner:jsonCopyBtn")
        r.close()
    }

    // ---- names that are not plain ----

    @Test
    fun `markup and a very long name are drawn as plain text and break nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig {
            queue("<b>orders</b>")
            queue("q" + "x".repeat(200))
        }
        showPlanner(r)
        r.state.plan.steps.forEachIndexed { i, step ->
            assertTrue(textOf("planStep:$i").contains(step.title), "row $i: ${textOf("planStep:$i")}")
        }
        node("planner:title").assertIsDisplayed()
        r.close()
    }

    // ---- keyboard at the ends of the list ----

    @Test
    fun `Alt Up on the first step and Alt Down on the last change nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { threeSteps() }
        showPlanner(r)
        val before = r.state.plan.steps.map { it.title }
        node("planStep:0").requestFocus()
        node("planStep:0").performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionUp); keyUp(Key.AltLeft) }
        node("planStep:2").requestFocus()
        node("planStep:2").performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionDown); keyUp(Key.AltLeft) }
        assertEquals(before, r.state.plan.steps.map { it.title })
        r.close()
    }

    // ---- the error banner ----

    @Test
    fun `the write-failed banner goes away when the plan changes, and the next save can succeed`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); folder.failWrite(1) }
        showPlanner(r)
        node("planner:saveBtn").performClick()
        assertDrawn("planner:error")
        node("planner:saveBtn").performClick() // the same plan again: the folder now accepts
        assertEquals("0005", r.state.saved?.version)
        assertNotDrawn("planner:error")
        r.close()
    }

    // ---- findings ----

    @Test
    fun `the description field shows what the plan holds after the view model is replaced`() = runDesktopComposeUiTest(1280, 800) {
        val first = rig()
        var current by mutableStateOf(first)
        setContent { PlannerOver(current) }
        waitForIdle()
        node("planner:description").performTextInput("typed just before the switch")
        val second = rig() // a new view model that read the draft before the pending text was written
        current = second
        waitForIdle()
        val shown = node("planner:description").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals(second.state.description, shown, "the field shows text the plan does not hold; Save would write the plan without it")
        first.close(); second.close()
    }
}
