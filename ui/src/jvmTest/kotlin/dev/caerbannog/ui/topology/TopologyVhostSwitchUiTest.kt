package dev.caerbannog.ui.topology

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2: the virtual host switch (mockup `vhost-menu`, decision T1, R4). One virtual host at a time, starting on the connection's own; the list
 * is the broker's (the view model orders it: the connection's first, then alphabetical) and the screen draws it in the state's order, without
 * permission marks. Window 1280 x 800 dp. RED on the compile stub. Tag contract, in addition to [TopologyScreenUiTest]:
 *  - `vhostSwitch`: a button above the tables on every tab, text "Virtual host <state.vhost>" (the name is the selected one), exists iff
 *    `state.showVhostSwitch` (the architect's ruling: a one-entry list still shows it; before the first good list answer the state hides it, the
 *    screen hides it, it is NOT drawn disabled). Role Button, content description "Virtual host <vhost>, change". Enter, Space or a click opens
 *    the menu.
 *  - `vhostMenu`: the open menu; one `vhostItem:<name>` per entry of `state.vhosts`, in the state's order; the item of `state.vhost` is selected
 *    (semantics selected true), the others false. Role MenuItem or Button, text = the name. Focus moves to the selected item on open.
 *  - choosing an item calls `events.selectVhost(name)` and closes the menu; choosing the CURRENT one only closes it (no event). Enter or Space
 *    on a focused item chooses it; Down and Up move focus between items; Escape closes the menu and returns focus to `vhostSwitch`.
 *  - the menu is closed when the state changes the vhost (a switch happened) and never persisted by the screen.
 */
@OptIn(ExperimentalTestApi::class)
class TopologyVhostSwitchUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.key(tag: String, key: Key) {
        node(tag).requestFocus()
        node(tag).performKeyInput { keyDown(key); keyUp(key) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.text(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    @Test
    fun `the switch shows the selected vhost and opens a menu of the offered ones in order`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(vhost = "/", vhosts = listOf("/", "billing", "many")))
        assertTrue(text("vhostSwitch").contains("Virtual host"), text("vhostSwitch"))
        assertTrue(text("vhostSwitch").contains("/"))
        node("vhostMenu").assertDoesNotExist()
        node("vhostSwitch").performClick()
        node("vhostMenu").assertExists()
        val names = listOf("/", "billing", "many")
        names.forEach { node("vhostItem:$it").assertExists() }
        val tops = names.map { node("vhostItem:$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "the state's order is the drawn order")
        assertEquals(true, node("vhostItem:/").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, node("vhostItem:billing").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun `the name of the selected vhost is drawn on the switch, also a name with a space and a slash`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(vhost = "my vhost/x", vhosts = listOf("/", "my vhost/x")))
        assertTrue(text("vhostSwitch").contains("my vhost/x"), text("vhostSwitch"))
        assertEquals(listOf("Virtual host my vhost/x, change"), node("vhostSwitch").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `choosing another vhost sends it and closes the menu, choosing the current one only closes it`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(), events)
        node("vhostSwitch").performClick()
        node("vhostItem:billing").performClick()
        node("vhostMenu").assertDoesNotExist()
        node("vhostSwitch").performClick()
        node("vhostItem:/").performClick()
        node("vhostMenu").assertDoesNotExist()
        assertEquals(listOf("selectVhost:billing"), events.sent())
    }

    @Test
    fun `the switch is a normal tab stop, Enter and Space open the menu`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        key("vhostSwitch", Key.Enter)
        node("vhostMenu").assertExists()
        key("vhostItem:/", Key.Escape)
        node("vhostMenu").assertDoesNotExist()
        key("vhostSwitch", Key.Spacebar)
        node("vhostMenu").assertExists()
    }

    @Test
    fun `focus goes to the selected item on open, Down and Up move, Enter and Space choose`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(), events)
        node("vhostSwitch").performClick()
        waitForIdle()
        node("vhostItem:/").assertIsFocused()
        node("vhostItem:/").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        waitForIdle()
        node("vhostItem:billing").assertIsFocused()
        node("vhostItem:billing").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        waitForIdle()
        node("vhostItem:many").assertIsFocused()
        node("vhostItem:many").performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        waitForIdle()
        node("vhostItem:billing").assertIsFocused()
        node("vhostItem:billing").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("selectVhost:billing"), events.sent())
        node("vhostMenu").assertDoesNotExist()
    }

    @Test
    fun `Escape closes the menu, sends nothing and returns focus to the switch`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(), events)
        node("vhostSwitch").performClick()
        waitForIdle()
        node("vhostItem:/").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        waitForIdle()
        node("vhostMenu").assertDoesNotExist()
        node("vhostSwitch").assertIsFocused()
        assertEquals(emptyList(), events.sent())
    }

    @Test
    fun `a one entry list still shows the switch, no list at all hides it`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState(vhosts = listOf("/"), showSwitch = true))
        node("vhostSwitch").assertExists()
        shown.state = queuesState(vhosts = emptyList(), showSwitch = false)
        waitForIdle()
        node("vhostSwitch").assertDoesNotExist()
        node("tab:Queues").assertExists()
    }

    @Test
    fun `the menu closes when the state changes the vhost`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState())
        node("vhostSwitch").performClick()
        node("vhostMenu").assertExists()
        shown.state = queuesState(vhost = "billing")
        waitForIdle()
        node("vhostMenu").assertDoesNotExist()
        assertTrue(text("vhostSwitch").contains("billing"))
    }

    @Test
    fun `the switch is on the Exchanges and Bindings tabs too`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(exchangesState(listOf(exchange(""))))
        node("vhostSwitch").assertExists()
        shown.state = bindingsState(listOf(binding("events", "orders")))
        waitForIdle()
        node("vhostSwitch").assertExists()
    }
}
