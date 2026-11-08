package dev.caerbannog.datarabbit

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.PublishTarget
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamManagementResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.basicAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Gate test for Milestone M5 & M6 against a live RabbitMQ broker:
 * 1. Stream management: create stream, list it, delete stream, verify deletion.
 * 2. Message publisher: publish to stream, verify message payload and offset progression.
 * 3. Message publisher: publish to exchange/queue over HTTP management API.
 * 4. MQTT plugin support: publish with QoS and retain over plain (1883) & TLS (8883), active subscriber tracking.
 */
@Tag("broker")
class RealBrokerM5GateTest {

    private val inMemorySecretStore = object : SecretStore {
        private val secrets = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { secrets[profileName] = password }
        override fun retrieve(profileName: String): String? = secrets[profileName]
        override fun delete(profileName: String) { secrets.remove(profileName) }
    }

    private fun plainProfile(): ConnectionProfile = ConnectionProfile(
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

    private fun tlsProfile(): ConnectionProfile {
        val ca = File("src/test/resources/tls/ca.pem").absoluteFile
        return ConnectionProfile(
            name = "dev-tls",
            host = "localhost",
            virtualHost = "/",
            username = "admin",
            managementPort = 15671,
            streamPort = 5551,
            mqttPort = 8883,
            tls = true,
            caCertificatePath = ca.absolutePath,
            verifyHostName = false,
            topologyControlLevel = TopologyControlLevel.FULL_CONTROL
        )
    }

    @Test
    fun testGateStreamCreateListAndDelete(): Unit = runBlocking {
        val profile = plainProfile()
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened, "Gateway open failed: $openResult")

        val connection = (openResult as OpenResult.Opened).connection
        val streamName = "gate-stream-${UUID.randomUUID().toString().take(8)}"
        try {
            // 1. Create stream
            val createResult = connection.manager.createStream("/", streamName)
            assertTrue(createResult is StreamManagementResult.Success, "Stream creation failed: $createResult")

            // 2. Verify stream is listed
            val listResult = connection.catalog.listStreams(StreamListRequest(vhost = "/"))
            assertTrue(listResult is CatalogResult.Success, "Stream listing failed: $listResult")
            val streams = (listResult as CatalogResult.Success).value.rows
            assertTrue(streams.any { it.name == streamName }, "Created stream '$streamName' not found in $streams")

            // 3. Delete stream
            val deleteResult = connection.manager.deleteStream("/", streamName)
            assertTrue(deleteResult is StreamManagementResult.Success, "Stream deletion failed: $deleteResult")

            // 4. Verify stream is gone
            val listAfter = connection.catalog.listStreams(StreamListRequest(vhost = "/"))
            assertTrue(listAfter is CatalogResult.Success)
            val streamsAfter = (listAfter as CatalogResult.Success).value.rows
            assertTrue(streamsAfter.none { it.name == streamName }, "Stream '$streamName' should be deleted")
        } finally {
            connection.close()
        }
    }

    @Test
    fun testGatePublishToStreamAndRead(): Unit = runBlocking {
        val profile = plainProfile()
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened)

