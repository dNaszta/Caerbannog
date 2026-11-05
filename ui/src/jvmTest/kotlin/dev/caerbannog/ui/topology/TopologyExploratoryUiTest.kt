package dev.caerbannog.ui.topology

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 4 (test developer): exploratory checks on the built screen, kept as tests. Narrow window (700 dp), very long names, the vhost menu and the
 * detail block inside the window, a state change with the detail open. Tags are those of [TopologyScreenUiTest].
 */
@OptIn(ExperimentalTestApi::class)
class TopologyExploratoryUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.inside(tag: String, width: Int, height: Int) {
        val b = node(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(b.left >= -1f && b.top >= -1f && b.right <= width + 1f && b.bottom <= height + 1f, "$tag at $b is outside the ${width}x$height window")
    }

    @Test
    fun `at 700 dp the tabs, switch, search, chips and footer are all reachable, the table scrolls sideways`() = runDesktopComposeUiTest(700, 800) {
        screen(queuesState())
        listOf("tab:Queues", "tab:Exchanges", "tab:Bindings", "vhostSwitch", "queueSearch", "typeChip:Quorum", "typeChip:OtherTypes", "rangeText", "pageNext").forEach { node(it).assertExists("$it exists at 700 dp") }
        // The table keeps every column (it scrolls sideways) and the Name cell stays visible.
        listOf("Name", "Ready", "Unacked", "Consumers", "Leader", "MembersOnline").forEach { node("columnHeader:$it").assertExists() }
        inside("tab:Exchanges", 700, 800)
        inside("vhostSwitch", 700, 800)
        inside("cell:orders:Name", 700, 800)
    }

    @Test
    fun `at 700 dp the vhost menu is not clipped by the window or the table`() = runDesktopComposeUiTest(700, 800) {
        screen(queuesState(vhosts = listOf("/", "billing", "a-vhost-with-a-rather-long-name-" + "x".repeat(60))))
        node("vhostSwitch").performClick()
        waitForIdle()
        node("vhostMenu").assertExists()
        inside("vhostMenu", 700, 800)
        node("vhostItem:billing").assertExists()
    }

    @Test
    fun `at 700 dp the detail block is fully inside the window`() = runDesktopComposeUiTest(700, 800) {
        screen(queuesState(selected = mockupDetail))
        node("queueDetail").assertExists()
        inside("queueDetail", 700, 800)
        inside("detailClose", 700, 800)
    }

    @Test
    fun `a very long vhost name keeps the switch inside the window and the whole name in its description`() = runDesktopComposeUiTest(700, 800) {
        val long = "v".repeat(240)
        screen(queuesState(vhost = long, vhosts = listOf("/", long)))
        inside("vhostSwitch", 700, 800)
        val description = node("vhostSwitch").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
        assertEquals(listOf("Virtual host $long, change"), description)
    }

    @Test
    fun `a very long queue name does not move the other cells and copies in full`() = runDesktopComposeUiTest(1280, 800) {
        val long = "q-" + "n".repeat(300)
        val clip = FakeClip()
        screen(queuesState(listOf(queue(long), queue("short"))), wrap = { CompositionLocalProvider(LocalClipboard provides clip) { it() } })
        assertEquals(node("cell:short:Ready").fetchSemanticsNode().boundsInRoot.left, node("cell:$long:Ready").fetchSemanticsNode().boundsInRoot.left)
        node("cell:$long:Name").requestFocus()
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()
        assertEquals(long, clip.text, "the copy is the whole name, not the ellipsis")
    }

    @Test
    fun `a state change with the detail open that removes the queue closes the detail block`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState(selected = mockupDetail))
        node("queueDetail").assertExists()
        shown.state = queuesState(rows = mockupQueues.drop(1), selected = null)
        waitForIdle()
        node("queueDetail").assertDoesNotExist()
        node("cell:orders:Name").assertDoesNotExist()
    }

    @Test
    fun `switching vhost with the detail open shows the new vhost without the old detail`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(queuesState(selected = mockupDetail), events)
        node("vhostSwitch").performClick()
        node("vhostItem:billing").performClick()
        assertEquals(listOf("selectVhost:billing"), events.sent())
        shown.state = queuesState(vhost = "billing", rows = listOf(queue("invoices")), selected = null)
        waitForIdle()
        node("queueDetail").assertDoesNotExist()
        node("cell:invoices:Name").assertExists()
    }

    private class FakeClip : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }
}
