package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.planner.PlannerContext
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-03 step 2: opt-in screenshots (tag `screenshot`, `task test:screenshots`) of the planner for the owner's by-eye review against
 * `docs/ux/Planner.dc.html` and its wording list (brief, "Mockup review list"). One PNG per mockup section built here, 1280 x 800, in
 * `ui/build/screenshots/planner-*.png`. It asserts nothing about the picture; it fails only when the screen cannot be drawn or the file
 * cannot be written. The Migrations screen entry is in `migrations-entry-*.png` (written by the last tests).
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class PlannerScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val reason3x = "This broker runs RabbitMQ 3.x: it is a migration source only"

    private fun DesktopComposeUiTest.capture(name: String, dialog: Boolean = false) {
        waitForIdle()
        val roots = onAllNodes(isRoot())
        val image = (if (dialog) roots[1] else onRoot()).captureToImage().toAwtImage()
        outDir.mkdirs()
        check(ImageIO.write(image, "png", File(outDir, "planner-$name.png"))) { "could not write planner-$name.png" }
    }

    private fun shot(name: String, rig: UiRig, dialog: Boolean = false, act: DesktopComposeUiTest.(UiRig) -> Unit = {}) =
        runDesktopComposeUiTest(1280, 800) {
            showPlanner(rig)
            act(rig)
            capture(name, dialog)
            rig.close()
        }

    @Test fun `01 plan with steps`() = shot("01-plan", UiRig { threeSteps(); vm.setDescription("Orders with an events exchange") })
    @Test fun `02 empty plan`() = shot("02-empty", UiRig())
    @Test fun `03 wizard page 1`() = shot("03-wizard-kind", UiRig(), dialog = true) { node("planner:addStepBtn").performClick() }
    @Test fun `04a queue form with a hint`() = shot("04a-queue", UiRig(), dialog = true) {
        node("planner:addStepBtn").performClick(); node("planWizard:next").performClick(); node("planForm:name").performTextInput("Orders_EU")
    }
    @Test fun `04b exchange form`() = shot("04b-exchange", UiRig(), dialog = true) {
        node("planner:addStepBtn").performClick(); node("planWizard:kind:Exchange").performClick(); node("planWizard:next").performClick()
        node("planForm:name").performTextInput("events")
    }
    @Test fun `04c binding form`() = shot("04c-binding", UiRig { queue("orders"); exchange("events") }, dialog = true) {
        node("planner:addStepBtn").performClick(); node("planWizard:kind:Binding").performClick(); node("planWizard:next").performClick()
        node("planForm:exchange").performTextInput("events")
    }
    @Test fun `05 summary`() = shot("05-summary", UiRig(), dialog = true) {
        node("planner:addStepBtn").performClick(); node("planWizard:next").performClick(); node("planForm:name").performTextInput("orders")
        node("planWizard:next").performClick()
    }
    @Test fun `06 naming menu`() = shot("06-naming", UiRig { queue("orders") }) { node("planner:namingChip").performClick() }
    @Test fun `07 problem and note in the step list`() = shot("07-problems", UiRig { binding("events", "orders", "k"); queue("orders"); queue("orders"); exchange("events") })
    @Test fun `10 save failed`() = shot("10-save-failed", UiRig { queue("orders"); folder.failWrite(1) }) { node("planner:saveBtn").performClick() }
    @Test fun `11 dismiss question`() = shot("11-dismiss", UiRig { threeSteps() }, dialog = true) { node("planner:dismissBtn").performClick() }
    @Test fun `12a 3x`() = shot("12a-3x", UiRig(context = PlannerContext(unavailableReason = reason3x)))
    @Test fun `12b unreadable draft`() = shot("12b-unreadable", UiRig { store.putUnreadable() })
    @Test fun `12b start a new plan question`() = shot("12b-start-new", UiRig { store.putUnreadable() }, dialog = true) { node("planner:startNewPlanBtn").performClick() }
    @Test fun `12c read only level`() = shot("12c-read-only", UiRig(context = PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY)) { queue("orders") })
    @Test fun `12d no configure permission`() = shot("12d-no-permission", UiRig(context = PlannerContext(hasConfigurePermission = false, connectionName = "prod-eu")) { queue("orders") })
    @Test fun `12e not connected`() = shot("12e-not-connected", UiRig(context = PlannerContext(connectionName = null)) { queue("orders") })
    @Test fun `12f no folder`() = shot("12f-no-folder", UiRig(folderPath = null) { queue("orders") })
    @Test fun `12f read only folder`() = shot("12f-read-only-folder", UiRig { queue("orders"); folder.readOnly = true }.also { it.folders.store.folder = FOLDER })
    @Test fun `12g another virtual host`() = shot("12g-vhost", UiRig { vm.setVirtualHost("/orders"); queue("orders") })
    @Test fun `window of 1024 px`() = runDesktopComposeUiTest(1024, 768) {
        val rig = UiRig { threeSteps() }
        showPlanner(rig)
        capture("narrow-1024")
        rig.close()
    }
}