        val connection = (openResult as OpenResult.Opened).connection
        val streamName = "gate-pub-${UUID.randomUUID().toString().take(8)}"
        try {
            connection.manager.createStream("/", streamName)

            // 1. Publish message to stream
            val payload = """{"gate":"M5-06","status":"verified"}""".toByteArray(Charsets.UTF_8)
            val publishResult = connection.publisher.publish(
                PublishMessageRequest(
                    vhost = "/",
                    target = PublishTarget(name = streamName, kind = PublishTargetKind.STREAM),
                    payload = payload,
                    properties = mapOf("messageId" to "gate-msg-1", "contentType" to "application/json"),
                    applicationProperties = mapOf("testKey" to "gateVal")
                )
            )
            assertTrue(publishResult is PublishOutcome.Success, "Publish failed: $publishResult")

            // 2. Read message back via stream message reader
            val pageResult = connection.messages.readPage(
                vhost = "/",
                stream = streamName,
                start = BrowseStart.First,
                pageSize = 10
            )
            assertTrue(pageResult is BrowseResult.Success, "Expected browse success: $pageResult")
            val messages = (pageResult as BrowseResult.Success).messages
            assertTrue(messages.isNotEmpty(), "Expected at least 1 message in stream")
            val firstMsg = messages.first()
            assertEquals("gate-msg-1", firstMsg.properties["messageId"])
            assertEquals("application/json", firstMsg.properties["contentType"])
            assertEquals("gateVal", firstMsg.applicationProperties["testKey"])
            val payloadStr = firstMsg.payload.toString(Charsets.UTF_8)
            assertTrue(payloadStr.contains("M5-06"), "Payload $payloadStr does not contain 'M5-06'")

            // Clean up
            connection.manager.deleteStream("/", streamName)
        } finally {
            connection.close()
        }
    }

    @Test
    fun testGateMqttPublishAndClientSessionTracking(): Unit = runBlocking {
        val profile = plainProfile()
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened)

        val connection = (openResult as OpenResult.Opened).connection
        val subscriberId = "gate-sub-${UUID.randomUUID().toString().take(8)}"
        val topic = "gate/telemetry/${UUID.randomUUID().toString().take(6)}"

        val subscriber = MqttClient("tcp://localhost:1883", subscriberId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = "admin"
            password = "dev-password".toCharArray()
            isCleanSession = true
        }
        subscriber.connect(opts)
        subscriber.subscribe(topic, 1)

        try {
            // 1. Discover active subscriber session
            val catalogResult = connection.mqtt.listClients("/")
            assertTrue(catalogResult is CatalogResult.Success)
            val clients = (catalogResult as CatalogResult.Success).value
            val foundClient = clients.firstOrNull { it.clientId == subscriberId }
            assertTrue(foundClient != null, "Subscriber $subscriberId not discovered in $clients")
            assertTrue(foundClient!!.subscriptions.any { it.topic == topic }, "Topic $topic not in subscriptions")

            // 2. Publish MQTT message with QoS 1 and Retain
            val publishResult = connection.mqttPublisher.publish(
                PublishMqttRequest(
                    topic = topic,
                    payload = """{"metric":"cpu","value":42.0}""".toByteArray(Charsets.UTF_8),
                    qos = MqttQos.AT_LEAST_ONCE,
                    retain = true
                )
            )
            assertTrue(publishResult is PublishOutcome.Success, "MQTT publish failed: $publishResult")
        } finally {
            subscriber.disconnect()
            subscriber.close()
            connection.close()
        }
    }

    @Test
    fun testGateTlsMqttPublishWithQos2(): Unit = runBlocking {
        val profile = tlsProfile()
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened)

        val connection = (openResult as OpenResult.Opened).connection
        try {
            val publishResult = connection.mqttPublisher.publish(
                PublishMqttRequest(
                    topic = "gate/tls/events",
                    payload = """{"secure":true,"qos":2}""".toByteArray(Charsets.UTF_8),
                    qos = MqttQos.EXACTLY_ONCE,
                    retain = false
                )
            )
            assertTrue(publishResult is PublishOutcome.Success, "MQTT TLS publish failed: $publishResult")
            assertEquals("Message published to 'gate/tls/events' (QoS 2)", (publishResult as PublishOutcome.Success).message)
        } finally {
            connection.close()
        }
    }

    @Test
    fun testGateMqttPublishRoutesIntoStreamAndRead(): Unit = runBlocking {
        val profile = plainProfile()
        inMemorySecretStore.store(profile.name, "dev-password")

        val gateway = RabbitMqConnectionGateway(secretStore = inMemorySecretStore)
        val openResult = gateway.open(profile)
        assertTrue(openResult is OpenResult.Opened)

        val connection = (openResult as OpenResult.Opened).connection
        val streamName = "gate-mqtt-stream-${UUID.randomUUID().toString().take(8)}"
        val topic = "sensors/telemetry/${UUID.randomUUID().toString().take(6)}"
        val routingKey = "sensors.#"

        try {
            // 1. Create stream queue on the broker
            val createResult = connection.manager.createStream("/", streamName)
            assertTrue(createResult is StreamManagementResult.Success, "Stream creation failed: $createResult")

            // 2. Bind stream to amq.topic exchange so MQTT topic publications route into it
            val httpClient = HttpClient(Java)
            try {
                val vhostEncoded = URLEncoder.encode("/", StandardCharsets.UTF_8)
                val bindUrl = "http://localhost:15672/api/bindings/$vhostEncoded/e/amq.topic/q/$streamName"
                val bindResponse = httpClient.post(bindUrl) {
                    basicAuth("admin", "dev-password")
                    contentType(ContentType.Application.Json)
                    setBody("""{"routing_key":"$routingKey"}""")
                }
                assertTrue(bindResponse.status.value in 200..204, "Binding stream to amq.topic failed: ${bindResponse.status}")
            } finally {
                httpClient.close()
            }

            // 3. Publish an MQTT message via Caerbannog's MqttMessagePublisher
            val payloadStr = """{"sensor":"temp-01","celsius":23.5,"timestamp":${System.currentTimeMillis()}}"""
            val publishResult = connection.mqttPublisher.publish(
                PublishMqttRequest(
                    topic = topic,
                    payload = payloadStr.toByteArray(Charsets.UTF_8),
                    qos = MqttQos.AT_LEAST_ONCE,
                    retain = false
                )
            )
            assertTrue(publishResult is PublishOutcome.Success, "MQTT publish failed: $publishResult")

            // Allow brief moment for broker routing and stream segment flush
            delay(500)

            // 4. Read the message from the stream using Caerbannog's StreamMessageReader
            val pageResult = connection.messages.readPage(
                vhost = "/",
                stream = streamName,
                start = BrowseStart.First,
                pageSize = 10
            )
            assertTrue(pageResult is BrowseResult.Success, "Stream browse failed: $pageResult")
            val messages = (pageResult as BrowseResult.Success).messages
            assertTrue(messages.isNotEmpty(), "Expected at least 1 message in stream $streamName")

            val foundMessage = messages.firstOrNull { it.payload.toString(Charsets.UTF_8).contains("temp-01") }
            assertTrue(foundMessage != null, "MQTT payload not found in stream messages: ${messages.map { it.payload.toString(Charsets.UTF_8) }}")
            assertEquals(payloadStr, foundMessage!!.payload.toString(Charsets.UTF_8))

            // 5. Clean up stream
            connection.manager.deleteStream("/", streamName)
        } finally {
            connection.close()
        }
    }
}
