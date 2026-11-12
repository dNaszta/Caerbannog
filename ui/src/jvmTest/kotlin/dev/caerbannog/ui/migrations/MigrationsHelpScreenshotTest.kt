package dev.caerbannog.ui.migrations

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.ApplyOutcome
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** M8-14b: opt-in screenshots (tag `screenshot`) of the Migrations screen with its guidance, at 1024x768 and 1280x900, in `ui/build/screenshots/migrations-help-*.png`. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrationsHelpScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.shot(name: String, outcome: ApplyOutcome?) {
        val f = MigrationFile("0003", "Desc", listOf(MigrationAction.DeclareQueue("orders", "quorum")), """{ "version": "0003" }""", "abc")
        val state = MigrationsScreenState(
            entries = listOf(MigrationEntry(f, MigrationStatus.Pending), MigrationEntry(f.copy(version = "0002"), MigrationStatus.Modified("a", "b"))),
            selectedVersion = "0003", selectedFile = f,
            dryRun = MigrationDryRun("0003", f.up.map { ActionDryRun(it, allowed = true) }, canApply = true), isApplyPendingEnabled = true, profileName = "uat-eu",
            applyOutcome = outcome,
        )
        setContent {
            MigrationsScreen(state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {}, onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {})
        }
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        outDir.mkdirs()
        check(ImageIO.write(image, "png", File(outDir, "migrations-help-$name.png")))
    }

    @Test fun `screenshot - 1024x768`() = runDesktopComposeUiTest(1024, 768) { shot("1024x768", null) }
    @Test fun `screenshot - conflict at 1280x900`() = runDesktopComposeUiTest(1280, 900) { shot("conflict-1280x900", ApplyOutcome.Conflict("0003", 1, 1, "queue exists with other arguments", "orders")) }
}
