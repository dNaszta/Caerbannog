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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.core.topology.buildBindingGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI tests for the Bindings graph view and the Table / Graph view toggle (task M2-08d).
 */
@OptIn(ExperimentalTestApi::class)
class BindingsGraphUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun exRow(name: String, type: String = "direct") =
        ExchangeRow(name = name, vhost = "/", type = type, durable = true, autoDelete = false, internal = false, arguments = emptyMap(), superStream = false)

    private fun qRow(name: String, ready: Long = 0) =
        QueueRow(name = name, vhost = "/", type = "quorum", readyMessages = ready, unackedMessages = 0, consumers = 0, leader = null, members = emptyList(), online = emptyList(), policy = null, state = "running")

    private fun bRow(source: String, destination: String, type: BindingDestinationType = BindingDestinationType.QUEUE, key: String = "") =
        BindingRow(source = source, vhost = "/", destination = destination, destinationType = type, routingKey = key, arguments = emptyMap())

    private fun sampleGraphState(
        viewMode: BindingsViewMode = BindingsViewMode.Graph,
        isTooLarge: Boolean = false,
        empty: Boolean = false,
    ): TopologyState {
        val exchanges = listOf(
            exRow("dev.events", "topic"),
            exRow("dev.fanout", "fanout"),
        )
        val queues = listOf(
            qRow("orders", ready = 10),
            qRow("audit", ready = 5),
        )
        val bindings = if (empty) emptyList() else listOf(
            bRow("dev.events", "orders", BindingDestinationType.QUEUE, "order.created"),
            bRow("dev.events", "audit", BindingDestinationType.QUEUE, "#"),
        )

        val graph = if (isTooLarge) {
            dev.caerbannog.core.topology.BindingGraph(emptyList(), emptyList(), totalBindings = 100, isTooLarge = true)
        } else if (empty) {
            dev.caerbannog.core.topology.BindingGraph(emptyList(), emptyList(), totalBindings = 0, isTooLarge = false)
        } else {
            buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        }

        return TopologyState(
            screen = dev.caerbannog.core.topology.TopologyScreen.Ready,
            message = null,
            tab = TopologyTab.Bindings,
            vhost = "/",
            vhosts = listOf("/", "billing"),
            showVhostSwitch = true,
            bindingsView = viewMode,
            graph = graph,
            graphTooLargeMessage = if (isTooLarge) TopologyTexts.graphTooLarge(100) else null,
            bindings = listOf(
                binding("dev.events", "orders", key = "order.created"),
                binding("dev.events", "audit", key = "#"),
            ),
            total = 2,
            rangeText = "1 to 2 of 2",
        )
    }

    @Test
    fun `the Bindings tab shows the Table Graph toggle and clicking switches mode`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(sampleGraphState(viewMode = BindingsViewMode.Table), events)

        node("bindingsViewSwitch").assertExists()
        node("bindingsView:Table").assertExists()
        node("bindingsView:Graph").assertExists()

        node("bindingsView:Graph").performClick()
        assertEquals(listOf("setBindingsView:Graph"), events.sent())

        node("bindingsView:Table").performClick()
        assertEquals(listOf("setBindingsView:Graph", "setBindingsView:Table"), events.sent())
    }

    @Test
    fun `in graph view the graph container and nodes are drawn with correct info`() = runDesktopComposeUiTest(1280, 800) {
        screen(sampleGraphState(viewMode = BindingsViewMode.Graph))

        node("bindingsTable").assertDoesNotExist()
        node("bindingsGraphContainer").assertExists()
        node("graphNode:dev.events").assertExists()
        node("graphNode:orders").assertExists()
        node("graphNode:audit").assertExists()

        node("edgePill:order.created").assertExists()
        node("edgePill:#").assertExists()
    }

    @Test
    fun `graph nodes have accessible descriptions and can receive keyboard focus`() = runDesktopComposeUiTest(1280, 800) {
        screen(sampleGraphState(viewMode = BindingsViewMode.Graph))

        val exNode = node("graphNode:dev.events")
        exNode.requestFocus()
        val exDesc = exNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: ""
        assertTrue(exDesc.contains("exchange", ignoreCase = true), "exchange node description: $exDesc")
        assertTrue(exDesc.contains("dev.events"), "exchange node description: $exDesc")
        assertTrue(exDesc.contains("2 bindings") || exDesc.contains("2 binding"), "exchange node description: $exDesc")

        val qNode = node("graphNode:orders")
        qNode.requestFocus()
        val qDesc = qNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: ""
        assertTrue(qDesc.contains("queue", ignoreCase = true), "queue node description: $qDesc")
        assertTrue(qDesc.contains("orders"), "queue node description: $qDesc")
    }

    @Test
    fun `overflow warning is shown when graph has more than maximum bindings`() = runDesktopComposeUiTest(1280, 800) {
        screen(sampleGraphState(viewMode = BindingsViewMode.Graph, isTooLarge = true))

        node("bindingsGraphTooLarge").assertExists()
        node("graphTooLargeMessage").assertExists()
        onNode(hasText("There are more bindings than can be drawn (100 bindings). Choose an exchange or narrow the filter.")).assertExists()
        node("bindingsGraphContainer").assertDoesNotExist()
        node("graphNode:dev.events").assertDoesNotExist()
    }

    @Test
    fun `empty graph displays no bindings message`() = runDesktopComposeUiTest(1280, 800) {
        screen(sampleGraphState(viewMode = BindingsViewMode.Graph, empty = true))

        node("bindingsGraphEmpty").assertExists()
        node("bindingsGraphContainer").assertDoesNotExist()
    }
}
