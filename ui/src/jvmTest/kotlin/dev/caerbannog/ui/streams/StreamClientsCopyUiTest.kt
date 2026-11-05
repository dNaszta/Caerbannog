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
 * M2-06b step 2, UI1 (brief item 12): every value in both tables copies EXACTLY AS DISPLAYED, by mouse selection plus the platform
 * copy key and by focusing the cell and pressing it (same mechanism as the list, see [StreamsCopyUiTest]; the fake `LocalClipboard`
 * needs no display). Pinned: a reference, a connection, a user, an offset `47950` (no separator), a grouped count `48,100`, a status
 * text. Cell tags: see [StreamScreenUiTest].
 */
@OptIn(ExperimentalTestApi::class)
class StreamClientsCopyUiTest {

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun DesktopComposeUiTest.pressCopy() {
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()
    }

    private fun copied(select: (FakeClipboard) -> Unit = {}, cell: DesktopComposeUiTest.() -> SemanticsNodeInteraction, gesture: DesktopComposeUiTest.(SemanticsNodeInteraction) -> Unit): String? {
        var result: String? = null
        runDesktopComposeUiTest(1280, 800) {
            val clipboard = FakeClipboard()
            clientsScreen(content(), wrap = { c -> CompositionLocalProvider(LocalClipboard provides clipboard) { c() } })
            gesture(cell())
            pressCopy()
            result = clipboard.text
        }
        return result
    }

    private val drag: DesktopComposeUiTest.(SemanticsNodeInteraction) -> Unit = { cell ->
        cell.performMouseInput {
            val middle = visibleSize.height / 2f
            moveTo(Offset(1f, middle)); press(); moveTo(Offset(visibleSize.width - 1f, middle)); release()
        }
    }
    private val focus: DesktopComposeUiTest.(SemanticsNodeInteraction) -> Unit = { it.requestFocus() }

    // --- mouse selection, then the copy key ---------------------------------------------------------------------------------

    @Test
    fun `selecting a consumer offset copies plain digits 47950`() = assertEquals("47950", copied(cell = { consumerCell("analytics", "Offset") }, gesture = drag))

    @Test
    fun `selecting a publisher count copies it as displayed, 48,100 with its comma`() = assertEquals("48,100", copied(cell = { publisherCell(0, "Published") }, gesture = drag))

    @Test
    fun `selecting a publisher reference copies the whole reference, hyphen included`() = assertEquals("checkout-1", copied(cell = { publisherCell(0, "Reference") }, gesture = drag))

    @Test
    fun `selecting a connection copies the socket pair as displayed`() {
        assertEquals("10.0.4.21:51234 -> 10.0.0.5:5551", copied(cell = { publisherCell(0, "Connection") }, gesture = drag))
        assertEquals("10.0.9.3:52901 -> 10.0.0.5:5551", copied(cell = { consumerCell("analytics", "Connection") }, gesture = drag))
    }

    @Test
    fun `selecting a user copies the user, and a status copies its text`() {
        assertEquals("checkout", copied(cell = { publisherCell(0, "User") }, gesture = drag))
        assertEquals("349 behind", copied(cell = { consumerCell("analytics", "Status") }, gesture = drag))
    }

    @Test
    fun `selecting a consumer name copies the whole name`() = assertEquals("analytics", copied(cell = { consumerCell("analytics", "Name") }, gesture = drag))

    // --- keyboard: a focused cell copies with the platform shortcut ---------------------------------------------------------

    @Test
    fun `a focused consumer offset cell copies 47950 with the platform copy shortcut`() = assertEquals("47950", copied(cell = { consumerCell("analytics", "Offset") }, gesture = focus))

    @Test
    fun `a focused publisher count cell copies 48,100`() = assertEquals("48,100", copied(cell = { publisherCell(0, "Published") }, gesture = focus))

    @Test
    fun `a focused reference cell copies the reference`() = assertEquals("checkout-1", copied(cell = { publisherCell(0, "Reference") }, gesture = focus))

    @Test
    fun `a focused connection cell copies the socket pair, and a focused behind cell copies the broker's number`() {
        assertEquals("10.0.5.14:38812 -> 10.0.0.5:5551", copied(cell = { consumerCell("billing", "Connection") }, gesture = focus))
        assertEquals("349", copied(cell = { consumerCell("analytics", "Behind") }, gesture = focus))
    }

    @Test
    fun `a focused status cell copies its text`() = assertEquals("199 behind", copied(cell = { consumerCell("audit", "Status") }, gesture = focus))
}
