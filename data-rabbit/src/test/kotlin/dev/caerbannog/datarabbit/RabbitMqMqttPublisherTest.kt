package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.IMqttClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttSecurityException
import org.eclipse.paho.client.mqttv3.MqttTopic
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    private open class TestMqttClient(
        private val uri: String,
        private val clientId: String,
    ) : IMqttClient {
        var connected = AtomicBoolean(false)
        var closed = AtomicBoolean(false)
        var disconnectedForcibly = AtomicInteger(0)
        var lastConnectOptions: MqttConnectOptions? = null
        val published = mutableListOf<Pair<String, MqttMessage>>()

        var onConnect: ((MqttConnectOptions) -> Unit)? = null
        var onPublish: ((String, MqttMessage) -> Unit)? = null

        override fun connect() = connect(MqttConnectOptions())

        override fun connect(options: MqttConnectOptions) {
            lastConnectOptions = options
            onConnect?.invoke(options)
            connected.set(true)
        }

        override fun connectWithResult(options: MqttConnectOptions?): IMqttToken = throw UnsupportedOperationException()
        override fun disconnect() { connected.set(false) }
        override fun disconnect(quiesceTimeout: Long) { connected.set(false) }
        override fun disconnectForcibly() { connected.set(false); disconnectedForcibly.incrementAndGet() }
        override fun disconnectForcibly(quiesceTimeout: Long) { connected.set(false); disconnectedForcibly.incrementAndGet() }
        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) { connected.set(false); disconnectedForcibly.incrementAndGet() }
        override fun subscribe(topic: String?) = Unit
        override fun subscribe(topicFilters: Array<out String>?) = Unit
        override fun subscribe(topic: String?, qos: Int) = Unit
        override fun subscribe(topicFilters: Array<out String>?, qos: IntArray?) = Unit
        override fun subscribe(topic: String?, messageListener: IMqttMessageListener?) = Unit
        override fun subscribe(topicFilters: Array<out String>?, messageListeners: Array<out IMqttMessageListener>?) = Unit
        override fun subscribe(topic: String?, qos: Int, messageListener: IMqttMessageListener?) = Unit
        override fun subscribe(topicFilters: Array<out String>?, qos: IntArray?, messageListeners: Array<out IMqttMessageListener>?) = Unit
        override fun subscribeWithResponse(topic: String?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topic: String?, messageListener: IMqttMessageListener?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topic: String?, qos: Int): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topic: String?, qos: Int, messageListener: IMqttMessageListener?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topicFilters: Array<out String>?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topicFilters: Array<out String>?, messageListeners: Array<out IMqttMessageListener>?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topicFilters: Array<out String>?, qos: IntArray?): IMqttToken = throw UnsupportedOperationException()
        override fun subscribeWithResponse(topicFilters: Array<out String>?, qos: IntArray?, messageListeners: Array<out IMqttMessageListener>?): IMqttToken = throw UnsupportedOperationException()
        override fun unsubscribe(topic: String?) = Unit
        override fun unsubscribe(topicFilters: Array<out String>?) = Unit
        override fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean) = Unit

        override fun publish(topic: String, message: MqttMessage) {
            onPublish?.invoke(topic, message)
            published += (topic to message)
        }

        override fun setCallback(callback: MqttCallback?) = Unit
        override fun getTopic(topic: String?): MqttTopic = throw UnsupportedOperationException()
        override fun isConnected(): Boolean = connected.get()
        override fun getClientId(): String = clientId
        override fun getServerURI(): String = uri
        override fun getPendingDeliveryTokens(): Array<IMqttDeliveryToken> = emptyArray()
        override fun setManualAcks(manualAcks: Boolean) = Unit
        override fun reconnect() = Unit
        override fun messageArrivedComplete(messageId: Int, qos: Int) = Unit
        override fun close() { closed.set(true) }
    }

    @Test
    fun `publish rejects blank topic and wildcards without creating client`() = runBlocking {
        var clientCreated = false
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { u, id -> clientCreated = true; TestMqttClient(u, id) },
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
        lateinit var createdClient: TestMqttClient
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "secret-guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                assertEquals("tcp://localhost:1883", uri)
                assertTrue(id.startsWith("caerbannog-pub-"))
                TestMqttClient(uri, id).also { createdClient = it }
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
        assertArrayEquals("secret-guest".toCharArray(), opts.password)
        assertTrue(opts.isCleanSession)

        // Verify published message
        assertEquals(1, createdClient.published.size)
        val (topic, msg) = createdClient.published.first()
        assertEquals("sensors/temperature", topic)
        assertEquals(2, msg.qos)
        assertTrue(msg.isRetained)
        assertArrayEquals(payload, msg.payload)

        // Verify cleanup
        assertTrue(createdClient.closed.get())
        assertEquals(1, createdClient.disconnectedForcibly.get())
    }

    @Test
    fun `publish maps MqttSecurityException to PublishOutcome Refused`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttClient(uri, id).apply {
                    onConnect = { throw MqttSecurityException(MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "cpu-high".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Refused)
        assertTrue((outcome as PublishOutcome.Refused).reason.startsWith("MQTT authentication refused"))
    }

    @Test
    fun `publish maps MqttException REASON_CODE_NOT_AUTHORIZED to PublishOutcome Refused`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttClient(uri, id).apply {
                    onPublish = { _, _ -> throw MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("finance/restricted", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Refused)
        assertEquals("Not authorized to publish to topic 'finance/restricted'", (outcome as PublishOutcome.Refused).reason)
    }

    @Test
    fun `publish maps MqttException REASON_CODE_SERVER_CONNECT_ERROR to PublishOutcome Failed`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttClient(uri, id).apply {
                    onConnect = { throw MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Failed)
        assertEquals("Could not connect to MQTT broker at localhost:1883", (outcome as PublishOutcome.Failed).error)
    }

    @Test
    fun `publish maps MqttException REASON_CODE_CLIENT_TIMEOUT to PublishOutcome Failed`() = runBlocking {
        val publisher = RabbitMqMqttPublisher(
            profile = plainProfile,
            password = "guest",
            transport = BrokerTransport.Plain,
            clientFactory = { uri, id ->
                TestMqttClient(uri, id).apply {
                    onPublish = { _, _ -> throw MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt()) }
                }
            },
        )

        val outcome = publisher.publish(PublishMqttRequest("alerts", "payload".encodeToByteArray()))
        assertTrue(outcome is PublishOutcome.Failed)
        assertEquals("MQTT publish timed out after 10 seconds", (outcome as PublishOutcome.Failed).error)
    }
}
