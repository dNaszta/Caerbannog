package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.PlannerContext
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-06 step 2: opt-in screenshots (tag `screenshot`, `task test:screenshots`) of the check on the planner screen, for the owner's by-eye review against
 * `docs/ux/Planner.dc.html` section 9 and the wording list of M9-05 (items 1 to 10, `docs/tasks/M9-05-check-core-and-data.md`). Written to
 * `ui/build/screenshots/planner-check-*.png`. Asserts nothing about the picture; it fails only when a state cannot be drawn or written.
 * 1600 x 1000 for the states with seven rows (side by side, every row visible); the two layout shots show 1280 (stacked) and 1600.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class PlannerCheckScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.capture(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        outDir.mkdirs()
        check(ImageIO.write(image, "png", File(outDir, "planner-check-$name.png"))) { "could not write planner-check-$name.png" }
    }

    private fun shot(name: String, rig: UiRig, width: Int = 1600, height: Int = 1000, act: DesktopComposeUiTest.(UiRig) -> Unit = {}) =
        runDesktopComposeUiTest(width, height) {
            showPlanner(rig)
            act(rig)
            capture(name)
            rig.close()
        }

    private fun sevenResults(port: FakeDeclareCheck, level: TopologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT): UiRig {
        port.answerNamed("invoices", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Different)
            .answerNamed("evnts", BrokerFinding.Absent).answerNamed("telemetry", BrokerFinding.Unreadable("The broker did not answer."))
            .answerNamed("legacy-orders", BrokerFinding.Same).answerNamed("gone-orders", BrokerFinding.Absent)
        return UiRig(context = PlannerContext(controlLevel = level), declareCheck = port) {
            store.putDraft(
                "",
                listOf(
                    MigrationAction.DeclareQueue("invoices"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareQueue("orders"),
                    MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created"), MigrationAction.DeclareStream("telemetry"),
                    MigrationAction.DeleteQueue("legacy-orders"), MigrationAction.DeleteQueue("gone-orders"),
                ),
            )
        }
    }

    private fun threeStepRig(port: FakeDeclareCheck): UiRig = UiRig(declareCheck = port) { threeSteps() }.also { rig ->
        rig.state.plan.steps.forEach { step ->
            when (val a = step.action) {
                is MigrationAction.DeclareQueue -> port.answerNamed(a.name, BrokerFinding.Absent)
                is MigrationAction.DeclareExchange -> port.answerNamed(a.name, BrokerFinding.Same)
                else -> port.answer(a, BrokerFinding.Absent)
            }
        }
    }

    @Test fun `01 every result of section 9`() = shot("01-results", sevenResults(FakeDeclareCheck())) { node("planner:checkBtn").performClick() }

    @Test fun `02 progress at step 2 of 3`() {
        val port = FakeDeclareCheck()
        val gate = port.holdOn("events")
        shot("02-progress", threeStepRig(port)) { node("planner:checkBtn").performClick() }
        gate.release()
    }

    @Test fun `03 stale after a change`() = shot("03-stale", threeStepRig(FakeDeclareCheck())) { rig ->
        node("planner:checkBtn").performClick(); rig.queue("invoices")
    }

    @Test fun `04 before the check, the button names the connection`() = shot("04-before", threeStepRig(FakeDeclareCheck()))

    @Test fun `05 no connection, the button says Check`() = shot("05-not-connected", UiRig(context = PlannerContext(connectionName = null)) { threeSteps() })

    @Test fun `06 read-only level, blocked with the level's reason`() =
        shot("06-read-only", sevenResults(FakeDeclareCheck(), TopologyControlLevel.READ_ONLY)) { node("planner:checkBtn").performClick() }

    @Test fun `07 layout at 1280, the card stacks`() = shot("07-layout-1280", threeStepRig(FakeDeclareCheck()), width = 1280, height = 1000) { node("planner:checkBtn").performClick() }
}
