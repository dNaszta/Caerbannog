package dev.caerbannog.core.topology

import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttSubscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopologyViewModelMqttTest {

    private fun client(
        id: String,
        user: String = "user",
        vhost: String = "/",
        subscriptions: List<MqttSubscription> = emptyList(),
        connected: Boolean = true,
    ) = MqttClientRow(
        clientId = id,
        connectionName = "127.0.0.1:5000 -> 127.0.0.1:1883",
        vhost = vhost,
        username = user,
        peerHost = "127.0.0.1",
        peerPort = 5000,
        protocol = "MQTT 3.1.1",
        connectedAt = 1000L,
        ssl = false,
        cleanSession = true,
        subscriptions = subscriptions,
        connected = connected,
    )

    @Test
    fun `hasMqtt reflects profile hasMqtt setting`() = runTest {
        val harnessWithoutMqtt = TopologyHarness(this)
        harnessWithoutMqtt.connect()
        harnessWithoutMqtt.settle()
        assertFalse(harnessWithoutMqtt.state.hasMqtt)

        val harnessWithMqtt = TopologyHarness(this)
        val profileWithMqtt = harnessWithMqtt.profile.copy(mqttPort = 8883)
        harnessWithMqtt.context.value = TopologyContext(profileWithMqtt, null)
        harnessWithMqtt.h.active.connect(profileWithMqtt)
        harnessWithMqtt.settle()
        assertTrue(harnessWithMqtt.state.hasMqtt)
    }

    @Test
    fun `switching to Mqtt tab loads clients from the catalog`() = runTest {
        val harness = TopologyHarness(this)
        val mqtt = harness.h.gateway.mqttOf("uat-eu")
        mqtt.clients += client("sensor-01", user = "sensor-svc", subscriptions = listOf(MqttSubscription("sensors/temp", qos = 1)))

        harness.connect()
        harness.vm.setShown(true)
        harness.settle()

        harness.vm.setTab(TopologyTab.Mqtt)
        harness.settle()

        assertEquals(TopologyTab.Mqtt, harness.state.tab)
        assertEquals(1, harness.state.mqttClients.size)
        val view = harness.state.mqttClients.single()
        assertEquals("sensor-01", view.clientId)
        assertEquals("sensor-svc", view.username)
        assertEquals(1, view.subscriptionCount)
        assertEquals("sensors/temp", view.subscriptions.single().topic)
        assertEquals(TopologyScreen.Ready, harness.state.screen)
    }

    @Test
    fun `empty Mqtt tab shows NO_MQTT_CLIENTS message`() = runTest {
        val harness = TopologyHarness(this)
        harness.connect()
        harness.vm.setShown(true)
        harness.settle()

        harness.vm.setTab(TopologyTab.Mqtt)
        harness.settle()

        assertEquals(TopologyScreen.Empty, harness.state.screen)
        assertEquals(TopologyTexts.NO_MQTT_CLIENTS, harness.state.message)
        assertTrue(harness.state.mqttClients.isEmpty())
    }

    @Test
    fun `search filters clients by clientId, username, or subscribed topic`() = runTest {
        val harness = TopologyHarness(this)
        val mqtt = harness.h.gateway.mqttOf("uat-eu")
        mqtt.clients += client("sensor-alpha", user = "bob", subscriptions = listOf(MqttSubscription("sensors/humidity")))
        mqtt.clients += client("gateway-beta", user = "alice", subscriptions = listOf(MqttSubscription("gateways/status")))
        mqtt.clients += client("device-gamma", user = "charlie", subscriptions = listOf(MqttSubscription("alerts/critical")))

        harness.connect()
        harness.vm.setShown(true)
        harness.vm.setTab(TopologyTab.Mqtt)
        harness.settle()
        assertEquals(3, harness.state.total)

        // Filter by clientId substring
        harness.vm.setSearch("alpha")
        harness.settle()
        assertEquals(1, harness.state.mqttClients.size)
        assertEquals("sensor-alpha", harness.state.mqttClients.single().clientId)

        // Filter by username
        harness.vm.setSearch("alice")
        harness.settle()
        assertEquals(1, harness.state.mqttClients.size)
        assertEquals("gateway-beta", harness.state.mqttClients.single().clientId)

        // Filter by subscribed topic
        harness.vm.setSearch("alerts")
        harness.settle()
        assertEquals(1, harness.state.mqttClients.size)
        assertEquals("device-gamma", harness.state.mqttClients.single().clientId)

        // Search clearing restores all
        harness.vm.setSearch("")
        harness.settle()
        assertEquals(3, harness.state.total)
    }

    @Test
    fun `paging applies to MQTT clients list`() = runTest {
        val harness = TopologyHarness(this, pageSize = 2)
        val mqtt = harness.h.gateway.mqttOf("uat-eu")
        mqtt.clients += client("c1")
        mqtt.clients += client("c2")
        mqtt.clients += client("c3")
        mqtt.clients += client("c4")
        mqtt.clients += client("c5")

        harness.connect()
        harness.vm.setShown(true)
        harness.vm.setTab(TopologyTab.Mqtt)
        harness.settle()

        assertEquals(5, harness.state.total)
        assertEquals(2, harness.state.mqttClients.size)
        assertEquals(listOf("c1", "c2"), harness.state.mqttClients.map { it.clientId })
        assertEquals("1 to 2 of 5", harness.state.rangeText)
        assertTrue(harness.state.hasNextPage)
        assertFalse(harness.state.hasPreviousPage)

        harness.vm.nextPage()
        harness.settle()
        assertEquals(listOf("c3", "c4"), harness.state.mqttClients.map { it.clientId })
        assertEquals("3 to 4 of 5", harness.state.rangeText)

        harness.vm.nextPage()
        harness.settle()
        assertEquals(listOf("c5"), harness.state.mqttClients.map { it.clientId })
        assertEquals("5 to 5 of 5", harness.state.rangeText)
        assertFalse(harness.state.hasNextPage)
        assertTrue(harness.state.hasPreviousPage)
    }
}
