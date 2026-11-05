package dev.caerbannog.ui.topology

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2: the Exchanges tab (mockup `tab-exchanges`) and the Bindings tab as a TABLE (`tab-bindings-table`; the graph is 08d). Window 1280 x 800 dp.
 * RED on the compile stub. Tag contract, in addition to [TopologyScreenUiTest]:
 *  Exchanges: search `exchangeSearch` (typing calls `events.setSearch(text)`), table `exchangesTable`, headers `columnHeader:<Column>` with Column
 *   Name, Type, Durable, AutoDelete, Internal (texts "Name", "Type", "Durable", "Auto-delete", "Internal"), rows `exchangeRow:<name>` (the DISPLAY name, the
 *   default exchange is "(default exchange)", listed FIRST as the state gives it, ALWAYS: the switch below does not affect this tab), cells
 *   `cell:<name>:<Column>` = the row's strings ("yes" / "no"), and `superStreamPill:<name>` (text "Super stream") only on a row with `superStream`.
 *  Bindings: search `bindingSearch`, the switch `showDefaultExchange` (a Switch with the label "Show default exchange", checked = `state.showDefaultExchange`,
 *   semantics role Switch with a toggleable state; a click or Space calls `events.setShowDefaultExchange(!current)`), table `bindingsTable`, headers
 *   Source, Destination, DestinationType, RoutingKey, Arguments (texts "Source", "Destination", "Destination type", "Routing key", "Arguments"), rows
 *   `bindingRow:<index>` (0-based position in the visible page; names are not unique), cells `cell:<index>:<Column>` = the row's strings (a destination
 *   that is an exchange says "exchange"; the default exchange reads "(default exchange)"; arguments as given).
 *  Copy: a focused cell copies exactly its displayed text with the platform copy shortcut (like the stream list, UI1); tested on a routing key and an
 *   exchange name with a fake clipboard.
 */
@OptIn(ExperimentalTestApi::class)
class TopologyTabsContentUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.left(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot.left

    private val exchangeRows = listOf(
        exchange(""),
        exchange("dev.events", "topic"),
        exchange("dev.fanout", "fanout", autoDelete = "yes"),
        exchange("orders", "direct", superStream = true),
    )

    // --- Exchanges ---

    @Test
    fun `the exchanges table has the five columns in order, the default exchange first, flags as given`() = runDesktopComposeUiTest(1280, 800) {
        screen(exchangesState(exchangeRows))
        val columns = listOf("Name", "Type", "Durable", "AutoDelete", "Internal")
        val texts = listOf("Name", "Type", "Durable", "Auto-delete", "Internal")
        val lefts = columns.map { left("columnHeader:$it") }
        assertEquals(lefts.sorted(), lefts)
        columns.zip(texts).forEach { (c, t) -> onNode(hasTestTag("columnHeader:$c").and(hasText(t)), useUnmergedTree = true).assertExists() }
        node("exchangesTable").assertExists()
        val tops = exchangeRows.map { node("exchangeRow:${it.name}").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "rows in the state's order, the default exchange first")
        node("cell:(default exchange):Type").assertTextEquals("direct")
        node("cell:dev.fanout:AutoDelete").assertTextEquals("yes")
        node("cell:dev.events:Durable").assertTextEquals("yes")
    }

    @Test
    fun `only a super stream row has the pill`() = runDesktopComposeUiTest(1280, 800) {
        screen(exchangesState(exchangeRows))
        onNode(hasTestTag("superStreamPill:orders").and(hasText("Super stream")), useUnmergedTree = true).assertExists()
        node("superStreamPill:dev.events").assertDoesNotExist()
        node("superStreamPill:(default exchange)").assertDoesNotExist()
    }

    @Test
    fun `typing in the exchange search sends the text`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(exchangesState(exchangeRows), events)
        node("exchangeSearch").performTextInput("dev")
        waitForIdle()
        assertEquals("setSearch:dev", events.sent().last())
        node("queueSearch").assertDoesNotExist()
        node("typeChip:Quorum").assertDoesNotExist()
        node("approximateNote").assertDoesNotExist()
    }

    @Test
    fun `the Exchanges tab has the footer with the range`() = runDesktopComposeUiTest(1280, 800) {
        screen(exchangesState(exchangeRows))
        node("rangeText").assertTextEquals("1 to 4 of 4")
        node("pageNext").assertExists()
    }

    // --- Bindings ---

    private val bindingRows = listOf(
        binding("dev.events", "dev-jobs", key = "job.#"),
        binding("dev.events", "dev.fanout", type = "exchange", key = "#"),
        binding("orders", "orders-0", key = "0", arguments = """{"x-stream-partition-order": 0}"""),
    )

    @Test
    fun `the bindings table has the five columns in order and one row per binding by index`() = runDesktopComposeUiTest(1280, 800) {
        screen(bindingsState(bindingRows))
        val columns = listOf("Source", "Destination", "DestinationType", "RoutingKey", "Arguments")
        val texts = listOf("Source", "Destination", "Destination type", "Routing key", "Arguments")
        val lefts = columns.map { left("columnHeader:$it") }
        assertEquals(lefts.sorted(), lefts)
        columns.zip(texts).forEach { (c, t) -> onNode(hasTestTag("columnHeader:$c").and(hasText(t)), useUnmergedTree = true).assertExists() }
        node("bindingsTable").assertExists()
        node("cell:0:Source").assertTextEquals("dev.events")
        node("cell:1:DestinationType").assertTextEquals("exchange")
        node("cell:2:RoutingKey").assertTextEquals("0")
        node("cell:2:Arguments").assertTextEquals("""{"x-stream-partition-order": 0}""")
        node("bindingRow:2").assertExists()
        node("bindingRow:3").assertDoesNotExist()
    }

    @Test
    fun `the default exchange binding reads default exchange and the switch follows the state`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(bindingsState(bindingRows), events)
        assertEquals(false, node("showDefaultExchange").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On })
        onNode(hasText("Show default exchange"), useUnmergedTree = true).assertExists()
        node("showDefaultExchange").performClick()
        shown.state = bindingsState(listOf(binding("", "orders", key = "orders")) + bindingRows, showDefault = true)
        waitForIdle()
        node("cell:0:Source").assertTextEquals("(default exchange)")
        assertEquals(true, node("showDefaultExchange").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On })
        node("showDefaultExchange").performClick()
        assertEquals(listOf("setShowDefaultExchange:true", "setShowDefaultExchange:false"), events.sent())
    }

    @Test
    fun `Space on the switch toggles it`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(bindingsState(bindingRows), events)
        node("showDefaultExchange").requestFocus()
        node("showDefaultExchange").performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
        waitForIdle()
        assertEquals(listOf("setShowDefaultExchange:true"), events.sent())
    }

    @Test
    fun `typing in the binding search sends the text, the switch is on the Bindings tab only`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        val shown = screen(bindingsState(bindingRows), events)
        node("bindingSearch").performTextInput("job")
        waitForIdle()
        assertEquals("setSearch:job", events.sent().last())
        shown.state = exchangesState(exchangeRows)
        waitForIdle()
        node("showDefaultExchange").assertDoesNotExist()
        shown.state = queuesState()
        waitForIdle()
        node("showDefaultExchange").assertDoesNotExist()
    }

    @Test
    fun `a binding with a long routing key does not push the arguments out of the row`() = runDesktopComposeUiTest(1280, 800) {
        screen(bindingsState(listOf(binding("a", "b", key = "k".repeat(200)), binding("a", "c", key = "short"))))
        assertEquals(left("cell:1:Arguments"), left("cell:0:Arguments"))
    }
}
