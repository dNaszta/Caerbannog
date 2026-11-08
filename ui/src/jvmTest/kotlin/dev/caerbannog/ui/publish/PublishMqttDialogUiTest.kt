package dev.caerbannog.ui.publish

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttState
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyScreen as Screen
import dev.caerbannog.ui.mqtt.PublishMqttDialog
import dev.caerbannog.ui.mqtt.PublishMqttEvents
import dev.caerbannog.ui.streams.RecordedClientsEvents
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import dev.caerbannog.ui.topology.RecordedTopologyEvents
import dev.caerbannog.ui.topology.screen
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PublishMqttDialogUiTest {

    private class RecordingMqttEvents : PublishMqttEvents {
        val calls = mutableListOf<String>()
        var lastTopic: String? = null
        var lastQos: MqttQos? = null
        var lastRetain: Boolean? = null
        var lastPayload: String? = null
        var published: Boolean = false
        var cancelled: Boolean = false
        var feedbackCleared: Boolean = false

        override fun setTopic(topic: String) {
            calls += "setTopic:$topic"
            lastTopic = topic
        }

        override fun setQos(qos: MqttQos) {
            calls += "setQos:$qos"
            lastQos = qos
        }

        override fun setRetain(retain: Boolean) {
            calls += "setRetain:$retain"
            lastRetain = retain
        }

        override fun setPayload(payload: String) {
            calls += "setPayload:$payload"
            lastPayload = payload
        }

        override fun publish() {
            calls += "publish"
            published = true
        }

        override fun cancel() {
            calls += "cancel"
            cancelled = true
        }

        override fun clearFeedback() {
            calls += "clearFeedback"
            feedbackCleared = true
        }
    }

    private val defaultState = PublishMqttState(
        connectionName = "prod-broker",
        topic = "",
        qos = MqttQos.AT_MOST_ONCE,
        retain = false,
        payload = "",
    )

    @Test
    fun `PublishMqttDialog renders all fields and initial disabled publish button`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("publishMqttDialogTitle").assertTextEquals("Publish MQTT message")
        onNode(hasText(defaultState.warningBanner)).assertExists()
        onNodeWithTag("publishMqttTopicInput").assertExists()
        onNodeWithTag("qosChip:0").assertHasClickAction()
        onNodeWithTag("qosChip:1").assertHasClickAction()
        onNodeWithTag("qosChip:2").assertHasClickAction()
        onNodeWithTag("publishMqttRetainSwitch").assertHasClickAction()
        onNodeWithTag("publishMqttPayloadInput").assertExists()
        onNodeWithTag("publishMqttCancelButton").assertIsEnabled()
        onNodeWithTag("publishMqttPublishButton").assertIsNotEnabled()
    }

    @Test
    fun `entering topic emits setTopic event`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("publishMqttTopicInput").performTextInput("sensors/temp")
        assertEquals("sensors/temp", events.lastTopic)
    }

    @Test
    fun `topic error displays error text and disables publish button`() = runDesktopComposeUiTest(1280, 800) {
        val state = defaultState.copy(
            topic = "sensors/+/temp",
            topicError = "Topic cannot contain wildcards '+' or '#'",
        )
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = state, events = events)
        }

        onNodeWithTag("publishMqttTopicError", useUnmergedTree = true).assertTextEquals("Topic cannot contain wildcards '+' or '#'")
        onNodeWithTag("publishMqttPublishButton").assertIsNotEnabled()
    }

    @Test
    fun `selecting QoS chips emits setQos events`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("qosChip:1").performClick()
        assertEquals(MqttQos.AT_LEAST_ONCE, events.lastQos)

        onNodeWithTag("qosChip:2").performClick()
        assertEquals(MqttQos.EXACTLY_ONCE, events.lastQos)
    }

    @Test
    fun `toggling retain switch emits setRetain event`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("publishMqttRetainSwitch").performClick()
        assertEquals(true, events.lastRetain)
    }

    @Test
    fun `entering payload emits setPayload event`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("publishMqttPayloadInput").performTextInput("24.5")
        assertEquals("24.5", events.lastPayload)
    }

    @Test
    fun `clicking publish button emits publish event when canPublish is true`() = runDesktopComposeUiTest(1280, 800) {
        val state = defaultState.copy(
            topic = "sensors/temperature",
            payload = "24.5",
        )
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = state, events = events)
        }

        onNodeWithTag("publishMqttPublishButton").assertIsEnabled()
        onNodeWithTag("publishMqttPublishButton").performClick()
        assertTrue(events.published)
    }

    @Test
    fun `clicking cancel button emits cancel event`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = defaultState, events = events)
        }

        onNodeWithTag("publishMqttCancelButton").performClick()
        assertTrue(events.cancelled)
    }

    @Test
    fun `confirmation and error banners render when present in state`() = runDesktopComposeUiTest(1280, 800) {
        val stateWithConfirmation = defaultState.copy(
            confirmation = "Message published to 'sensors/temp' (QoS 0)",
        )
        val events = RecordingMqttEvents()
        setContent {
            PublishMqttDialog(state = stateWithConfirmation, events = events)
        }

        onNodeWithTag("publishMqttConfirmation").assertExists()
        onNode(hasText("Message published to 'sensors/temp' (QoS 0)")).assertExists()
        onAllNodesWithTag("publishMqttError").assertCountEquals(0)
    }

    @Test
    fun `TopologyScreen displays publishMqttButton on MQTT tab and opens dialog`() = runDesktopComposeUiTest(1280, 800) {
        val state = TopologyState(
            screen = Screen.Ready,
            tab = TopologyTab.Mqtt,
            hasMqtt = true,
        )
        var dialogOpened = false
        val mqttEvents = RecordingMqttEvents()

        screen(
            state = state,
            events = RecordedTopologyEvents(),
            showPublishMqttDialog = false,
            publishMqttState = defaultState,
            publishMqttEvents = mqttEvents,
            onOpenPublishMqttDialog = { dialogOpened = true },
        )

        onNodeWithTag("publishMqttButton").assertExists()
        onNodeWithTag("publishMqttButton").performClick()
        assertTrue(dialogOpened)
    }

    @Test
    fun `StreamScreen displays inbound MQTT route banner and pre-fills suggested publish topic on click`() = runDesktopComposeUiTest(1280, 800) {
        val routes = listOf(
            StreamInboundRoute(exchange = "amq.topic", routingKey = "sensors.#"),
        )
        var openedWithTopic: String? = null
        val mqttEvents = RecordingMqttEvents()

        setContent {
            androidx.compose.material3.MaterialTheme {
                StreamScreen(
                    stream = "telemetry-stream",
                    state = StreamClientsState(screen = StreamClientsScreen.Content),
                    nowMillis = 10_000L,
                    events = RecordedClientsEvents(),
                    control = connected,
                    controlEvents = noControlEvents,
                    inboundRoutes = routes,
                    showPublishMqttDialog = false,
                    publishMqttState = defaultState,
                    publishMqttEvents = mqttEvents,
                    onOpenPublishMqttDialog = { topic -> openedWithTopic = topic },
                )
            }
        }

        onNodeWithTag("streamInboundRoutesBar").assertExists()
        onNodeWithTag("inboundRoute:amq.topic:sensors.#").assertExists()
        onNodeWithTag("mqttTopicFilterPill:sensors.#", useUnmergedTree = true).assertTextEquals("MQTT: sensors/#")
        onNodeWithTag("streamPipelineGraph").assertExists()
        onNodeWithTag("pipelineNode:route:amq.topic:sensors.#").assertExists()
        onNodeWithTag("pipelineNode:stream:telemetry-stream").assertExists()
        onNodeWithTag("publishMqttRouteButton:sensors.#").performClick()
        assertEquals("sensors/test", openedWithTopic)

        onNodeWithTag("togglePipelineGraphButton").performClick()
        onAllNodesWithTag("streamPipelineGraph").assertCountEquals(0)
    }
}

