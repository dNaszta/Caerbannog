package dev.caerbannog.datarabbit

import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.streams.CatalogResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagementMqttCatalogTest {

    private val connectionsPath = "/api/connections"
    private val bindingsPath = "/api/bindings/%2F"

    private val connectionsJson = """
        [
          {
            "name": "127.0.0.1:54321 -> 127.0.0.1:1883",
            "user": "sensor-svc",
            "vhost": "/",
            "protocol": "MQTT 3.1.1",
            "peer_host": "127.0.0.1",
            "peer_port": 54321,
            "ssl": false,
            "connected_at": 1712345678000,
            "client_properties": {
              "client_id": "sensor-device-01",
              "clean_start": true
            }
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
          }
        ]
    """.trimIndent()

    private val routes = mapOf(
        connectionsPath to (200 to connectionsJson),
        bindingsPath to (200 to bindingsJson),
    )

    private fun <T> withCatalog(server: PlainManagementServer, block: suspend (ManagementMqttCatalog) -> T): T {
        val requests = ManagementRequests("http://127.0.0.1:${server.port}", BrokerTransport.Plain)
        val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "user", "pass")
        return try {
            runBlocking { withTimeout(5_000) { block(ManagementMqttCatalog(http, requests)) } }
        } finally {
            http.close()
        }
    }

    @Test
    fun `listClients fetches connections and bindings and parses MQTT client`() {
        PlainManagementServer(routes).use { server ->
            val result = withCatalog(server) { it.listClients("/") }
            assertTrue(result is CatalogResult.Success)
            val clients = (result as CatalogResult.Success).value

            assertEquals(1, clients.size)
            val client = clients.single()
            assertEquals("sensor-device-01", client.clientId)
            assertEquals("sensor-svc", client.username)
            assertEquals(listOf(MqttSubscription("telemetry/temperature", qos = 1)), client.subscriptions)
            assertEquals(listOf("GET $connectionsPath", "GET $bindingsPath"), server.seen.map { "${it.method} ${it.rawPath}" })
        }
    }

    @Test
    fun `listClients handles connection failure as Unreachable`() {
        val requests = ManagementRequests("http://127.0.0.1:65530", BrokerTransport.Plain)
        val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "user", "pass")
        try {
            val result = runBlocking { ManagementMqttCatalog(http, requests).listClients("/") }
            assertEquals(CatalogResult.Unreachable, result)
        } finally {
            http.close()
        }
    }
}
