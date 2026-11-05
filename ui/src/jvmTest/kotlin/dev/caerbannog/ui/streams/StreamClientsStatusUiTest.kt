package dev.caerbannog.ui.streams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import dev.caerbannog.core.streams.ConsumerStatus
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * M2-06 follow-ups F-F (lag rule on the screen), F-B (the Behind hint on keyboard focus) and F-D (guard: no copied literals).
 *
 * F-B contract (pinned by the test developer, the architect's "inline popup the keyboard can open"):
 *  - every consumer row whose status carries a hint has a focusable "i" node `statusHint:<ConsumerRowState.key>` (Tab reaches it);
 *    a Caught up, Not active or settling row has none;
 *  - while that node has the focus the node `statusHintPopup:<key>` exists and its text is the stored-offsets hint; when the focus
 *    is elsewhere the popup does not exist (so `onRoot()` and the copy tests stay unambiguous: the popup is inline, or a second
 *    root is not left behind);
 *  - the status cell keeps its state description (screen readers), unchanged.
 */
@OptIn(ExperimentalTestApi::class)
class StreamClientsStatusUiTest {

    // --- F-F: the screen never contradicts the lag rule ---------------------------------------------------------------------

    @Test
    fun `no row shows behind next to Caught up or Caught up next to behind, whichever way the lag is`() = runDesktopComposeUiTest(1280, 800) {
        val lags = listOf(0L, 1L, 2L, 349L, 0L)
        clientsScreen(
            content(
                consumers = lags.mapIndexed { i, lag ->
                    consumer("k$i", behind = if (lag == 0L) "0" else lag.toString(), status = if (lag == 0L) ConsumerStatus.CaughtUp else ConsumerStatus.Behind(lag))
                },
            ),
        )
        lags.forEachIndexed { i, lag ->
            val status = consumerCell("k$i", "Status")
            if (lag == 0L) status.assertTextEquals("Caught up") else status.assertTextEquals("$lag behind")
        }
        onAllNodes(hasText("Caught up"), useUnmergedTree = true).assertCountEquals(2)
        onAllNodes(hasText(" behind", substring = true).and(hasTestTag("consumerCell:k0:Status")), useUnmergedTree = true).assertCountEquals(0)
        onAllNodes(hasText(" behind", substring = true).and(hasTestTag("consumerCell:k4:Status")), useUnmergedTree = true).assertCountEquals(0)
    }

    // --- F-B: the hint on keyboard focus --------------------------------------------------------------------------------------

    @Test
    fun `a Behind row has a focusable hint control, a Caught up row has none`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())
        onNodeWithTag("statusHint:analytics", useUnmergedTree = true).assertExists("Behind row analytics has the hint control")
        onNodeWithTag("statusHint:audit", useUnmergedTree = true).assertExists()
        onNodeWithTag("statusHint:billing", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the hint text shows while the hint control has keyboard focus and not otherwise`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())
        onNodeWithTag("statusHintPopup:analytics", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag("statusHint:analytics", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("statusHint:analytics", useUnmergedTree = true).assertIsFocused()
        onNodeWithTag("statusHintPopup:analytics", useUnmergedTree = true).assertTextEquals(HINT_TEXT)
        onNodeWithTag("statusHintPopup:audit", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag("statusHint:audit", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("statusHint:analytics", useUnmergedTree = true).assertIsNotFocused()
        onNodeWithTag("statusHintPopup:analytics", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("statusHintPopup:audit", useUnmergedTree = true).assertTextEquals(HINT_TEXT)
    }

    @Test
    fun `the Escape key closes the hint while the control keeps the focus`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())
        onNodeWithTag("statusHint:audit", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("statusHintPopup:audit", useUnmergedTree = true).assertExists()
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        waitForIdle()
        onNodeWithTag("statusHintPopup:audit", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `with the hint open the root is still unambiguous and the status cell keeps its state description`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())
        onNodeWithTag("statusHint:analytics", useUnmergedTree = true).requestFocus()
        waitForIdle()

        onRoot().assertExists("onRoot() must name exactly one root while the hint is open")
        assertEquals(HINT_TEXT, consumerCell("analytics", "Status").stateDescription())
        assertNull(consumerCell("billing", "Status").stateDescription())
        consumerCell("analytics", "Status").assertTextEquals("349 behind")
    }

    // --- F-D guard: the literals moved out of the UI ------------------------------------------------------------------------

    @Test
    fun `the stream screen and the list hold none of the literals that moved into StreamClientsTexts`() {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/ui/streams")
        val screen = File(dir, "StreamScreen.kt").readText()
        val list = File(dir, "StreamsScreen.kt").readText()
        val moved = listOf("\"Refresh\"", "\"Publishers\"", "\"Consumers\"", "\"no user\"", "\"Connection (user)\"", "\"Messages behind\"")
        // The column keys ("Reference", "Status", ...) are tag keys that equal some headers, so only unambiguous headers are guarded.
        moved.forEach { assertFalse(screen.contains(it), "StreamScreen.kt still holds the literal $it; use StreamClientsTexts") }
        assertFalse(list.contains("\"Refresh\""), "StreamsScreen.kt still holds the literal \"Refresh\"")
        listOf("StreamScreen.kt" to screen, "StreamsScreen.kt" to list).forEach { (name, text) ->
            assertFalse(text.contains("Updated \$"), "$name still builds 'Updated N s ago' itself; use the shared UpdatedAge formatter")
        }
    }
}
