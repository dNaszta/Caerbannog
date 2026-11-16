package dev.caerbannog.ui.migrations

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerTexts
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** M9-03 step 2: opt-in screenshots (tag `screenshot`) of the Migrations screen entry (mockup section 1) and the saved notice, in `ui/build/screenshots/migrations-entry-*.png`. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrationsEntryScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val file = MigrationFile("0005", "Plan", listOf(MigrationAction.DeclareQueue("orders")), """{ "version": "0005" }""", "abc")

    private fun shot(name: String, entry: PlannerEntry, notice: String? = null, width: Int = 1280, height: Int = 800) = runDesktopComposeUiTest(width, height) {
        val state = MigrationsScreenState(entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = "0005", selectedFile = file, profileName = "uat-eu", isApplyPendingEnabled = true)
        setContent {
            MigrationsScreen(
                state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {}, plannerEntry = entry, plannerNotice = notice,
            )
        }
        waitForIdle()
        outDir.mkdirs()
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "migrations-entry-$name.png")))
    }

    @Test fun `new plan`() = shot("new-plan", PlannerEntry.NewPlan)
    @Test fun `continue plan with the draft card`() = shot("continue", PlannerEntry.ContinuePlan(10, "Orders with dead-lettering and a telemetry route"))
    @Test fun `unreadable draft`() = shot("unreadable", PlannerEntry.DraftUnreadable("/home/u/.caerbannog/planner-draft.json"))
    @Test fun `saved notice`() = shot("saved", PlannerEntry.NewPlan, notice = PlannerTexts.planSaved("0005"))
    @Test fun `continue plan at 1024 px`() = shot("continue-1024", PlannerEntry.ContinuePlan(10, "Orders"), width = 1024, height = 768)
}
