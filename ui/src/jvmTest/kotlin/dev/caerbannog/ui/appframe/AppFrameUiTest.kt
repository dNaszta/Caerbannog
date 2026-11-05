package dev.caerbannog.ui.appframe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-05b step 2: the app frame (brief item 7): the drawer (search and the four navigation items, NO Connection card) and the app
 * bar (title, the screen's own actions, the fixed chip last) around every screen. Tags: "appDrawer", "appBar", "connectionChip"
 * (see [ConnectionControlUiTest]). The frame has no navigation callbacks in this task: the items are drawn, not wired (the
 * screens come with M2-05c and M2-08 to M4); the test pins the look and the place of the chip.
 */
@OptIn(ExperimentalTestApi::class)
class AppFrameUiTest {

    private val noop = object : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    private val none = ConnectionControlState(
        chip = ConnectionChipState(ChipState.NoConnection, ChipIcon.LinkOff, "No connection", null, null, null, "No connection. Open connection menu", false),
        rows = listOf(ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false)),
        emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every5, enabled = false, note = "Connect to refresh"),
    )

    private fun DesktopComposeUiTest.frame(title: String = "Streams", control: ConnectionControlState = none, content: @androidx.compose.runtime.Composable () -> Unit = { Text("the screen") }) {
        setContent {
            AppFrame(
                title = title, control = control, events = noop,
                actions = { Button(onClick = {}) { Text("Create stream") } },
                content = content,
            )
        }
    }

    @Test
    fun `the app bar has the title, then the screen's own action, then the chip last`() = runDesktopComposeUiTest(1280, 800) {
        frame()

        onNode(hasText("Streams").and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).assertExists("the title in the app bar")
        val action = onNode(hasText("Create stream").and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = onNode(hasText("Streams").and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chip = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= action.left + 0.5f, "title before the action: $title, $action")
        assertTrue(action.right <= chip.left + 0.5f, "the chip is after the screen's own action: action $action, chip $chip")
        assertTrue(chip.right <= 1280f + 0.5f, "the chip is inside the window: $chip")
        val bar = onNodeWithTag("appBar").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.top >= bar.top && chip.bottom <= bar.bottom + 0.5f, "the chip is inside the app bar: bar $bar, chip $chip")
    }

    @Test
    fun `the chip is in the frame of every screen, with no connection it says No connection`() = runDesktopComposeUiTest(1280, 800) {
        val title = androidx.compose.runtime.mutableStateOf("Streams")
        setContent { AppFrame(title = title.value, control = none, events = noop) { Text("the screen of ${title.value}") } }
        for (name in listOf("Streams", "Connections", "Topology", "Migrations")) {
            title.value = name // a screen is another content under the same frame
            waitForIdle()
            onNodeWithTag("connectionChip").assertExists("chip on '$name'")
            onNode(hasText("No connection").and(hasAnyAncestor(hasTestTag("connectionChip"))), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `the drawer has the brand, the search field and the four navigation items`() = runDesktopComposeUiTest(1280, 800) {
        frame()

        listOf("Caerbannog", "behind the rabbit", "Search streams, queues, exchanges", "Connections", "Streams", "Topology", "Migrations").forEach {
            onNode(hasText(it).and(hasAnyAncestor(hasTestTag("appDrawer"))), useUnmergedTree = true).assertExists("'$it' in the drawer")
        }
        val drawer = onNodeWithTag("appDrawer").fetchSemanticsNode().boundsInRoot
        assertTrue(drawer.left <= 1f && drawer.width in 270f..600f, "the drawer sits at the left edge, about 280dp wide: $drawer")
    }

    @Test
    fun `the drawer has no Connection card, the chip replaced it`() = runDesktopComposeUiTest(1280, 800) {
        frame()

        onNodeWithTag("drawerConnectionCard").assertDoesNotExist()
        onNodeWithTag("drawerPermissionPill", useUnmergedTree = true).assertDoesNotExist()
        onNode(hasText("No connection selected"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the screen content is shown below the app bar and right of the drawer`() = runDesktopComposeUiTest(1280, 800) {
        frame()

        val content = onNodeWithText("the screen").fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag("appBar").fetchSemanticsNode().boundsInRoot
        val drawer = onNodeWithTag("appDrawer").fetchSemanticsNode().boundsInRoot
        assertTrue(content.top >= bar.bottom - 0.5f, "content below the bar: bar $bar, content $content")
        assertTrue(content.left >= drawer.right - 0.5f, "content right of the drawer: drawer $drawer, content $content")
    }

    @Test
    fun `the chip never moves when the content scrolls`() = runDesktopComposeUiTest(1280, 500) {
        frame {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll)) {
                repeat(60) { Text("row $it", Modifier.height(40.dp)) }
            }
        }
        val before = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot

        onNodeWithText("row 0").performScrollTo()
        onNodeWithText("row 59").performScrollTo()

        assertEquals(before, onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot, "the chip is fixed")
    }

    @Test
    fun `in a narrow window the chip stays inside the window`() = runDesktopComposeUiTest(640, 600) {
        frame(control = none.copy(chip = none.chip.copy(text = "a-rather-long-connection-name-".repeat(4))))

        val chip = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.right <= 640f + 0.5f && chip.left >= 0f, "the chip is inside a 640 px window: $chip")
    }

    @Test
    fun `the popup opens from the chip in the frame`() = runDesktopComposeUiTest(1280, 800) {
        frame()

        onNodeWithTag("connectionChip").performClick()
        waitForIdle()

        onNodeWithTag("connectionPopup").assertExists()
        onNodeWithTag("popupAction:uat-eu").assertExists()
    }
}
