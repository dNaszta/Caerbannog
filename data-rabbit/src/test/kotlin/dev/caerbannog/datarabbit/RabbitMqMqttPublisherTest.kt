package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.mqttv5.client.MqttClientException
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSecurityException
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RabbitMqMqttPublisherTest {

    private val plainProfile = ConnectionProfile(
        name = "local",
        host = "localhost",
        virtualHost = "/",
        username = "guest",
        managementPort = 15672,
        streamPort = 5552,
        mqttPort = 1883,
        tls = false,
    )

    private class TestMqttWire : MqttWire {
        var connected = false
        var closed = false
        var disconnectedForcibly = 0
        var lastConnectOptions: MqttConnectionOptions? = null
        val published = mutableListOf<Pair<String, MqttMessage>>()
        var onConnect: ((MqttConnectionOptions) -> Unit)? = null
        var onPublish: ((String, MqttMessage) -> Unit)? = null

        override val isConnected: Boolean get() = connected
        override fun connect(options: MqttConnectionOptions) {
            lastConnectOptions = options
            onConnect?.invoke(options)
            connected = true
        }
        override fun publish(topic: String, message: MqttMessage) {
            onPublish?.invoke(topic, message)
            published += topic to message
        }
        override fun disconnectForcibly(timeoutMs: Long) { connected = false; disconnectedForcibly++ }
        override fun close() { closed = true }
    }

    @Test
    fun `publish rejects blank topic and wildcards without creating client`() = runBlocking {
        var clientCreated = false
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { u, id -> clientCreated = true; TestMqttWire() },
        )

        val blank = publisher.publish(PublishMqttRequest("   ", "payload".encodeToByteArray()))
        assertTrue(blank is PublishOutcome.Refused)
        assertEquals("Topic cannot be blank", (blank as PublishOutcome.Refused).reason)

        val plus = publisher.publish(PublishMqttRequest("factory/+/temp", "payload".encodeToByteArray()))
        assertTrue(plus is PublishOutcome.Refused)
        assertEquals("Topic cannot contain wildcards '+' or '#'", (plus as PublishOutcome.Refused).reason)

        val hash = publisher.publish(PublishMqttRequest("factory/#", "payload".encodeToByteArray()))
        assertTrue(hash is PublishOutcome.Refused)
        assertEquals("Topic cannot contain wildcards '+' or '#'", (hash as PublishOutcome.Refused).reason)

        assertTrue(!clientCreated, "Client factory should not be called for invalid topics")
    }

    @Test
    fun `publish connects via plain tcp, publishes message with QoS and retain, and disconnects`() = runBlocking {
        lateinit var createdClient: TestMqttWire
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "secret-guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                assertEquals("tcp://localhost:1883", uri)
                assertTrue(id.startsWith("caerbannog-pub-"))
                TestMqttWire().also { createdClient = it }
            },
        )

        val payload = "temp:24C".encodeToByteArray()
        val request = PublishMqttRequest(
            topic = "sensors/temperature",
            payload = payload,
            qos = MqttQos.EXACTLY_ONCE,
            retain = true,
        )

        val outcome = publisher.publish(request)
        assertTrue(outcome is PublishOutcome.Success)
        assertEquals("Message published to 'sensors/temperature' (QoS 2, retained)", (outcome as PublishOutcome.Success).message)

        // Verify connect options
        val opts = createdClient.lastConnectOptions
        assertNotNull(opts)
        assertEquals("guest", opts!!.userName)
        assertArrayEquals("secret-guest".toByteArray(), opts.password)
        assertTrue(opts.isCleanStart)

        // Verify published message
        assertEquals(1, createdClient.published.size)
        val (topic, msg) = createdClient.published.first()
        assertEquals("sensors/temperature", topic)
        assertEquals(2, msg.qos)
        assertTrue(msg.isRetained)
        assertArrayEquals(payload, msg.payload)

        // Verify cleanup
        assertTrue(createdClient.closed)
        assertEquals(1, createdClient.disconnectedForcibly)
    }

    @Test
    fun `publish maps MqttSecurityException to PublishOutcome Refused`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttWire().apply {
                    onConnect = { throw MqttSecurityException(MqttClientException.REASON_CODE_FAILED_AUTHENTICATION.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "cpu-high".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Refused)
        assertTrue((outcome as PublishOutcome.Refused).reason.startsWith("MQTT authentication refused"))
    }

    @Test
    fun `publish maps MqttException NOT_AUTHORIZED to PublishOutcome Refused`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttWire().apply {
                    onPublish = { _, _ -> throw MqttException(MqttReturnCode.RETURN_CODE_NOT_AUTHORIZED) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("finance/restricted", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Refused)
        assertEquals("Not authorized to publish to topic 'finance/restricted'", (outcome as PublishOutcome.Refused).reason)
    }

    @Test
    fun `publish maps MqttException SERVER_CONNECT_ERROR to PublishOutcome Failed`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttWire().apply {
                    onConnect = { throw MqttException(MqttClientException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Failed)
        assertEquals("Could not connect to MQTT broker at localhost:1883", (outcome as PublishOutcome.Failed).error)
    }

    @Test
    fun `publish maps MqttException CLIENT_TIMEOUT to PublishOutcome Failed`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttWire().apply {
                    onPublish = { _, _ -> throw MqttException(MqttClientException.REASON_CODE_CLIENT_TIMEOUT.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Failed)
        assertEquals("MQTT publish timed out after 10 seconds", (outcome as PublishOutcome.Failed).error)
    }

    @Test
    fun `headers are sent as MQTT 5 user properties in order, and none are attached when there are no headers`() = runBlocking {
        val wires = mutableListOf<TestMqttWire>()
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile, password = "guest", transport = BrokerTransport.Plain,
            clientFactory = { _, _ -> TestMqttWire().also { wires += it } },
        )

        publisher.publish(
            PublishMqttRequest(
                "sensors/t", "x".encodeToByteArray(),
                headers = listOf(MqttHeader("trace-id", "abc"), MqttHeader("trace-id", "second"), MqttHeader("tenant", "")),
            ),
        )
        publisher.publish(PublishMqttRequest("sensors/t", "x".encodeToByteArray()))

        val props = wires[0].published.single().second.properties.userProperties.map { it.key to it.value }
        assertEquals(listOf("trace-id" to "abc", "trace-id" to "second", "tenant" to ""), props)
        assertTrue(wires[1].published.single().second.properties?.userProperties.isNullOrEmpty())
    }
}
