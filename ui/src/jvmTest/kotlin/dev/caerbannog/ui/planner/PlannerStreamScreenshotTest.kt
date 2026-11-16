package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-07 step 2: opt-in screenshots (tag `screenshot`, `task test:screenshots`) of the Stream step and the pair, for the owner's by-eye review against
 * `docs/ux/Planner.dc.html` (section 4 "Stream", steps 8 and 9, the dotted link of the review graph) and the new wording (the neutral note, the
 * tick, "Response stream (DLN) of ..."). Written to `ui/build/screenshots/planner-stream-*.png`. Asserts nothing about the picture.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class PlannerStreamScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.capture(name: String, dialog: Boolean = false) {
        waitForIdle()
        val roots = onAllNodes(isRoot())
        val image = (if (dialog) roots[1] else onRoot()).captureToImage().toAwtImage()
        outDir.mkdirs()
        check(ImageIO.write(image, "png", File(outDir, "planner-stream-$name.png"))) { "could not write planner-stream-$name.png" }
    }

    private fun shot(name: String, rig: UiRig, dialog: Boolean = false, width: Int = 1280, height: Int = 800, act: DesktopComposeUiTest.(UiRig) -> Unit = {}) =
        runDesktopComposeUiTest(width, height) {
            showPlanner(rig)
            act(rig)
            capture(name, dialog)
            rig.close()
        }

    private fun DesktopComposeUiTest.toStreamForm() {
        node("planner:addStepBtn").performClick(); node("planWizard:kind:Stream").performClick(); node("planWizard:next").performClick()
    }

    @Test fun `01 wizard page 1 with Stream`() = shot("01-kinds", UiRig(), dialog = true) { node("planner:addStepBtn").performClick() }

    @Test fun `02 stream form, tick off`() = shot("02-form-off", UiRig(), dialog = true) { toStreamForm(); node("planForm:name").performTextInput("telemetry") }

    @Test fun `03 stream form, tick on with the note`() = shot("03-form-on", UiRig(), dialog = true) {
        toStreamForm(); node("planForm:name").performTextInput("telemetry"); node("planForm:responseTick").performClick()
    }

    @Test fun `04 stream form, own response name with a hint`() = shot("04-form-hint", UiRig(), dialog = true) {
        toStreamForm(); node("planForm:name").performTextInput("telemetry"); node("planForm:responseTick").performClick()
        node("planForm:responseName").performTextReplacement("Telemetry_Replies")
    }

    @Test fun `05 summary of the pair`() = shot("05-summary", UiRig(), dialog = true) {
        toStreamForm(); node("planForm:name").performTextInput("telemetry"); node("planForm:responseTick").performClick()
        node("planWizard:next").performClick()
    }

    @Test fun `06 the pair in the plan and the dotted link in the graph`() =
        shot("06-pair", UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { queue("jobs"); stream("telemetry", withResponse = true) })

    @Test fun `07 the pair in a wide window, list beside the graph`() =
        shot("07-pair-wide", UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { stream("telemetry", withResponse = true) }, width = 1600, height = 1000)
}
