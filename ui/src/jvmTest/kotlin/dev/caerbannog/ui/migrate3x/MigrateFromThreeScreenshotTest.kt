package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.ExportNotice
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M8-06a round 1: opt-in screenshot helper (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye check against `docs/ux/Migrate3x.dc.html`,
 * files in `ui/build/screenshots/`: `migrate3x-01-empty`, `-02-loading`, `-03-rejected`, `-04-vhost`, `-05-vhost-error`, `-06-report` (mixed file, groups
 * closed), `-07-report-open` (all groups open), `-08-saved`. The files use the M8-01 sentinels: look for "SENTINEL" in them by eye (there must be none).
 * No pixel comparison; each test asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.shot(state: MigrateFromThreeState, fileName: String) {
        show(state)
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test fun `screenshot - empty`() = runDesktopComposeUiTest(1280, 800) { shot(MigrateFromThreeState.ChooseSource, "migrate3x-01-empty.png") }
    @Test fun `screenshot - loading`() = runDesktopComposeUiTest(1280, 800) { shot(MigrateFromThreeState.Loading("legacy-eu-definitions.json"), "migrate3x-02-loading.png") }
    @Test fun `screenshot - rejected`() = runDesktopComposeUiTest(1280, 800) { shot(MigrateFromThreeState.Rejected("notes.json", "The file is not a definitions file"), "migrate3x-03-rejected.png") }
    @Test fun `screenshot - virtual host prompt`() = runDesktopComposeUiTest(1280, 800) { shot(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null), "migrate3x-04-vhost.png") }
    @Test fun `screenshot - virtual host error`() = runDesktopComposeUiTest(1280, 800) { shot(MigrateFromThreeState.NeedsVirtualHost("vhost.json", "The virtual host name cannot contain a tab"), "migrate3x-05-vhost-error.png") }
    @Test fun `screenshot - report`() = runDesktopComposeUiTest(1280, 880) { shot(MigrateScreenFiles.report(MigrateScreenFiles.withUser), "migrate3x-06-report.png") }
    @Test fun `screenshot - report with every group open`() = runDesktopComposeUiTest(1280, 880) { shot(MigrateScreenFiles.report(MigrateScreenFiles.withUser, open = MigrateScreenFiles::allGroups), "migrate3x-07-report-open.png") }
    @Test fun `screenshot - saved notice`() = runDesktopComposeUiTest(1280, 880) { shot(MigrateScreenFiles.report(notice = ExportNotice("Report saved to /home/me/report.json", failed = false)), "migrate3x-08-saved.png") }
    @Test fun `screenshot - narrow window`() = runDesktopComposeUiTest(520, 700) { shot(MigrateScreenFiles.report(MigrateScreenFiles.withUser), "migrate3x-09-narrow.png") }
    @Test fun `screenshot - 400 dp with a long file name`() = runDesktopComposeUiTest(400, 700) { shot(MigrateScreenFiles.report(MigrateScreenFiles.withUser, label = "definitions-" + "very-long-".repeat(20) + "export.json"), "migrate3x-10-narrow-400-long-name.png") }
}
