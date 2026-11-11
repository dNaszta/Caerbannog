package dev.caerbannog.ui.components

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.help.HowTo
import dev.caerbannog.core.help.HowToTexts
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** M8-14b: opt-in screenshots (tag `screenshot`) of the How to dialog of each how-to, in `ui/build/screenshots/howto-<id>.png`, for a by-eye check that the dialog fits. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class HowToDialogScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.shot(howTo: HowTo) {
        setContent { HowToDialog(howTo, onClose = {}) }
        waitForIdle()
        val image = onNodeWithTag("howto-dialog-${howTo.id}", useUnmergedTree = true).captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, "howto-${howTo.id}.png")
        check(ImageIO.write(image, "png", file)) { "no PNG writer for ${file.name}" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test fun `screenshot - analyse and generate`() = runDesktopComposeUiTest(1280, 900) { shot(HowToTexts.ANALYSE_AND_GENERATE) }
    @Test fun `screenshot - apply a migration`() = runDesktopComposeUiTest(1280, 900) { shot(HowToTexts.APPLY_MIGRATIONS) }
    @Test fun `screenshot - move the messages of a queue`() = runDesktopComposeUiTest(1280, 900) { shot(HowToTexts.MOVE_MESSAGES) }
    @Test fun `screenshot - narrow window`() = runDesktopComposeUiTest(480, 700) { shot(HowToTexts.MOVE_MESSAGES) }
}
