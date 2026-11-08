package dev.caerbannog.ui.streams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.ui.appframe.AppDestination
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-06b step 2: the stream screen with its one tab "Publishers and consumers" (brief items 9 and 10, rulings 9 and 10,
 * `docs/ux/Clients.dc.html` incl. "States and rules"), drawn from [dev.caerbannog.core.streams.StreamClientsState] as the view model
 * builds it: the screen DRAWS the state's strings and decides none. Window 1280 x 800 dp.
 *
 * Tag contract (the developer follows it, or asks me to change the test and this KDoc together):
 *  - frame: `appBar`, `appDrawer`, `connectionChip`, `navItem:<Name>` as in the list. The app bar holds, in this order: the back arrow
 *    `backArrow` (content description "Back to streams"), the title (the stream name, from the `stream` parameter), the age text
 *    `updatedAge` ("Updated N s ago", only when `updatedAtMillis` is set), the Refresh icon `refreshIcon` (content description
 *    "Refresh"), the chip last. The drawer keeps `navItem:Streams` selected (the app frame must be told the active item: its title is
 *    the stream name here).
 *  - tabs: `tabList` holds exactly ONE tab `tab:Publishers and consumers` (text "Publishers and consumers", selected). No "Messages"
 *    tab, no disabled one.
 *  - sections (screen Content, and Loading): each with a caption node
 *    `publishersCaption` / `consumersCaption` whose text reads "Publishers 2" / "Consumers 3" (name, count) and a table node
 *    `publishersTable` / `consumersTable` whose content description is that same caption (so a reader announces the count), and
 *    which carries state description "Stale" when the state is stale, "Loading" while the screen is Loading. Headers
 *    `publishersHeader:<Reference|Connection|Published|Confirmed|Errors>`, `consumersHeader:<Consumer|Connection|Offset|Behind|Status>`.
 *    Empty lists: `publishersEmpty` / `consumersEmpty` with the mockup's sentences, in place of the rows, not of the section.
 *  - cells (text nodes): `publisherCell:<index in list order>:<Reference|Connection|User|Published|Confirmed|Errors>` and
 *    `consumerCell:<ConsumerRowState.key>:<Name|Connection|User|Offset|Behind|Status>`. Connection and User are two text nodes in one
 *    cell (user under the connection). Row identity of consumers is the key; publishers have none, so their tag index is the position.
 *  - `lastOffset` ("Last offset on the stream: 48299"), `clientsFootnote`, `ownConnectionsNotice`, `pluginMissingBanner`,
 *    `clientsBanner` (lost connection: text and a "Retry" button), `backToStreams` (the stream-gone button, text "Back to streams").
 */
@OptIn(ExperimentalTestApi::class)
class StreamScreenUiTest {

    private val noSections = listOf("publishersSection", "consumersSection", "publishersTable", "consumersTable", "lastOffset", "clientsFootnote")

    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertNoSections() {
        noSections.forEach { onAllNodes(hasTestTag(it), useUnmergedTree = true).assertCountEquals(0) }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertText(tag: String, text: String) =
        onNode(hasTestTag(tag).and(hasText(text)), useUnmergedTree = true).assertExists("$tag reads \"$text\"")

    private fun androidx.compose.ui.test.DesktopComposeUiTest.leftOf(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    // --- the tab calls show and hide while it is on screen (D3) --------------------------------------------------------------

    @Test
    fun `the tab tells the view model which stream it shows when it enters, and hide when it leaves`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        var on by mutableStateOf(true)
        setContent { if (on) StreamScreen(STREAM, content(), 0, events, connected, noControlEvents) }
        waitForIdle()
        assertEquals(listOf("show:$STREAM"), events.calls, "entering the composition")

        on = false
        waitForIdle()
        assertEquals(listOf("show:$STREAM", "hide"), events.calls, "leaving it: no more polling while another screen is on")
    }

    // --- header, back, drawer --------------------------------------------------------------------------------------------

    @Test
    fun `the app bar shows the back arrow, the stream name, the age, Refresh, and the chip last`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(updatedAtMillis = 1_000), nowMillis = 6_000)

