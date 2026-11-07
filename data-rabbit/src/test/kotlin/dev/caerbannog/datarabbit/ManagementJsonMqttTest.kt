package dev.caerbannog.datarabbit

import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.streams.CatalogResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagementJsonMqttTest {

    private val connectionsJson = """
        [
          {
            "name": "127.0.0.1:54321 -> 127.0.0.1:1883",
            "node": "rabbit@node1",
            "user": "sensor-svc",
            "vhost": "/",
            "protocol": "MQTT 3.1.1",
            "peer_host": "127.0.0.1",
            "peer_port": 54321,
            "ssl": false,
            "connected_at": 1712345678000,
            "client_properties": {
              "client_id": "sensor-device-01",
              "clean_start": true,
              "connection_name": "sensor-device-01"
            }
          },
          {
            "name": "127.0.0.1:54322 -> 127.0.0.1:8883",
            "node": "rabbit@node1",
            "user": "gateway-svc",
            "vhost": "iot",
            "protocol": "MQTT 5.0",
            "peer_host": "192.168.1.100",
            "peer_port": 54322,
            "ssl": true,
            "connected_at": 1712345690000,
            "client_properties": {
              "client_id": "gateway-01",
              "clean_session": false
            }
          },
          {
            "name": "127.0.0.1:54323 -> 127.0.0.1:5552",
            "user": "stream-user",
            "vhost": "/",
            "protocol": "stream",
            "peer_host": "127.0.0.1",
            "peer_port": 54323
          }
        ]
    """.trimIndent()

    private val bindingsJson = """
        [
          {
            "source": "amq.topic",
            "vhost": "/",
            "destination": "mqtt-subscription-sensor-device-01qos1",
            "destination_type": "queue",
            "routing_key": "telemetry.temperature"
          },
          {
            "source": "amq.topic",
            "vhost": "/",
            "destination": "mqtt-subscription-sensor-device-01qos0",
            "destination_type": "queue",
            "routing_key": "alerts.*.critical.#"
          },
          {
            "source": "amq.topic",
            "vhost": "/",
            "destination": "mqtt-subscription-offline-client-02qos2",
            "destination_type": "queue",
            "routing_key": "commands.device.#"
          },
          {
            "source": "direct-exchange",
            "vhost": "/",
            "destination": "some-other-queue",
            "destination_type": "queue",
            "routing_key": "ignored"
          }
        ]
    """.trimIndent()

    @Test
    fun `filters out non-MQTT protocols and parses active clients with subscriptions`() {
        val result = ManagementJson.parseMqttClients(connectionsJson, bindingsJson, vhost = "/")
        assertTrue(result is CatalogResult.Success)
        val clients = (result as CatalogResult.Success).value

        // Should have 2 clients on vhost "/": sensor-device-01 (active) and offline-client-02 (offline)
        assertEquals(2, clients.size)

        val sensor = clients.first { it.clientId == "sensor-device-01" }
        assertEquals("127.0.0.1:54321 -> 127.0.0.1:1883", sensor.connectionName)
        assertEquals("sensor-svc", sensor.username)
        assertEquals("127.0.0.1", sensor.peerHost)
        assertEquals(54321, sensor.peerPort)
        assertEquals("MQTT 3.1.1", sensor.protocol)
        assertEquals(1712345678000L, sensor.connectedAt)
        assertFalse(sensor.ssl)
        assertEquals(true, sensor.cleanSession)
        assertTrue(sensor.connected)

        // Subscriptions translated from AMQP routing keys: '.' -> '/', '*' -> '+'
        assertEquals(
            listOf(
                MqttSubscription("telemetry/temperature", qos = 1),
                MqttSubscription("alerts/+/critical/#", qos = 0),
            ),
            sensor.subscriptions,
        )

        val offline = clients.first { it.clientId == "offline-client-02" }
        assertFalse(offline.connected)
        assertEquals(listOf(MqttSubscription("commands/device/#", qos = 2)), offline.subscriptions)
    }

    @Test
    fun `filters by vhost correctly`() {
        val result = ManagementJson.parseMqttClients(connectionsJson, bindingsJson, vhost = "iot")
        assertTrue(result is CatalogResult.Success)
        val clients = (result as CatalogResult.Success).value

        assertEquals(1, clients.size)
        val gateway = clients.single()
        assertEquals("gateway-01", gateway.clientId)
        assertEquals("iot", gateway.vhost)
        assertEquals("MQTT 5.0", gateway.protocol)
        assertTrue(gateway.ssl)
        assertEquals(false, gateway.cleanSession)
        assertTrue(gateway.connected)
        assertTrue(gateway.subscriptions.isEmpty())
    }

    @Test
    fun `handles empty or null bindings gracefully`() {
        val result = ManagementJson.parseMqttClients(connectionsJson, bindingsBody = null, vhost = "/")
        assertTrue(result is CatalogResult.Success)
        val clients = (result as CatalogResult.Success).value
        assertEquals(1, clients.size)
        assertEquals("sensor-device-01", clients.single().clientId)
        assertTrue(clients.single().subscriptions.isEmpty())
    }

    @Test
    fun `returns Unexpected for malformed connections JSON`() {
        val result = ManagementJson.parseMqttClients("not-json", bindingsJson)
        assertEquals(CatalogResult.Unexpected, result)
    }
}
