package dev.caerbannog.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.help.HowToTexts
import kotlin.test.Test
import kotlin.test.assertTrue

/** M8-14b review: a dialog opened from a link inside the right-to-left top line is laid out left to right, like every other dialog. */
@OptIn(ExperimentalTestApi::class)
class HowToDialogDirectionUiTest {
    @Test
    fun `a dialog opened from the top line link is not mirrored`() = runDesktopComposeUiTest(1280, 900) {
        val howTo = HowToTexts.APPLY_MIGRATIONS
        setContent { TopLineWithHowTo(link = { HowToLink(howTo) }) { } }
        onNodeWithTag("howto-${howTo.id}", useUnmergedTree = true).performClick(); waitForIdle()
        val dialog = onNodeWithTag("howto-dialog-${howTo.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val close = onNodeWithTag("howto-close", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(close.left - dialog.left < 100.0f.let { androidx.compose.ui.unit.Dp(it) }, "the Close button should sit at the start (left) of the dialog: dialog $dialog, close $close")
    }
}