        onNode(hasText(STREAM).and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).assertExists("the title is the stream name")
        assertText("updatedAge", "Updated 5 s ago")
        val order = listOf("backArrow", "updatedAge", "refreshIcon", "connectionChip").map { leftOf(it) }
        assertEquals(order.sorted(), order, "left to right: back, age, Refresh, chip")
        assertEquals("Refresh", onNodeWithTag("refreshIcon", useUnmergedTree = true).contentDescription())
    }

    @Test
    fun `the age is absent before the first load`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(clientsLoading)

        onAllNodes(hasTestTag("updatedAge"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the back arrow calls back, with the mouse and with Enter`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        clientsScreen(content(), events)

        onNodeWithTag("backArrow").performClick()
        assertEquals(listOf("back"), events.sent())
        onNodeWithTag("backArrow").requestFocus()
        onNodeWithTag("backArrow").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        assertEquals(listOf("back", "back"), events.sent(), "reachable from the keyboard")
        assertEquals("Back to streams", onNodeWithTag("backArrow", useUnmergedTree = true).contentDescription())
    }

    @Test
    fun `the drawer keeps Streams selected while a stream is open, and a drawer item still navigates`() = runDesktopComposeUiTest(1280, 800) {
        val seen = mutableListOf<AppDestination>()
        clientsScreen(content(), onNavigate = { seen += it })

        onNodeWithTag("navItem:Streams").assertIsSelected()
        listOf("Connections", "Topology", "Migrations").forEach {
            onNodeWithTag("navItem:$it").assertIsNotSelected()
        }
        onNodeWithTag("navItem:Connections").performClick()
        assertEquals(listOf(AppDestination.Connections), seen)
    }

    // --- the tab list ------------------------------------------------------------------------------------------------------

    @Test
    fun `the tab list has the one tab Publishers and consumers, selected, and no Messages tab`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        onNodeWithTag("tab:Publishers and consumers", useUnmergedTree = true).assertIsSelected()
        assertText("tab:Publishers and consumers", "Publishers and consumers")
        val tabs = onAllNodes(SemanticsMatcher("a tab") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab:") == true }, useUnmergedTree = true)
        tabs.assertCountEquals(1)
        onAllNodes(hasText("Messages"), useUnmergedTree = true).assertCountEquals(0)
    }

    // --- content: both tables, caption with count, cells -------------------------------------------------------------------

    @Test
    fun `both sections have a caption with the count, and the table announces the same caption`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        assertText("publishersCaption", "Publishers")
        assertText("consumersCaption", "Consumers")
        assertEquals("Publishers 2", onNodeWithTag("publishersTable", useUnmergedTree = true).contentDescription())
        assertEquals("Consumers 3", onNodeWithTag("consumersTable", useUnmergedTree = true).contentDescription())
        assertEquals("Publishers 2", onNodeWithTag("publishersCaption", useUnmergedTree = true).fetchSemanticsNode().captionText())
        assertEquals("Consumers 3", onNodeWithTag("consumersCaption", useUnmergedTree = true).fetchSemanticsNode().captionText())
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.captionText(): String =
        (config.getOrNull(SemanticsProperties.Text) ?: emptyList()).joinToString(" ") { it.text }

    @Test
    fun `the headers are the mockup's, in order`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        val pub = listOf("Reference" to "Reference", "Connection" to "Connection (user)", "Published" to "Published", "Confirmed" to "Confirmed", "Errors" to "Errors")
        val con = listOf("Consumer" to "Consumer", "Connection" to "Connection (user)", "Offset" to "Offset", "Behind" to "Messages behind", "Status" to "Status")
        pub.forEach { (tag, text) -> assertText("publishersHeader:$tag", text) }
        con.forEach { (tag, text) -> assertText("consumersHeader:$tag", text) }
        assertEquals(pub.map { leftOf("publishersHeader:${it.first}") }.sorted(), pub.map { leftOf("publishersHeader:${it.first}") })
        assertEquals(con.map { leftOf("consumersHeader:${it.first}") }.sorted(), con.map { leftOf("consumersHeader:${it.first}") })
    }

    @Test
    fun `a publisher row shows its reference, connection with the user under it, and the counters as the state says`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        publisherCell(0, "Reference").assertTextEquals("checkout-1")
        publisherCell(0, "Connection").assertTextEquals("10.0.4.21:51234 -> 10.0.0.5:5551")
        publisherCell(0, "User").assertTextEquals("checkout")
        publisherCell(0, "Published").assertTextEquals("48,100")
        publisherCell(0, "Confirmed").assertTextEquals("48,100")
        publisherCell(0, "Errors").assertTextEquals("0")
        val connectionTop = publisherCell(0, "Connection").fetchSemanticsNode().boundsInRoot.top
        val userTop = publisherCell(0, "User").fetchSemanticsNode().boundsInRoot.top
        assertTrue(userTop > connectionTop, "the user is under the connection")
        publisherCell(1, "Reference").assertTextEquals("backfill-job")
    }

    @Test
    fun `a consumer row shows name, connection, user, plain offset, behind and status`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        consumerCell("analytics", "Name").assertTextEquals("analytics")
        consumerCell("analytics", "Connection").assertTextEquals("10.0.9.3:52901 -> 10.0.0.5:5551")
        consumerCell("analytics", "User").assertTextEquals("analytics")
        consumerCell("analytics", "Offset").assertTextEquals("47950")
        consumerCell("analytics", "Behind").assertTextEquals("349")
        consumerCell("analytics", "Status").assertTextEquals("349 behind")
    }

    @Test
    fun `the last offset of the stream is shown above the consumers, and the footnote says Caught up only at 0`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        assertText("lastOffset", "Last offset on the stream: 48299")
        assertTrue(leftOf("lastOffset") >= 0f)
        val top = onNodeWithTag("lastOffset", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < consumerCell("billing", "Name").fetchSemanticsNode().boundsInRoot.top, "above the consumer rows")
        val note = onNodeWithTag("clientsFootnote", useUnmergedTree = true).fetchSemanticsNode().captionText()
        assertTrue(note.startsWith("Read from the management API (rabbitmq_stream_management)."), note)
        assertTrue(note.endsWith("Caught up only at 0."), note)
    }

    @Test
    fun `an unavailable last offset is shown as the state says`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(lastOffset = "unavailable"))

        assertText("lastOffset", "Last offset on the stream: unavailable")
    }

    // --- status: text, hint, accessible description ----------------------------------------------------------------------

    @Test
    fun `status is text, Caught up only for lag 0, Behind with the number, Not active as its own word`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(
            content(
                consumers = listOf(
                    consumer("a", status = ConsumerStatus.CaughtUp),
                    consumer("b", behind = "2", status = ConsumerStatus.Behind(2)),
                    consumer("c", behind = "1,204,000", status = ConsumerStatus.Behind(1_204_000)),
                    consumer("d", status = ConsumerStatus.NotActive),
                ),
            ),
        )

        consumerCell("a", "Status").assertTextEquals("Caught up")
        consumerCell("b", "Status").assertTextEquals("2 behind")
        consumerCell("c", "Status").assertTextEquals("1,204,000 behind")
        consumerCell("d", "Status").assertTextEquals("Not active")
        onAllNodes(hasText("Caught up"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `a Behind status carries the stored-offsets hint as an accessible state description, a Caught up status carries none`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        // The tooltip is not reachable from a test (and not from a keyboard); the same hint must be the state description.
        assertEquals(HINT_TEXT, consumerCell("analytics", "Status").stateDescription())
        assertEquals(HINT_TEXT, consumerCell("audit", "Status").stateDescription())
        assertNull(consumerCell("billing", "Status").stateDescription(), "no hint on Caught up")
    }

    // --- settling, unnamed, no user ---------------------------------------------------------------------------------------

    @Test
    fun `a row that has just attached reads unknown and updating, never 0`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(publishers = listOf(settlingPublisher), consumers = listOf(settlingConsumer)))

        publisherCell(0, "Connection").assertTextEquals("unknown")
        listOf("Published", "Confirmed", "Errors").forEach { publisherCell(0, it).assertTextEquals("updating") }
        consumerCell("new", "Connection").assertTextEquals("unknown")
        listOf("Offset", "Behind", "Status").forEach { consumerCell("new", it).assertTextEquals("updating") }
        onAllNodes(hasText("0"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `an unnamed consumer and an unnamed publisher read unnamed in the name cell`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(publishers = listOf(publisher(reference = "unnamed")), consumers = listOf(consumer("k1", name = "unnamed"))))

        publisherCell(0, "Reference").assertTextEquals("unnamed")
        consumerCell("k1", "Name").assertTextEquals("unnamed")
    }

    @Test
    fun `a missing user is an em dash with the accessible name no user, a real user has no extra name`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        publisherCell(1, "User").assertTextEquals("—")
        assertEquals("no user", publisherCell(1, "User").contentDescription())
        assertNull(publisherCell(0, "User").contentDescription(), "a real user keeps its own text as the name")
        assertEquals(StreamClientsTexts.NO_USER, "—", "ruling 9: the constant is the em dash")
        onAllNodes(hasText("unknown"), useUnmergedTree = true).assertCountEquals(0)
    }

    // --- banners ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the own-connections notice shows with rows and stays with both lists empty`() = runDesktopComposeUiTest(1280, 800) {
        val shown = clientsScreen(content(notice = NOTICE_TEXT))
        assertText("ownConnectionsNotice", NOTICE_TEXT)

        shown.state = content(publishers = emptyList(), consumers = emptyList(), notice = NOTICE_TEXT)
        waitForIdle()
        assertText("ownConnectionsNotice", NOTICE_TEXT)
        assertText("publishersEmpty", "There are no publishers on this stream.")
        assertText("consumersEmpty", "There are no consumers on this stream.")
    }

    @Test
    fun `without the notice there is no notice node`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        onAllNodes(hasTestTag("ownConnectionsNotice"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a stream nobody uses says so per section, keeps both frames and their counts`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(publishers = emptyList(), consumers = emptyList()))

        assertText("publishersEmpty", "There are no publishers on this stream.")
        assertText("consumersEmpty", "There are no consumers on this stream.")
        assertEquals("Publishers 0", onNodeWithTag("publishersTable", useUnmergedTree = true).contentDescription())
        assertEquals("Consumers 0", onNodeWithTag("consumersTable", useUnmergedTree = true).contentDescription())
    }

    @Test
    fun `only one empty section says so when the other has rows`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content(consumers = emptyList()))

        onAllNodes(hasTestTag("publishersEmpty"), useUnmergedTree = true).assertCountEquals(0)
        assertText("consumersEmpty", "There are no consumers on this stream.")
        publisherCell(0, "Reference").assertExists()
    }

    @Test
    fun `plugin missing shows the banner in place of both sections, with the header and the last offset kept`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(messageOnly(StreamClientsScreen.PluginMissing, PLUGIN_TEXT, lastOffset = "48299"))

        assertText("pluginMissingBanner", PLUGIN_TEXT)
        onAllNodes(hasTestTag("publishersSection"), useUnmergedTree = true).assertCountEquals(0)
        onAllNodes(hasTestTag("consumersSection"), useUnmergedTree = true).assertCountEquals(0)
        onNode(hasText(STREAM).and(hasAnyAncestor(hasTestTag("appBar"))), useUnmergedTree = true).assertExists("the header keeps the stream name")
        assertText("lastOffset", "Last offset on the stream: 48299")
        onNodeWithTag("backArrow").assertIsEnabled()
    }

    @Test
    fun `denied shows the monitoring tag message and no sections`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(messageOnly(StreamClientsScreen.CannotReadStatistics, DENIED_CLIENTS_TEXT))

        onNode(hasText(DENIED_CLIENTS_TEXT), useUnmergedTree = true).assertExists()
        assertNoSections()
    }

    @Test
    fun `not connected and connecting say so, with no sections`() = runDesktopComposeUiTest(1280, 800) {
        val shown = clientsScreen(clientsNotConnected)
        onNode(hasText("Not connected"), useUnmergedTree = true).assertExists()
        assertNoSections()

        shown.state = clientsConnecting
        waitForIdle()
        onNode(hasText("Connecting..."), useUnmergedTree = true).assertExists()
        assertNoSections()
    }

    @Test
    fun `loading shows both section frames with loading rows, never a blank page`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(clientsLoading)

        onNodeWithTag("publishersTable", useUnmergedTree = true).assertExists()
        onNodeWithTag("consumersTable", useUnmergedTree = true).assertExists()
        assertEquals("Loading", onNodeWithTag("publishersTable", useUnmergedTree = true).stateDescription())
        assertEquals("Loading", onNodeWithTag("consumersTable", useUnmergedTree = true).stateDescription())
        assertTrue(onAllNodes(hasText("loading..."), useUnmergedTree = true).fetchSemanticsNodes().size >= 2, "a loading row in each section")
        onAllNodes(hasText("Caught up"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a stream deleted while open says so and offers Back to streams, which goes back`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        clientsScreen(messageOnly(StreamClientsScreen.StreamGone, GONE_TEXT), events)

        onNode(hasText(GONE_TEXT), useUnmergedTree = true).assertExists()
        assertNoSections()
        onNode(hasTestTag("backToStreams").and(hasText("Back to streams")), useUnmergedTree = true).performClick()
        assertEquals(listOf("back"), events.sent())
    }

    @Test
    fun `a failed first load says so and offers Retry, which retries`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        clientsScreen(messageOnly(StreamClientsScreen.Failed(FAILED_TEXT), FAILED_TEXT), events)

        onNode(hasText(FAILED_TEXT), useUnmergedTree = true).assertExists()
        assertNoSections()
        onNode(hasText("Retry"), useUnmergedTree = true).performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    @Test
    fun `a lost connection keeps the rows, shows the banner with Retry, and marks both tables stale`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        clientsScreen(content(stale = true, banner = "Connection lost"), events)

        assertText("clientsBanner", "Connection lost")
        consumerCell("analytics", "Status").assertExists()
        publisherCell(0, "Reference").assertExists()
        assertEquals("Stale", onNodeWithTag("publishersTable", useUnmergedTree = true).stateDescription())
        assertEquals("Stale", onNodeWithTag("consumersTable", useUnmergedTree = true).stateDescription())
        onNode(hasText("Retry").and(hasAnyAncestor(hasTestTag("clientsBanner"))), useUnmergedTree = true).performClick()
        assertEquals(listOf("retry"), events.sent())
    }

    @Test
    fun `fresh rows have no banner and no stale mark`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        onAllNodes(hasTestTag("clientsBanner"), useUnmergedTree = true).assertCountEquals(0)
        assertNull(onNodeWithTag("consumersTable", useUnmergedTree = true).stateDescription())
        assertNull(onNodeWithTag("publishersTable", useUnmergedTree = true).stateDescription())
    }

    @Test
    fun `the state going from loading to content to gone redraws, with no leftover sections`() = runDesktopComposeUiTest(1280, 800) {
        val shown = clientsScreen(clientsLoading)
        shown.state = content()
        waitForIdle()
        consumerCell("billing", "Name").assertExists()

        shown.state = messageOnly(StreamClientsScreen.StreamGone, GONE_TEXT)
        waitForIdle()
        assertNoSections()
        onAllNodes(hasTestTag("consumerCell:billing:Name"), useUnmergedTree = true).assertCountEquals(0)
    }

    // --- refresh ---------------------------------------------------------------------------------------------------------

    @Test
    fun `Refresh calls refreshNow on content`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        clientsScreen(content(), events)

        onNodeWithTag("refreshIcon").assertIsEnabled().performClick()

        assertEquals(listOf("refreshNow"), events.sent())
    }

    @Test
    fun `Refresh is disabled and sends nothing while not connected or loading`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedClientsEvents()
        val shown = clientsScreen(clientsNotConnected, events)
        onNodeWithTag("refreshIcon").assertIsNotEnabled().performClick()
        shown.state = clientsLoading
        waitForIdle()
        onNodeWithTag("refreshIcon").assertIsNotEnabled().performClick()

        assertEquals(emptyList(), events.sent())
    }

    // --- no jump button (needs the browser, M3) --------------------------------------------------------------------------

    @Test
    fun `no jump to the browser is drawn on any row`() = runDesktopComposeUiTest(1280, 800) {
        clientsScreen(content())

        val jump = SemanticsMatcher("anything that jumps or browses") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val words = (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
                .joinToString(" ").lowercase()
            tag.lowercase().contains("jump") || tag.lowercase().contains("browse") || words.contains("jump") || words.contains("browse")
        }
        onAllNodes(jump, useUnmergedTree = true).assertCountEquals(0)
    }

    // --- narrow window ---------------------------------------------------------------------------------------------------

    @Test
    fun `in a narrow window the app bar still holds the chip and every column header exists`() = runDesktopComposeUiTest(800, 600) {
        clientsScreen(content())

        onNodeWithTag("connectionChip", useUnmergedTree = true).assertExists()
        listOf("Reference", "Connection", "Published", "Confirmed", "Errors").forEach { onNodeWithTag("publishersHeader:$it", useUnmergedTree = true).assertExists() }
        listOf("Consumer", "Connection", "Offset", "Behind", "Status").forEach { onNodeWithTag("consumersHeader:$it", useUnmergedTree = true).assertExists() }
    }

    @Test
    fun `publisher and consumer rows render rolling velocity and lag trend sparklines when present in state`() = runDesktopComposeUiTest(1280, 800) {
        val base = content()
        val pubKey = base.publishers.first().key
        val consKey = "analytics"
        val pubSpark = dev.caerbannog.core.streams.MetricSparkline(
            samples = listOf(48_000L, 48_100L),
            normalizedPoints = listOf(0f, 1f),
            deltaPerTick = 100L,
            deltaPerSecond = 20L,
            direction = dev.caerbannog.core.streams.TrendDirection.Growing,
            label = "+20/s",
            semantic = dev.caerbannog.core.streams.SparklineSemantic.Velocity,
        )
        val lagSpark = dev.caerbannog.core.streams.MetricSparkline(
            samples = listOf(469L, 349L),
            normalizedPoints = listOf(1f, 0f),
            deltaPerTick = -120L,
            deltaPerSecond = -24L,
            direction = dev.caerbannog.core.streams.TrendDirection.Draining,
            label = "↘ Draining (-120)",
            semantic = dev.caerbannog.core.streams.SparklineSemantic.ConsumerLag,
        )
        val stateWithSparklines = base.copy(
            publisherSparklines = mapOf(pubKey to pubSpark),
            consumerLagSparklines = mapOf(consKey to lagSpark),
        )
        clientsScreen(stateWithSparklines)

        onNodeWithTag("publisherSparkline:$pubKey", useUnmergedTree = true).assertExists()
        onNodeWithTag("publisherSparkline:$pubKey:label", useUnmergedTree = true).assertTextEquals("+20/s")
        onNodeWithTag("consumerLagTrend:$consKey", useUnmergedTree = true).assertExists()
        onNodeWithTag("consumerLagTrend:$consKey:label", useUnmergedTree = true).assertTextEquals("↘ Draining (-120)")
    }
}
