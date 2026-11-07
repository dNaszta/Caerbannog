package dev.caerbannog.ui.topology

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.topology.MqttClientRowView
import dev.caerbannog.core.topology.TopologyScreen as Screen
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyTexts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TopologyMqttUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.left(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot.left

    private fun mqttClient(
        id: String,
        user: String = "sensor-svc",
        peer: String = "127.0.0.1:54321",
        protocol: String = "MQTT 3.1.1",
        duration: String = "5m",
        cleanSession: String = "Yes",
        subs: List<MqttSubscription> = emptyList(),
        connected: Boolean = true,
    ) = MqttClientRowView(
        clientId = id,
        connectionName = "127.0.0.1:54321 -> 127.0.0.1:1883",
        username = user,
        peerAddress = peer,
        protocol = protocol,
        connectedDuration = duration,
        ssl = false,
        cleanSession = cleanSession,
        subscriptionCount = subs.size,
        subscriptions = subs,
        connected = connected,
    )

    @Test
    fun `MQTT tab is shown only when state hasMqtt is true`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(mqttState(emptyList(), hasMqtt = false))
        node("tab:Mqtt").assertDoesNotExist()

        shown.state = mqttState(emptyList(), hasMqtt = true)
        waitForIdle()
        node("tab:Mqtt").assertExists()
    }

    @Test
    fun `clicking MQTT tab sends setTab Mqtt`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState().copy(hasMqtt = true), events)
        node("tab:Mqtt").performClick()
        waitForIdle()
        assertTrue(events.sent().contains("setTab:Mqtt"))
    }

    @Test
    fun `the MQTT table has all seven columns in order`() = runDesktopComposeUiTest(1280, 800) {
        val clients = listOf(mqttClient("sensor-01"))
        screen(mqttState(clients))

        val columns = listOf("ClientId", "User", "Protocol", "PeerAddress", "Connected", "CleanSession", "Subscriptions")
        val lefts = columns.map { left("columnHeader:$it") }
        assertEquals(lefts.sorted(), lefts, "Columns must be laid out from left to right in order")
    }

    @Test
    fun `active client row renders with Active status pill and cell contents`() = runDesktopComposeUiTest(1280, 800) {
        val client = mqttClient(
            id = "sensor-01",
            user = "alice",
            peer = "192.168.1.50:51234",
            protocol = "MQTT 5.0",
            duration = "12m",
            cleanSession = "Yes",
            subs = listOf(MqttSubscription("sensors/temperature", qos = 1)),
            connected = true,
        )
        screen(mqttState(listOf(client)))

        node("mqttStatusPill:sensor-01").assertTextEquals("Active")
        node("cell:sensor-01:ClientId").assertTextEquals("sensor-01")
        node("cell:sensor-01:User").assertTextEquals("alice")
        node("cell:sensor-01:Protocol").assertTextEquals("MQTT 5.0")
        node("cell:sensor-01:PeerAddress").assertTextEquals("192.168.1.50:51234")
        node("cell:sensor-01:Connected").assertTextEquals("12m")
        node("cell:sensor-01:CleanSession").assertTextEquals("Yes")
        node("cell:sensor-01:Subscriptions").assertTextEquals("sensors/temperature (QoS 1)")
    }

    @Test
    fun `offline client row renders with Offline status pill and no connected duration`() = runDesktopComposeUiTest(1280, 800) {
        val offlineClient = mqttClient(
            id = "offline-gateway-02",
            user = "–",
            peer = "–",
            duration = "–",
            cleanSession = "No",
            subs = listOf(MqttSubscription("commands/#", qos = 2)),
            connected = false,
        )
        screen(mqttState(listOf(offlineClient)))

        node("mqttStatusPill:offline-gateway-02").assertTextEquals("Offline")
        node("cell:offline-gateway-02:ClientId").assertTextEquals("offline-gateway-02")
        node("cell:offline-gateway-02:Subscriptions").assertTextEquals("commands/# (QoS 2)")
    }

    @Test
    fun `typing in MQTT search field sends setSearch`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(mqttState(emptyList()), events)
        node("mqttSearch").performTextInput("temp")
        waitForIdle()
        assertEquals("setSearch:temp", events.sent().last())
    }

    @Test
    fun `empty state on MQTT tab shows NO_MQTT_CLIENTS message`() = runDesktopComposeUiTest(1280, 800) {
        val emptyState = messageState(
            screen = Screen.Empty,
            message = TopologyTexts.NO_MQTT_CLIENTS,
            tab = TopologyTab.Mqtt,
            showSwitch = true,
        ).copy(hasMqtt = true)

        screen(emptyState)
        onNodeWithText(TopologyTexts.NO_MQTT_CLIENTS).assertIsDisplayed()
    }
}
