package dev.caerbannog.ui.topology

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyTexts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2: the states of the mockup (`state-empty`, `state-vhost-unavailable`, `state-stale`, `state-no-tag`, loading) and the failed first
 * load, drawn from [dev.caerbannog.core.topology.TopologyState]. The screen draws `state.message` and `state.action` and decides nothing.
 * Window 1280 x 800 dp. RED on the compile stub. Tag contract in addition to [TopologyScreenUiTest]:
 *  - `topologyMessage` holds `state.message` for NotConnected, Connecting, Empty, CannotRead, VhostUnavailable and Failed (a centred message in
 *    the body, no table). Empty draws NO action (T4: nothing can be created in M2).
 *  - Failed: `topologyMessage` plus a "Retry" button `retryButton` (text `state.action`) that calls `events.retry()`; no tabs, no table, no switch.
 *  - Loading: the table frame of the current tab (`queuesTable`, `exchangesTable` or `bindingsTable`) with cells that read "loading..." (never a
 *    blank body), the tabs and the switch; the table has state description "Loading".
 *  - CannotRead (no management tag): ONLY the message: no `tab:*`, no search, no table, no `vhostSwitch`, no banner.
 *  - VhostUnavailable: the message, the tabs, and the `vhostSwitch` (usable); NO table, no search, no chips, no footer.
 *  - Stale (D2: `state.stale`): the table (rows kept) has state description "Stale" (dimmed rows are also marked for screen readers; the dimming
 *    itself is colour alpha on the text colours, not an alpha layer on the whole table: F-C lesson, not testable here); the lost-connection banner
 *    `topologyConnectionBanner` shows `state.connectionBanner` with the "Retry" button (`state.action`) when `connectionBanner` is not null, and
 *    nothing when it is null (a failed refresh marks stale without the banner). It is a polite live region.
 */
@OptIn(ExperimentalTestApi::class)
class TopologyScreenStatesUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.stateDescription(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun DesktopComposeUiTest.noTabs() = TopologyTab.entries.forEach { node("tab:${it.name}").assertDoesNotExist() }

    @Test
    fun `Not connected and Connecting are a message and nothing else`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(notConnected)
        node("topologyMessage").assertTextEquals(TopologyTexts.NOT_CONNECTED)
        noTabs()
        node("queuesTable").assertDoesNotExist()
        shown.state = connecting
        waitForIdle()
        node("topologyMessage").assertTextEquals(TopologyTexts.CONNECTING)
    }

    @Test
    fun `Loading shows the table frame with loading cells, the tabs and the switch, never a blank body`() = runDesktopComposeUiTest(1280, 800) {
        screen(loading)
        node("queuesTable").assertExists()
        assertTrue(onAllNodes(hasText(TopologyTexts.LOADING), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "loading cells")
        node("tab:Queues").assertExists()
        node("vhostSwitch").assertExists()
        assertEquals("Loading", stateDescription("queuesTable"))
    }

    @Test
    fun `Loading on the Exchanges and Bindings tabs shows their table frames`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(loading.copy(tab = TopologyTab.Exchanges))
        node("exchangesTable").assertExists()
        shown.state = loading.copy(tab = TopologyTab.Bindings)
        waitForIdle()
        node("bindingsTable").assertExists()
    }

    @Test
    fun `an empty vhost says there are no queues yet, offers no action, keeps the tabs and the switch`() = runDesktopComposeUiTest(1280, 800) {
        screen(emptyQueues)
        node("topologyMessage").assertTextEquals("There are no queues in this virtual host yet.")
        node("tab:Queues").assertExists()
        node("vhostSwitch").assertExists()
        node("queuesTable").assertDoesNotExist()
        node("retryButton").assertDoesNotExist()
        node("createStreamEmpty").assertDoesNotExist()
        onNodeWithText("Create", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the Exchanges and Bindings empty messages come from the state`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(emptyQueues.copy(tab = TopologyTab.Exchanges, message = TopologyTexts.NO_EXCHANGES))
        node("topologyMessage").assertTextEquals(TopologyTexts.NO_EXCHANGES)
        shown.state = emptyQueues.copy(tab = TopologyTab.Bindings, message = TopologyTexts.NO_BINDINGS)
        waitForIdle()
        node("topologyMessage").assertTextEquals(TopologyTexts.NO_BINDINGS)
    }

    @Test
    fun `a user without the management tag sees the message and nothing else`() = runDesktopComposeUiTest(1280, 800) {
        screen(noTag)
        node("topologyMessage").assertTextEquals("This user cannot read topology. Ask for the management tag.")
        noTabs()
        node("vhostSwitch").assertDoesNotExist()
        node("queueSearch").assertDoesNotExist()
        node("queuesTable").assertDoesNotExist()
        node("topologyBanner").assertDoesNotExist()
    }

    @Test
    fun `an unavailable vhost says so, drops the table and keeps the tabs and a usable switch`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(unavailable, events)
        node("topologyMessage").assertTextEquals(
            "Virtual host billing is not available to this user. It may have been deleted, or the user may have no permission on it.",
        )
        node("tab:Queues").assertExists()
        node("queuesTable").assertDoesNotExist()
        node("queueSearch").assertDoesNotExist()
        node("typeChip:Quorum").assertDoesNotExist()
        node("rangeText").assertDoesNotExist()
        node("vhostSwitch").performClick()
        node("vhostItem:/").performClick()
        assertEquals(listOf("selectVhost:/"), events.sent())
    }

    @Test
    fun `a failed first load shows the message and Retry, no tabs, no table, no switch`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(failed, events)
        node("topologyMessage").assertTextEquals(TopologyTexts.LOAD_FAILED)
        onNode(hasTestTag("retryButton").and(hasText("Retry")), useUnmergedTree = true).assertExists()
        noTabs()
        node("vhostSwitch").assertDoesNotExist()
        node("retryButton").performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    // --- D2 ---

    @Test
    fun `a lost connection keeps the rows, shows the banner with Retry, and marks the table stale`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(stale = true, connectionBanner = "Connection lost", action = "Retry"), events)
        node("cell:orders:Ready").assertTextEquals("1,240")
        node("topologyConnectionBanner").assertExists()
        assertEquals("Connection lost", node("topologyConnectionBanner").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text })
        onNode(hasText("Retry"), useUnmergedTree = true).performClick()
        assertEquals(listOf("retry"), events.sent())
        assertEquals("Stale", stateDescription("queuesTable"))
    }

    @Test
    fun `stale rows after a failed refresh are marked stale even without the banner`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(stale = true))
        node("topologyConnectionBanner").assertDoesNotExist()
        assertEquals("Stale", stateDescription("queuesTable"))
    }

    @Test
    fun `fresh rows have no banner and no stale mark`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        node("topologyConnectionBanner").assertDoesNotExist()
        assertEquals(null, stateDescription("queuesTable"))
    }

    @Test
    fun `the stale mark is on the Exchanges and Bindings tables too`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(exchangesState(listOf(exchange(""))).copy(stale = true, connectionBanner = "Connection lost", action = "Retry"))
        assertEquals("Stale", stateDescription("exchangesTable"))
        shown.state = bindingsState(listOf(binding("events", "orders"))).copy(stale = true)
        waitForIdle()
        assertEquals("Stale", stateDescription("bindingsTable"))
    }
}
