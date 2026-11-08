package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.streams.CatalogResult
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("broker")
class RealBrokerMqttIntegrationTest {

    private val inMemorySecretStore = object : SecretStore {
        private val secrets = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { secrets[profileName] = password }
        override fun retrieve(profileName: String): String? = secrets[profileName]
        override fun delete(profileName: String) { secrets.remove(profileName) }
    }

    @Test
    fun testRealBrokerPlainMqttPublishAndConnect() = runBlocking {
        val profile = ConnectionProfile(
            name = "dev-plain",
            host = "localhost",
            virtualHost = "/",
            username = "admin",
            managementPort = 15672,
            streamPort = 5552,
            mqttPort = 1883,
            tls = false,
            topologyControlLevel = TopologyControlLevel.FULL_CONTROL
        )
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened, "Gateway open failed: $openResult")

        val connection = (openResult as OpenResult.Opened).connection
        try {
            // 1. Publish real message to MQTT broker
            val outcome = connection.mqttPublisher.publish(
                PublishMqttRequest(
                    topic = "dev/sensors/temperature",
                    payload = "24.5 °C".toByteArray(Charsets.UTF_8),
                    qos = MqttQos.AT_LEAST_ONCE,
                    retain = true
                )
            )
            println("MQTT Publish Outcome: $outcome")
            assertTrue(outcome is PublishOutcome.Success, "Expected success: $outcome")
            assertEquals("Message published to 'dev/sensors/temperature' (QoS 1, retained)", (outcome as PublishOutcome.Success).message)

            // 2. Query MQTT catalog via management API
            val catalogResult = connection.mqtt.listClients("/")
            println("MQTT Catalog Result: $catalogResult")
            assertTrue(catalogResult is CatalogResult.Success, "Expected CatalogResult.Success: $catalogResult")
        } finally {
            connection.close()
        }
    }

    @Test
    fun testRealBrokerActiveSubscriberDiscovery() = runBlocking {
        val profile = ConnectionProfile(
            name = "dev-plain",
            host = "localhost",
            virtualHost = "/",
            username = "admin",
            managementPort = 15672,
            streamPort = 5552,
            mqttPort = 1883,
            tls = false,
            topologyControlLevel = TopologyControlLevel.FULL_CONTROL
        )
        inMemorySecretStore.store(profile.name, "dev-password")

        // Start a real active MQTT client and subscribe to a topic
        val subscriberId = "test-sensor-sub"
        val subscriber = MqttClient("tcp://localhost:1883", subscriberId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = "admin"
            password = "dev-password".toCharArray()
            isCleanSession = true
        }
        subscriber.connect(opts)
        subscriber.subscribe("dev/sensors/temperature", 1)

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened, "Gateway open failed: $openResult")

        val connection = (openResult as OpenResult.Opened).connection
        try {
            val catalogResult = connection.mqtt.listClients("/")
            assertTrue(catalogResult is CatalogResult.Success, "Expected success: $catalogResult")
            val clients = (catalogResult as CatalogResult.Success).value
            println("Active MQTT Clients found: ${clients.map { it.clientId to it.subscriptions }}")
            val foundSub = clients.firstOrNull { it.clientId == subscriberId }
            assertTrue(foundSub != null, "Client $subscriberId should be discovered in $clients")
            assertTrue(foundSub!!.subscriptions.any { it.topic == "dev/sensors/temperature" }, "Subscription should be discovered")
        } finally {
            subscriber.disconnect()
            subscriber.close()
            connection.close()
        }
    }

    @Test
    fun testRealBrokerTlsMqttPublishAndConnect() = runBlocking {
        val caFile = File("src/test/resources/tls/ca.pem").absoluteFile
        val profile = ConnectionProfile(
            name = "dev-tls",
            host = "localhost",
            virtualHost = "/",
            username = "admin",
            managementPort = 15671,
            streamPort = 5551,
            mqttPort = 8883,
            tls = true,
            caCertificatePath = caFile.absolutePath,
            topologyControlLevel = TopologyControlLevel.FULL_CONTROL
        )
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened, "Gateway open failed: $openResult")

        val connection = (openResult as OpenResult.Opened).connection
        try {
            val outcome = connection.mqttPublisher.publish(
                PublishMqttRequest(
                    topic = "dev/sensors/pressure",
                    payload = "1013.25 hPa".toByteArray(Charsets.UTF_8),
                    qos = MqttQos.EXACTLY_ONCE,
                    retain = false
                )
            )
            println("MQTT TLS Publish Outcome: $outcome")
            assertTrue(outcome is PublishOutcome.Success, "Expected success: $outcome")
            assertEquals("Message published to 'dev/sensors/pressure' (QoS 2)", (outcome as PublishOutcome.Success).message)
        } finally {
            connection.close()
        }
    }
}
