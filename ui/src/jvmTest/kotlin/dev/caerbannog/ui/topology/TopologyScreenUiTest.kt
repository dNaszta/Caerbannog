package dev.caerbannog.ui.topology

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyTexts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2 (tests first): the Topology screen, Queues tab and frame (`docs/ux/Topology.dc.html`, top block and `vhost-menu`), drawn from
 * [dev.caerbannog.core.topology.TopologyState] as the view model builds it: the screen DRAWS the state's strings and decides none (message,
 * range text, note, cells, banners). Window 1280 x 800 dp. RED on the compile stub (it draws nothing).
 *
 * TAG CONTRACT (the developer follows it, or asks me to change the tests and this KDoc together). All lookups use the unmerged tree.
 *  frame, as on the stream list (05b): `appBar` with the title "Topology", then the age text `updatedAge` (only when `updatedAtMillis` is set,
 *   `UpdatedAge.text(now, updatedAt)`), the Refresh icon button `refreshIcon` (content description "Refresh"), then the chip last; `appDrawer`
 *   with `navItem:Topology` selected. NO `createStream` and no other action button (T4).
 *  tabs `tab:Queues`, `tab:Exchanges`, `tab:Bindings`: role Tab, text "Queues" / "Exchanges" / "Bindings", `selected` follows `state.tab`;
 *   a click, Enter or Space calls `events.setTab(tab)`. The tab row exists in Ready, Empty, Loading and VhostUnavailable; not in NotConnected,
 *   Connecting, CannotRead (no tabs, no table, no switch) and Failed (see `TopologyScreenStatesUiTest`).
 *  vhost switch `vhostSwitch` and its menu `vhostMenu` / `vhostItem:<name>`: see `TopologyVhostSwitchUiTest`.
 *  Queues tab (Ready, Empty keeps the chips and the search): search field `queueSearch` (shows `state.search`, typing calls
 *   `events.setSearch(text)`), chips `typeChip:Quorum` and `typeChip:OtherTypes` (texts "Quorum" and "Other types", `selected` follows
 *   `state.queueFilter`, a click calls `events.setQueueFilter(filter)`), table `queuesTable`, headers `columnHeader:<Column>` with Column one of
 *   Name, Ready, Unacked, Consumers, Leader, MembersOnline (header texts "Name", "Ready", "Unacked", "Consumers", "Leader", "Members online";
 *   left to right in that order) and, only while the filter is OtherTypes, `columnHeader:Type` (text "Type") right after Name;
 *   rows `queueRow:<name>`; the text node of each cell `cell:<name>:<Column>` shows the row's string unchanged;
 *   `memberOffline:<name>` exists only for a row with `memberOffline`, content description "Member offline" (a mark, not colour alone).
 *   Selecting: a click, Enter or Space on `cell:<name>:Name` calls `events.selectQueue(name)`; the detail sheet is in `TopologyDetailUiTest`.
 *  footer (Queues, Exchanges, Bindings): `rangeText` (state.rangeText), `pagePrevious` / `pageNext` (content descriptions "Previous page" /
 *   "Next page", enabled by `hasPreviousPage` / `hasNextPage`, a click calls previousPage / nextPage); `approximateNote` (state.approximateNote) on the
 *   Queues tab only.
 *  entering the composition calls `events.setShown(true)`, leaving it `events.setShown(false)` (D3).
 */
