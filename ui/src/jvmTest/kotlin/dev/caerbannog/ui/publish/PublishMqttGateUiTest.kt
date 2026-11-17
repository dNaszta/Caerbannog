package dev.caerbannog.ui.publish

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.buildStreamPipelineGraph
import dev.caerbannog.core.topology.TopologyScreen as Screen
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.ui.streams.RecordedClientsEvents
import dev.caerbannog.ui.streams.StreamPipelineGraphCard
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import dev.caerbannog.ui.topology.RecordedTopologyEvents
import dev.caerbannog.ui.topology.screen
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10e: the four controls that open the Publish MQTT dialog. A callback with no reason is the enabled control; no callback and a reason is the
 * control shown DISABLED (same tag, not clickable, the reason in the content description, as `publishTestMessageButton`); neither is the control absent
 * (no MQTT port, unchanged). Clicking a disabled control calls nothing.
 */
@OptIn(ExperimentalTestApi::class)
class PublishMqttGateUiTest {

    private val reason = "Test publishing is disabled: no write permission on virtual host orders."
    private val route = StreamInboundRoute(exchange = "amq.topic", routingKey = "orders.#")
    private val routeTag = "publishMqttRouteButton:orders.#"
    private val nodeTag = "pipelineNode:route:amq.topic:orders.#"

    private fun ComposeUiTest.stream(open: ((String?) -> Unit)?, reasonText: String?) = setContent {
        androidx.compose.material3.MaterialTheme {
            StreamScreen(
                stream = "orders-events",
                state = StreamClientsState(screen = StreamClientsScreen.Content),
                nowMillis = 10_000L,
                events = RecordedClientsEvents(),
                control = connected,
                controlEvents = noControlEvents,
                inboundRoutes = listOf(route),
                onOpenPublishMqttDialog = open,
                publishMqttDisabledReason = reasonText,
            )
        }
    }

    private fun ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.description(tag: String): String? =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

    /** Material buttons keep an OnClick action when disabled, so only the controls drawn by hand (the pill and the node) must lack it. */
    private fun ComposeUiTest.assertDisabledWithReason(tag: String, noClick: Boolean = false) {
        onNodeWithTag(tag).assertIsNotEnabled()
        if (noClick) onNodeWithTag(tag).assertHasNoClickAction()
        assertEquals(reason, description(tag), "the reason of $tag")
    }

    // ---- stream screen: header button, route pill, pipeline node ----

    @Test
    fun `stream screen without write permission shows the header button, the route pill and the route node disabled with the reason`() = runDesktopComposeUiTest(1280, 800) {
        stream(open = null, reasonText = reason)
        assertDisabledWithReason("publishMqttStreamButton")
        for (tag in listOf(routeTag, nodeTag)) assertDisabledWithReason(tag, noClick = true)
    }

    @Test
    fun `clicking a disabled stream screen control calls nothing`() = runDesktopComposeUiTest(1280, 800) {
        stream(open = null, reasonText = reason)
        for (tag in listOf("publishMqttStreamButton", routeTag, nodeTag)) onNodeWithTag(tag).performClick()
        assertTrue(!drawn("publishMqttDialog"), "no dialog opened")
    }

    @Test
    fun `stream screen with a callback and no reason has the three controls enabled and calling the callback`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String?>()
        stream(open = { opened += it }, reasonText = null)
        for (tag in listOf("publishMqttStreamButton", routeTag, nodeTag)) onNodeWithTag(tag).assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(3, opened.size, "each of the three controls opened once")
        assertTrue(description("publishMqttStreamButton")?.contains("no write permission") != true, "an enabled control carries no reason")
    }

    @Test
    fun `stream screen with neither callback nor reason has none of the controls`() = runDesktopComposeUiTest(1280, 800) {
        stream(open = null, reasonText = null)
        for (tag in listOf("publishMqttStreamButton", routeTag)) assertTrue(!drawn(tag), "$tag is absent")
        onNodeWithTag(nodeTag).assertHasNoClickAction()
    }

    // ---- the pipeline graph card alone ----

    @Test
    fun `the pipeline route node is disabled with the reason and does not call the callback`() = runDesktopComposeUiTest(1280, 800) {
        val graph = buildStreamPipelineGraph(stream = "orders-events", lastOffset = "9", inboundRoutes = listOf(route))
        setContent {
            androidx.compose.material3.MaterialTheme {
                StreamPipelineGraphCard(graph, onOpenPublishMqttDialog = null, publishMqttDisabledReason = reason)
            }
        }
        assertDisabledWithReason(nodeTag, noClick = true)
        onNodeWithTag(nodeTag).performClick()
        // Other nodes are unaffected: still no click action and no reason.
        onNodeWithTag("pipelineNode:stream:orders-events").assertHasNoClickAction()
    }

    // ---- topology screen, MQTT tab ----

    private val mqttTab = TopologyState(screen = Screen.Ready, tab = TopologyTab.Mqtt, hasMqtt = true)

    @Test
    fun `topology MQTT tab without write permission shows Publish message disabled with the reason`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(state = mqttTab, events = events, onOpenPublishMqttDialog = null, publishMqttDisabledReason = reason)
        assertDisabledWithReason("publishMqttButton")
        onNodeWithTag("publishMqttButton").performClick()
        assertTrue(!drawn("publishMqttDialog"), "no dialog opened")
        assertEquals(emptyList(), events.sent(), "nothing was sent to the topology screen")
    }

    @Test
    fun `topology MQTT tab with a callback and no reason has Publish message enabled`() = runDesktopComposeUiTest(1280, 800) {
        var opened = 0
        screen(state = mqttTab, onOpenPublishMqttDialog = { opened++ })
        onNodeWithTag("publishMqttButton").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `topology MQTT tab with neither callback nor reason has no Publish message button`() = runDesktopComposeUiTest(1280, 800) {
        screen(state = mqttTab)
        assertTrue(!drawn("publishMqttButton"), "absent without an MQTT port")
    }
}
