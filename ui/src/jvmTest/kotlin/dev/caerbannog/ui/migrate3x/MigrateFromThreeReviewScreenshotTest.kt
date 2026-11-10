package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.NOT_DURABLE_GROUP
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.ORDERS_GROUP
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M8-06b: opt-in screenshots (tag `screenshot`, `./gradlew :ui:screenshotTest`) of the sheets and states 6 to 11 of `docs/ux/Migrate3x.dc.html`, in
 * `ui/build/screenshots/`: `migrate3x-20-state6-accept-tick`, `-21-state7-manual`, `-22-state8-replace`, `-23-state8-replace-error`, `-24-state9-decided`,
 * `-25-state10-result`, `-26-state11-folder-problem`, `-27-second-generation`, `-28-partial-save`, `-29-sheet-400dp`. Look at them by eye next
 * to the mockup (no pixel comparison); each test asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeReviewScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val tickTag = "migrate3x-tick-${Acknowledgement.OrderingByPriorityMayChange.name}"

    private fun DesktopComposeUiTest.shot(fileName: String) {
        waitForIdle()
        val image = onAllNodes(isRoot(), useUnmergedTree = true)[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test fun `state 6 - accept with the tick`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three); showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); shot("migrate3x-20-state6-accept-tick.png")
    }

    @Test fun `state 7 - manual fix`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.mixed); showLive(rig)
        clickReview(rig, NOT_DURABLE_GROUP, "temp"); shot("migrate3x-21-state7-manual.png")
    }

    @Test fun `state 8 - replace and its error`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders); showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace"); shot("migrate3x-22-state8-replace.png")
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("""[{"action":"deleteQueue","name":"orders"}]""")
        click("migrate3x-replace-save"); shot("migrate3x-23-state8-replace-error.png")
    }

    @Test fun `state 9 - decided rows, the Skipped group and the bar`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three); showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept")
        clickReview(rig, ORDERS_GROUP, "invoices"); click("migrate3x-skip")
        openGroupAndFind(rig, ReviewScreenFiles.ORDERS_GROUP, "jobs")
        shot("migrate3x-24-state9-decided.png")
    }

    @Test fun `state 10 - result`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").addMigration("0001"); it.folder("/migrations").addMigration("0002") }
        val rig = LiveRig(ReviewScreenFiles.mixed, folders); showLive(rig)
        for (name in listOf("orders", "prio")) { clickReview(rig, ORDERS_GROUP, name); if (nodes(tickTag).isNotEmpty()) click(tickTag); click("migrate3x-accept") }
        clickReview(rig, NOT_DURABLE_GROUP, "temp"); click("migrate3x-skip")
        click("migrate3x-generate"); shot("migrate3x-25-state10-result.png")
    }

    @Test fun `state 11 - folder problem, second generation, partial save`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").readOnly = true }
        val rig = LiveRig(ReviewScreenFiles.orders, folders); showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        shot("migrate3x-26-state11-folder-problem.png")
    }

    @Test fun `second generation question`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders); showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate"); click("migrate3x-generate")
        shot("migrate3x-27-second-generation.png")
    }

    @Test fun `partial save`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").failWrite(2, "the file could not be written") }
        val rig = LiveRig(ReviewScreenFiles.twoVhosts, folders); showLive(rig)
        listOf("orders", "billing").forEach { clickReview(rig, ORDERS_GROUP, it); click(tickTag); click("migrate3x-accept") }
        click("migrate3x-generate"); shot("migrate3x-28-partial-save.png")
    }

    @Test fun `sheet at 400 dp`() = runDesktopComposeUiTest(400, 800) {
        val rig = LiveRig(ReviewScreenFiles.mixed); showLive(rig)
        clickReview(rig, NOT_DURABLE_GROUP, "temp"); shot("migrate3x-29-sheet-400dp.png")
    }
}
