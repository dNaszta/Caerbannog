package dev.caerbannog.ui.streams

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.CreateStreamGate
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * M2-06b step 2, brief item 11 (opening a stream from the list). SPIKE RESULT (test developer, 2026-11-05): the name cell CAN be a link
 * (`Modifier.clickable(role = Role.Button)` on the SAME text node that is inside the table's `SelectionContainer` and has the copy key
 * handler) and still be selected by a mouse drag, copied with the platform key (drag, or focus), clicked, and opened with Enter; a drag
 * does not open. So no "Open <stream>" fallback button is built and none is allowed to replace the link here.
 *
 * Contract: `StreamsScreen(..., onOpenStream: (String) -> Unit)` is called with the stream NAME by a click or Enter on the node tagged
 * `cell:<stream>:Name` (the same node the copy tests use); that node has role Button. No other cell opens anything.
 */
@OptIn(ExperimentalTestApi::class)
class StreamsOpenLinkUiTest {

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val rows = listOf(row("orders-events"), row("audit-log", messages = "1,204,000"))

    private fun DesktopComposeUiTest.show(opened: MutableList<String>, clipboard: FakeClipboard = FakeClipboard()) {
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                StreamsScreen(rowsState(rows), 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents, onOpenStream = { opened += it })
            }
        }
    }

    private fun DesktopComposeUiTest.name(stream: String) = onNodeWithTag("cell:$stream:Name", useUnmergedTree = true)

    @Test
    fun `a click on the name cell opens that stream`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        show(opened)

        name("audit-log").performClick()
        waitForIdle()

        assertEquals(listOf("audit-log"), opened, "the stream of the clicked row")
    }

    @Test
    fun `Enter on the focused name cell opens that stream`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        show(opened)

        name("orders-events").requestFocus()
        onRoot().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("orders-events"), opened)
    }

    @Test
    fun `the name cell is a button for screen readers`() = runDesktopComposeUiTest(1280, 800) {
        show(mutableListOf())

        val config = name("orders-events").fetchSemanticsNode().config
        assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role), "role")
        assertNotNull(config.getOrNull(SemanticsActions.OnClick), "click action")
    }

    @Test
    fun `no other cell opens anything`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        show(opened)

        listOf("Type", "Retention", "Messages", "FirstOffset", "LastOffset", "Publishers", "Consumers").forEach {
            onNodeWithTag("cell:orders-events:$it", useUnmergedTree = true).performClick()
        }
        waitForIdle()

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `selecting the name by dragging opens nothing and copying still gives the whole name`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        val clipboard = FakeClipboard()
        show(opened, clipboard)

        name("orders-events").performMouseInput {
            val middle = visibleSize.height / 2f
            moveTo(Offset(1f, middle)); press(); moveTo(Offset(visibleSize.width - 1f, middle)); release()
        }
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()

        assertEquals("orders-events", clipboard.text, "UI1: the whole displayed name")
        assertEquals(emptyList(), opened, "a drag is a selection, not an open")
    }

    @Test
    fun `a focused name cell copies the full name and does not open`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        val clipboard = FakeClipboard()
        show(opened, clipboard)

        name("orders-events").requestFocus()
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()

        assertEquals("orders-events", clipboard.text)
        assertEquals(emptyList(), opened)
    }
}