@OptIn(ExperimentalTestApi::class)
class TopologyScreenUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.left(tag: String): Float = node(tag).fetchSemanticsNode().boundsInRoot.left

    private fun DesktopComposeUiTest.key(tag: String, key: Key) {
        node(tag).requestFocus()
        node(tag).performKeyInput { keyDown(key); keyUp(key) }
        waitForIdle()
    }

    // --- shown (D3) ---

    @Test
    fun `the screen tells the view model it is shown when it enters and not shown when it leaves`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        var on by mutableStateOf(true)
        setContent { if (on) TopologyScreen(queuesState(), 0, events, dev.caerbannog.ui.streams.connected, dev.caerbannog.ui.streams.noControlEvents) }
        waitForIdle()
        assertEquals(listOf("setShown:true"), events.calls)
        on = false
        waitForIdle()
        assertEquals(listOf("setShown:true", "setShown:false"), events.calls, "no more polling while another screen is on")
    }

    // --- frame ---

    @Test
    fun `the app bar says Topology, the drawer marks Topology, and there is no create or write button`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        node("appBar").assertExists()
        onNode(hasText("Topology").and(androidx.compose.ui.test.hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).assertExists()
        assertEquals(true, node("navItem:Topology").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        node("createStream").assertDoesNotExist()
        node("connectionChip").assertExists()
    }

    @Test
    fun `the age text shows only once a load succeeded, the refresh icon refreshes`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(queuesState(), events, nowMillis = 13_000)
        node("updatedAge").assertDoesNotExist()
        shown.state = queuesState(updatedAtMillis = 5_000)
        waitForIdle()
        node("updatedAge").assertTextEquals("Updated 8 s ago")
        node("refreshIcon").performClick()
        assertEquals(listOf("refreshNow"), events.sent())
    }

    @Test
    fun `the refresh icon is off while the connection is lost and while nothing is loaded`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState())
        node("refreshIcon").assertIsEnabled()
        shown.state = queuesState(stale = true, connectionBanner = "Connection lost", action = "Retry")
        waitForIdle()
        node("refreshIcon").assertIsNotEnabled()
        shown.state = loading
        waitForIdle()
        node("refreshIcon").assertIsNotEnabled()
    }

    // --- tabs ---

    @Test
    fun `three tabs in order, the current one selected, a click or Enter or Space switches`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(), events)
        val tabs = listOf("Queues", "Exchanges", "Bindings")
        assertEquals(tabs.map { left("tab:$it") }.sorted(), tabs.map { left("tab:$it") }, "tabs left to right")
        tabs.forEach { onNode(hasTestTag("tab:$it").and(hasText(it)), useUnmergedTree = true).assertExists() }
        assertEquals(true, node("tab:Queues").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, node("tab:Exchanges").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))

        node("tab:Exchanges").performClick()
        key("tab:Bindings", Key.Enter)
        key("tab:Queues", Key.Spacebar)
        assertEquals(listOf("setTab:Exchanges", "setTab:Bindings", "setTab:Queues"), events.sent())
    }

    @Test
    fun `the selected tab follows the state`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(exchangesState(listOf(exchange(""))))
        assertEquals(true, node("tab:Exchanges").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        shown.state = bindingsState(listOf(binding("events", "orders")))
        waitForIdle()
        assertEquals(true, node("tab:Bindings").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    // --- queues table ---

    private val columns = listOf("Name", "Ready", "Unacked", "Consumers", "Leader", "MembersOnline")
    private val headerTexts = listOf("Name", "Ready", "Unacked", "Consumers", "Leader", "Members online")

    @Test
    fun `the queues table has the six columns of the mockup in order, one row per queue, cells as given`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        val lefts = columns.map { left("columnHeader:$it") }
        assertEquals(lefts.sorted(), lefts, "columns left to right: $columns")
        columns.zip(headerTexts).forEach { (column, text) -> onNode(hasTestTag("columnHeader:$column").and(hasText(text)), useUnmergedTree = true).assertExists("header $column reads \"$text\"") }
        node("columnHeader:Type").assertDoesNotExist()
        node("queuesTable").assertExists()
        mockupQueues.forEach { node("queueRow:${it.name}").assertExists() }
        node("cell:orders:Ready").assertTextEquals("1,240")
        node("cell:orders:Unacked").assertTextEquals("12")
        node("cell:orders:Consumers").assertTextEquals("3")
        node("cell:orders:Leader").assertTextEquals("rabbit@node-1")
        node("cell:notifications:MembersOnline").assertTextEquals("2 of 3")
    }

    @Test
    fun `the Other types filter adds the Type column and shows the dashes of a classic queue`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(listOf(queue("legacy", type = "classic", leader = "–", members = "–")), filter = QueueTypeFilter.OtherTypes))
        assertTrue(left("columnHeader:Type") > left("columnHeader:Name"))
        assertTrue(left("columnHeader:Type") < left("columnHeader:Ready"))
        node("cell:legacy:Type").assertTextEquals("classic")
        node("cell:legacy:Leader").assertTextEquals("–")
        node("cell:legacy:MembersOnline").assertTextEquals("–")
    }

    @Test
    fun `n slash a counts are drawn as given, never as zero`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(listOf(queue("fresh", ready = "n/a", unacked = "n/a", consumers = "n/a"))))
        node("cell:fresh:Ready").assertTextEquals("n/a")
        node("cell:fresh:Consumers").assertTextEquals("n/a")
    }

    @Test
    fun `a queue with a member offline is marked with a described mark, the others are not`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        node("memberOffline:notifications").assertExists()
        assertEquals(listOf("Member offline"), node("memberOffline:notifications").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        node("memberOffline:orders").assertDoesNotExist()
    }

    @Test
    fun `a long queue name does not push the other cells out of the row`() = runDesktopComposeUiTest(1280, 800) {
        val long = "a-very-long-queue-name-" + "x".repeat(120)
        screen(queuesState(listOf(queue(long), queue("short"))))
        assertEquals(left("cell:short:Ready"), left("cell:$long:Ready"), "the Ready cell stays in its column")
    }

    @Test
    fun `selecting a queue by click, Enter or Space on its name`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(), events)
        node("cell:orders:Name").performClick()
        key("cell:invoices:Name", Key.Enter)
        key("cell:notifications:Name", Key.Spacebar)
        assertEquals(listOf("selectQueue:orders", "selectQueue:invoices", "selectQueue:notifications"), events.sent())
    }

    // --- chips and search ---

    @Test
    fun `the Quorum and Other types chips follow the state, a click sends the filter`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(filter = QueueTypeFilter.Quorum), events)
        onNode(hasTestTag("typeChip:Quorum").and(hasText("Quorum")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("typeChip:OtherTypes").and(hasText("Other types")), useUnmergedTree = true).assertExists()
        assertEquals(true, node("typeChip:Quorum").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, node("typeChip:OtherTypes").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        node("typeChip:OtherTypes").performClick()
        assertEquals(listOf("setQueueFilter:OtherTypes"), events.sent())
    }

    @Test
    fun `typing in the search field sends the text, the field shows the state's search`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(queuesState(), events)
        node("queueSearch").performTextInput("orders")
        waitForIdle()
        assertTrue(events.sent().isNotEmpty() && events.sent().all { it.startsWith("setSearch:") } && events.sent().last() == "setSearch:orders", events.sent().toString())
        shown.state = queuesState(search = "inv")
        waitForIdle()
        assertEquals("inv", node("queueSearch").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test
    fun `a search with no match still shows the table frame, the chips and the field`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(rows = emptyList(), search = "zzz"))
        node("queueSearch").assertExists()
        node("typeChip:Quorum").assertExists()
        node("queuesTable").assertExists()
    }

    // --- footer ---

    @Test
    fun `the footer shows the range text and the approximate note and pages with the buttons`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(queuesState(rangeText = "1 to 4 of 12", hasNext = true), events)
        node("rangeText").assertTextEquals("1 to 4 of 12")
        node("approximateNote").assertTextEquals(TopologyTexts.APPROXIMATE)
        node("pagePrevious").assertIsNotEnabled()
        node("pageNext").assertIsEnabled()
        node("pageNext").performClick()
        shown.state = queuesState(rangeText = "5 to 8 of 12", hasNext = true, hasPrevious = true)
        waitForIdle()
        node("pagePrevious").performClick()
        assertEquals(listOf("nextPage", "previousPage"), events.sent())
    }

    @Test
    fun `the page buttons have accessible names`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        assertEquals(listOf("Previous page"), node("pagePrevious").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf("Next page"), node("pageNext").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
    }
}
