package dev.caerbannog.ui.streams

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals

/**
 * M2-05c step 2, UI1 (copyable values, brief item 13): COPY YIELDS EXACTLY THE DISPLAYED TEXT (retention `7D`, an offset without
 * separator, a count WITH its comma, the word `unavailable`). The clipboard is a fake `LocalClipboard`, so the test needs no
 * display and never touches the real clipboard.
 *
 * Spike result (test developer, 2026-11-05, recorded in the brief's Status): a Material `Text` inside a `SelectionContainer`
 * supports mouse selection and the platform copy key (Cmd+C on macOS, Ctrl+C elsewhere) after a drag, and the copy gives the whole
 * text of the cell even for `orders-events` (not just a word). `SelectionContainer` ALONE gives no keyboard copy for a focused
 * cell: a Text is not focusable (checked: RequestFocus is not offered, nothing reaches the clipboard). So the cell needs a
 * `Modifier` that makes it focusable and handles the copy shortcut itself; the second group of tests below pins it.
 *
 * Contract: the node tagged `cell:<stream>:<Column>` is the cell's text node; the mouse tests drag across it, the keyboard tests
 * request focus on that same node (so it must be focusable) and press the copy shortcut.
 */
@OptIn(ExperimentalTestApi::class)
class StreamsCopyUiTest {

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val rows = listOf(
        row("orders-events", messages = "1,204,000", first = "880000", last = "2083999", retention = "7D", publishers = "unavailable"),
    )

    private fun DesktopComposeUiTest.shownWith(clipboard: FakeClipboard) {
        screen(rowsState(rows), wrap = { content -> CompositionLocalProvider(LocalClipboard provides clipboard) { content() } })
    }

    private fun DesktopComposeUiTest.pressCopy() {
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.cell(column: String): SemanticsNodeInteraction = onNodeWithTag("cell:orders-events:$column", useUnmergedTree = true)

    private fun DesktopComposeUiTest.selectByDragging(cell: SemanticsNodeInteraction) {
        cell.performMouseInput {
            val middle = visibleSize.height / 2f
            moveTo(Offset(1f, middle)); press(); moveTo(Offset(visibleSize.width - 1f, middle)); release()
        }
    }

    // --- mouse selection, then the copy shortcut -------------------------------------------------------------------------

    private fun copiedByMouse(column: String): String? {
        var result: String? = null
        runDesktopComposeUiTest(1280, 800) {
            val clipboard = FakeClipboard()
            shownWith(clipboard)
            selectByDragging(cell(column))
            pressCopy()
            result = clipboard.text
        }
        return result
    }

    @Test
    fun `selecting the retention cell and copying gives 7D, not seconds`() = assertEquals("7D", copiedByMouse("Retention"))

    @Test
    fun `selecting a name cell and copying gives the whole name, hyphen included`() = assertEquals("orders-events", copiedByMouse("Name"))

    @Test
    fun `selecting an offset cell and copying gives plain digits, no separator`() {
        assertEquals("880000", copiedByMouse("FirstOffset"))
        assertEquals("2083999", copiedByMouse("LastOffset"))
    }

    @Test
    fun `selecting a count cell and copying gives it as displayed, with its comma`() = assertEquals("1,204,000", copiedByMouse("Messages"))

    @Test
    fun `selecting an unavailable cell copies the word that is shown`() = assertEquals("unavailable", copiedByMouse("Publishers"))

    // --- keyboard: a focused cell copies with the platform shortcut ---------------------------------------------------------

    private fun copiedByKeyboard(column: String): String? {
        var result: String? = null
        runDesktopComposeUiTest(1280, 800) {
            val clipboard = FakeClipboard()
            shownWith(clipboard)
            cell(column).requestFocus()
            pressCopy()
            result = clipboard.text
        }
        return result
    }

    @Test
    fun `a focused retention cell copies 7D with the platform copy shortcut`() = assertEquals("7D", copiedByKeyboard("Retention"))

    @Test
    fun `a focused name cell copies the full name`() = assertEquals("orders-events", copiedByKeyboard("Name"))

    @Test
    fun `a focused offset cell copies plain digits`() = assertEquals("2083999", copiedByKeyboard("LastOffset"))
}
