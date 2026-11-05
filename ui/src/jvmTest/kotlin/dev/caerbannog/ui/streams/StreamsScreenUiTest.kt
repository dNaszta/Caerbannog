package dev.caerbannog.ui.streams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamSort
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-05c step 2: the stream list screen (brief items 9 to 15, `docs/ux/Streams.dc.html` incl. "States and rules"), drawn from the
 * [dev.caerbannog.core.streams.StreamListState] as the view model builds it: the screen DRAWS the state's strings, it decides none
 * (`message`, `rangeText`, `approximateNote`, row cells). Window 1280 x 800 dp, the size the mockups are drawn for.
 *
 * Tag contract (the developer follows it, or asks me to change the test and the KDoc together):
 *  - frame (05b): `appBar`, `appDrawer`, `connectionChip`; the app bar holds the title "Streams" and, in this order: the age text
 *    `updatedAge` (only when `updatedAtMillis` is set), the Refresh icon button `refreshIcon` (content description "Refresh"),
 *    the `createStream` button (text "Create stream", every state), then the chip last.
 *  - table: `streamsTable` (exists only when the state has a table: Rows and Loading), headers `columnHeader:<Column>`, rows
 *    `streamRow:<name>`, and the TEXT NODE of each cell `cell:<name>:<Column>`, with Column one of Name, Type, Retention, Messages,
 *    FirstOffset, LastOffset, Size, Publishers, Consumers (left to right, header texts as in the mockup).
 *  - others: `streamsSearch` (text field, Rows only), `pagePrevious` / `pageNext` (content descriptions "Previous page" / "Next page"),
 *    `rangeText`, `approximateNote`, `streamsBanner` (the lost-connection banner, holds the state's `banner` text and a "Retry" button),
 *    `createStreamEmpty` (the body button of the Empty state, text "Create stream").
 */
@OptIn(ExperimentalTestApi::class)
class StreamsScreenUiTest {

    private val columns = listOf("Name", "Type", "Retention", "Messages", "FirstOffset", "LastOffset", "Size", "Publishers", "Consumers")
    private val headerTexts = listOf("Name", "Type", "Retention", "Messages (approx.)", "First offset", "Last offset", "Size", "Publishers", "Consumers")

    // --- the list is "shown" only while the screen is on show (D3) ------------------------------------------------------------

    @Test
    fun `the screen tells the view model it is shown when it enters, and not shown when it leaves`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        var on by androidx.compose.runtime.mutableStateOf(true)
        setContent { if (on) StreamsScreen(rowsState(), 0, CreateStreamGate.Enabled, events, connected, noControlEvents) }
        waitForIdle()
        assertEquals(listOf("setShown:true"), events.calls, "entering the composition")

        on = false
        waitForIdle()
        assertEquals(listOf("setShown:true", "setShown:false"), events.calls, "leaving it: no more polling while another screen is on")
    }

    // --- rows -----------------------------------------------------------------------------------------------------------

    @Test
    fun `the table has the nine columns of the mockup in order, with no action column`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState())

        val lefts = columns.map { onNodeWithTag("columnHeader:$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts, "columns left to right: $columns")
        columns.zip(headerTexts).forEach { (column, text) ->
            onNode(hasTestTag("columnHeader:$column").and(hasText(text)), useUnmergedTree = true).assertExists("header $column reads \"$text\"")
        }
    }

    @Test
    fun `a row shows every cell exactly as the state says, offsets plain and counts grouped`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(listOf(row("audit-log", messages = "1,204,000", first = "880000", last = "2083999", retention = "30D", publishers = "1", consumers = "1"))))

        val expected = mapOf(
            "Name" to "audit-log", "Type" to "stream", "Retention" to "30D", "Messages" to "1,204,000", "FirstOffset" to "880000",
            "LastOffset" to "2083999", "Publishers" to "1", "Consumers" to "1",
        )
        expected.forEach { (column, text) ->
            onNode(hasTestTag("cell:audit-log:$column").and(hasText(text)), useUnmergedTree = true).assertExists("cell $column reads \"$text\"")
        }
    }

    @Test
    fun `the Size column is kept and every cell shows a dash (decision N1, blank until M3)`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(listOf(row("a"), row("b"))))

        listOf("a", "b").forEach {
            onNode(hasTestTag("cell:$it:Size").and(hasText("—")), useUnmergedTree = true).assertExists("Size of $it is an em dash")
        }
    }

    @Test
    fun `cells that are not known read unavailable or loading, and the counts stay`() = runDesktopComposeUiTest(1280, 800) {
        screen(
            rowsState(
                listOf(
                    row("denied", first = "unavailable", last = "unavailable", publishers = "unavailable"),
                    row("slow", first = "loading...", last = "loading..."),
                ),
            ),
        )

        onNode(hasTestTag("cell:denied:FirstOffset").and(hasText("unavailable")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("cell:denied:LastOffset").and(hasText("unavailable")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("cell:denied:Publishers").and(hasText("unavailable")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("cell:denied:Messages").and(hasText("48,300")), useUnmergedTree = true).assertExists("the count stays")
        onNode(hasTestTag("cell:slow:FirstOffset").and(hasText("loading...")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("cell:slow:LastOffset").and(hasText("loading...")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `until M3 there is no Browse button and no more-actions menu, and the kind chips exist since M2-07`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState())

        onNodeWithText("Browse").assertDoesNotExist()
        onNode(androidx.compose.ui.test.hasContentDescription("More actions"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Super streams").assertExists()
        onNodeWithText("All").assertExists()
    }

    @Test
    fun `the approximate note and the range text are the state's strings`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(List(5) { row("s$it") }, totalStreams = 12, hasNextPage = true))

        onNode(hasTestTag("rangeText").and(hasText("1 to 5 of 12")), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("approximateNote").and(hasText("Message counts and offsets are approximate.")), useUnmergedTree = true).assertExists()
    }

    // --- sort, filter, paging -------------------------------------------------------------------------------------------

    @Test
    fun `the Name header sorts, and a second press reverses`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        val shown = screen(rowsState(sort = StreamSort.NameAscending), events)
        onNodeWithTag("columnHeader:Name", useUnmergedTree = true).performClick()
        assertEquals(listOf("setSort:NameDescending"), events.sent())

        events.calls.clear()
        shown.state = rowsState(sort = StreamSort.NameDescending)
        waitForIdle()
        onNodeWithTag("columnHeader:Name", useUnmergedTree = true).performClick()
        assertEquals(listOf("setSort:NameAscending"), events.sent())
    }

    @Test
    fun `typing in the search field sends the text, and the field shows the state's filter`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(rowsState(), events)

        onNodeWithTag("streamsSearch").performTextInput("ord")

        assertEquals(listOf("setFilter:ord"), events.sent())
    }

    @Test
    fun `a filter that matches nothing keeps the search field and the filter text, and shows an empty table, not an error`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(rows = emptyList(), filter = "zzz", totalStreams = 0))

        onNode(hasTestTag("streamsSearch").and(hasText("zzz")), useUnmergedTree = true).assertExists("the user can still correct the filter")
        onNodeWithTag("columnHeader:Name", useUnmergedTree = true).assertExists()
        onNodeWithText("There are no streams on this broker yet.").assertDoesNotExist()
    }

    @Test
    fun `at the first and last page both paging buttons are disabled, in the middle both are enabled`() = runDesktopComposeUiTest(1280, 800) {
        val middle = RecordedEvents()
        val shown = screen(rowsState(hasNextPage = false, hasPreviousPage = false), middle)
        onNodeWithTag("pagePrevious").assertIsNotEnabled()
        onNodeWithTag("pageNext").assertIsNotEnabled()

        shown.state = rowsState(hasNextPage = true, hasPreviousPage = true)
        waitForIdle()
        onNodeWithTag("pagePrevious").assertIsEnabled().performClick()
        onNodeWithTag("pageNext").assertIsEnabled().performClick()
        assertEquals(listOf("previousPage", "nextPage"), middle.sent())
    }

    @Test
    fun `on the first page only Next is enabled, on the last page only Previous`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(rowsState(hasNextPage = true, hasPreviousPage = false))
        onNodeWithTag("pagePrevious").assertIsNotEnabled()
        onNodeWithTag("pageNext").assertIsEnabled()

        shown.state = rowsState(hasNextPage = false, hasPreviousPage = true)
        waitForIdle()
        onNodeWithTag("pagePrevious").assertIsEnabled()
        onNodeWithTag("pageNext").assertIsNotEnabled()
    }

    // --- states with no table -------------------------------------------------------------------------------------------

    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertNoTableNoFilter() {
        onNodeWithTag("streamsTable").assertDoesNotExist()
        onNodeWithTag("streamsSearch").assertDoesNotExist()
        onNodeWithTag("pageNext").assertDoesNotExist()
        onNodeWithTag("pagePrevious").assertDoesNotExist()
    }

    @Test
    fun `not connected says Not connected, with no table, no filter, no paging`() = runDesktopComposeUiTest(1280, 800) {
        screen(notConnected)

        onNodeWithText("Not connected").assertExists()
        assertNoTableNoFilter()
    }

    @Test
    fun `connecting says Connecting, with no table`() = runDesktopComposeUiTest(1280, 800) {
        screen(connecting)

        onNodeWithText("Connecting...").assertExists()
        assertNoTableNoFilter()
    }

    @Test
    fun `loading shows the table frame with loading cells, never a blank table`() = runDesktopComposeUiTest(1280, 800) {
        screen(loading)

        onNodeWithTag("streamsTable").assertExists()
        onNodeWithTag("columnHeader:Name", useUnmergedTree = true).assertExists("the frame has its header")
        assertTrue(
            onAllNodesWithTextCount("loading...") >= 1,
            "the frame says loading... in its cells, not blank",
        )
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `an empty broker says so, offers Create stream and shows no table`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(empty, events, CreateStreamGate.Enabled)

        onNodeWithText("There are no streams on this broker yet.").assertExists()
        onNodeWithTag("streamsTable").assertDoesNotExist()
        onNodeWithTag("streamsSearch").assertDoesNotExist()
        onNodeWithTag("createStreamEmpty").assertIsEnabled().performClick()
        assertEquals(listOf("createStream"), events.sent(), "the press is reported, the app does nothing with it until M3")
    }

    @Test
    fun `an empty broker without the configure permission has Create stream disabled in the body too`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(empty, events, CreateStreamGate.Disabled("Topology locked"))

        onNodeWithTag("createStreamEmpty").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events.sent())
    }

    @Test
    fun `denied shows the monitoring tag message, no table, no filter, no refresh`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(denied, events)

        onNodeWithText("This user cannot read stream statistics. Ask for the monitoring tag.").assertExists()
        assertNoTableNoFilter()
        onNodeWithTag("refreshIcon").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events.sent())
    }

    @Test
    fun `a failed first load shows the new text and a Retry button that retries`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(loadFailed, events)

        onNodeWithText(LOAD_FAILED_TEXT).assertExists()
        assertNoTableNoFilter()
        onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    @Test
    fun `a failed connection shows its reason and Retry (the view model's Failed screen, not Not connected)`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(messageState(StreamListScreen.Failed("The broker could not be reached"), "The broker could not be reached", "Retry"), events)

        onNodeWithText("The broker could not be reached").assertExists()
        assertNoTableNoFilter()
        onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    // --- lost connection and stale rows ---------------------------------------------------------------------------------

    @Test
    fun `a lost connection keeps the rows, shows the banner with Retry, and marks the table stale`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(rowsState(stale = true, banner = "Connection lost", action = "Retry"), events)

        onNode(hasTestTag("streamsBanner").and(hasText("Connection lost")), useUnmergedTree = true).assertExists("the banner above the rows")
        onNode(hasTestTag("cell:orders-events:Name"), useUnmergedTree = true).assertExists("the rows stay")
        val stale = onNodeWithTag("streamsTable").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Stale", stale, "dimmed rows are also marked for screen readers, not by colour alone")
        onNode(hasText("Retry").and(hasAnyAncestor(hasTestTag("streamsBanner"))), useUnmergedTree = true).performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    @Test
    fun `fresh rows have no banner and no stale mark`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState())

        onNodeWithTag("streamsBanner").assertDoesNotExist()
        assertEquals(null, onNodeWithTag("streamsTable").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `stale rows after a failed refresh are marked stale even without the banner`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(stale = true))

        onNodeWithTag("streamsBanner").assertDoesNotExist()
        assertEquals("Stale", onNodeWithTag("streamsTable").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    // --- app bar: refresh shortcut, age, Create stream ------------------------------------------------------------------

    @Test
    fun `the app bar reads Streams, then the age, the Refresh icon, Create stream, and the chip last`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(updatedAtMillis = 1_000), nowMillis = 13_000)

        fun left(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        onNode(hasText("Streams").and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).assertExists("the title")
        val order = listOf(left("updatedAge"), left("refreshIcon"), left("createStream"), left("connectionChip"))
        assertEquals(order.sorted(), order, "age, refresh, create, chip from left to right")
    }

    @Test
    fun `the age reads Updated N s ago from the last success and the clock, and is absent before the first load`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(rowsState(updatedAtMillis = 1_000), nowMillis = 13_000)
        onNode(hasTestTag("updatedAge").and(hasText("Updated 12 s ago")), useUnmergedTree = true).assertExists()

        // 5 s back (not 500 ms: integer division would turn -0.5 s into 0 and hide a missing coerceAtLeast).
        shown.now = -4_000
        waitForIdle()
        onNode(hasTestTag("updatedAge").and(hasText("Updated 0 s ago")), useUnmergedTree = true).assertExists("a clock that went back never shows a negative age")

        shown.state = rowsState(updatedAtMillis = null)
        waitForIdle()
        onNodeWithTag("updatedAge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the Refresh icon calls refreshNow while rows or an empty list are shown`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        val shown = screen(rowsState(), events)
        onNodeWithTag("refreshIcon").assertIsEnabled().performClick()
        assertEquals(listOf("refreshNow"), events.sent())

        events.calls.clear()
        shown.state = empty
        waitForIdle()
        onNodeWithTag("refreshIcon").assertIsEnabled().performClick()
        assertEquals(listOf("refreshNow"), events.sent())
    }

    @Test
    fun `the Refresh icon is disabled and sends nothing while not connected, connecting, loading, failed or the connection is lost`() = runDesktopComposeUiTest(1280, 800) {
        val states = listOf(
            "not connected" to notConnected, "connecting" to connecting, "loading" to loading, "failed" to loadFailed,
            "lost" to rowsState(stale = true, banner = "Connection lost", action = "Retry"),
        )
        val events = RecordedEvents()
        val shown = screen(notConnected, events)
        for ((label, state) in states) {
            shown.state = state
            waitForIdle()
            onNodeWithTag("refreshIcon").assertIsNotEnabled()
            onNodeWithTag("refreshIcon").performClick()
            assertEquals(emptyList(), events.sent(), "refresh was sent while $label")
        }
    }

    @Test
    fun `Create stream is enabled by the gate and reports the press, in every state the app bar button exists`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        val shown = screen(rowsState(), events, CreateStreamGate.Enabled)
        onNodeWithTag("createStream").assertIsEnabled().performClick()
        assertEquals(listOf("createStream"), events.sent())

        shown.gate = CreateStreamGate.Disabled("Not connected")
        for (state in listOf(notConnected, connecting, loading, denied, loadFailed)) {
            shown.state = state
            waitForIdle()
            onNodeWithTag("createStream").assertExists("the button is drawn in every state (gated, not hidden): $state")
        }
    }

    @Test
    fun `Create stream without the permission is disabled, does nothing, and carries the reason for screen readers`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(rowsState(), events, CreateStreamGate.Disabled("Topology locked"))

        onNodeWithTag("createStream").assertIsNotEnabled().performClick()

        assertEquals(emptyList(), events.sent())
        // The tooltip is not reachable from a test (and not from a keyboard); the same reason is the state description.
        assertEquals(
            "Topology locked",
            onNodeWithTag("createStream").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    // --- layout ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a very long stream name is cut in the cell but keeps its full text, and the chip stays inside the window`() = runDesktopComposeUiTest(1280, 800) {
        val name = "a-very-long-stream-name-".repeat(10)
        screen(rowsState(listOf(row(name))))

        onNode(hasTestTag("cell:$name:Name").and(hasText(name)), useUnmergedTree = true).assertExists("the full name is the cell text (copy gives it)")
        val chip = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.right <= 1280f + 0.5f, "the chip is inside the window: $chip")
    }

    @Test
    fun `in a narrow window the app bar still holds the chip and the table is still complete`() = runDesktopComposeUiTest(800, 600) {
        screen(rowsState())

        val chip = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.right <= 800f + 0.5f, "the chip is inside the window: $chip")
        onNodeWithTag("columnHeader:Consumers", useUnmergedTree = true).assertExists("the table scrolls sideways, it does not drop columns")
    }
}
