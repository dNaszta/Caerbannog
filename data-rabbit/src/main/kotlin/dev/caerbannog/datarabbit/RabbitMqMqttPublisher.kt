package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.mqtt.MqttMessagePublisher
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.caerbannog.core.mqtt.MqttHeader
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttClientException
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSecurityException
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode
import org.eclipse.paho.mqttv5.common.packet.UserProperty
import java.util.UUID

/** The few client operations the publisher needs; the seam that keeps the Paho client out of unit tests. */
internal interface MqttWire {
    val isConnected: Boolean
    fun connect(options: MqttConnectionOptions)
    fun publish(topic: String, message: MqttMessage)
    fun disconnectForcibly(timeoutMs: Long)
    fun close()
}

private class PahoMqttWire(private val client: MqttClient) : MqttWire {
    override val isConnected: Boolean get() = client.isConnected
    override fun connect(options: MqttConnectionOptions) = client.connect(options)
    override fun publish(topic: String, message: MqttMessage) = client.publish(topic, message)
    override fun disconnectForcibly(timeoutMs: Long) = client.disconnectForcibly(timeoutMs)
    override fun close() = client.close()
}

/**
 * Real MQTT message publisher using the Eclipse Paho MQTT 5 client (RabbitMQ 4+).
 * Connects to the profile's configured [ConnectionProfile.mqttPort] over plain TCP or TLS,
 * publishes the message with QoS, retain flag and optional headers (MQTT 5 user properties), and disconnects cleanly.
 */
class RabbitMqMqttPublisher internal constructor(
    private val profile: ConnectionProfile,
    private val password: String,
    private val transport: BrokerTransport,
    private val clientFactory: (serverUri: String, clientId: String) -> MqttWire = { uri, id ->
        PahoMqttWire(MqttClient(uri, id, MemoryPersistence()))
    },
) : MqttMessagePublisher {

    override suspend fun publish(request: PublishMqttRequest): PublishOutcome = withContext(Dispatchers.IO) {
        if (request.topic.isBlank()) {
            return@withContext PublishOutcome.Refused("Topic cannot be blank")
        }
        if (request.topic.contains('+') || request.topic.contains('#')) {
            return@withContext PublishOutcome.Refused("Topic cannot contain wildcards '+' or '#'")
        }

        val serverUri = if (transport is BrokerTransport.Tls) {
            "ssl://${profile.host}:${profile.mqttPort}"
        } else {
            "tcp://${profile.host}:${profile.mqttPort}"
        }

        val clientId = "caerbannog-pub-${UUID.randomUUID().toString().take(8)}"
        val client = try {
            clientFactory(serverUri, clientId)
        } catch (e: Exception) {
            return@withContext PublishOutcome.Failed("Failed to initialize MQTT client: ${e.message}")
        }

        val options = MqttConnectionOptions().apply {
            isCleanStart = true
            connectionTimeout = 10
            userName = profile.username
            password = this@RabbitMqMqttPublisher.password.toByteArray()
            if (transport is BrokerTransport.Tls) {
                socketFactory = TlsSupport.managementSslContextOf(transport.trust, transport.verifyHostName).socketFactory
            }
        }

        val message = MqttMessage(request.payload).apply {
            qos = request.qos.value
            isRetained = request.retain
            if (request.headers.isNotEmpty()) properties = userPropertiesOf(request.headers)
        }

        try {
            client.connect(options)
            client.publish(request.topic, message)
            val retainDesc = if (request.retain) ", retained" else ""
            PublishOutcome.Success("Message published to '${request.topic}' (QoS ${request.qos.value}$retainDesc)")
        } catch (e: MqttSecurityException) {
            PublishOutcome.Refused("MQTT authentication refused: ${e.message ?: "invalid credentials"}")
        } catch (e: MqttException) {
            when (e.reasonCode) {
                MqttReturnCode.RETURN_CODE_NOT_AUTHORIZED, MqttClientException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                    PublishOutcome.Refused("Not authorized to publish to topic '${request.topic}'")
                MqttReturnCode.RETURN_CODE_BAD_USERNAME_OR_PASSWORD, MqttClientException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ->
                    PublishOutcome.Refused("MQTT authentication refused: invalid credentials")
                MqttClientException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() ->
                    PublishOutcome.Failed("Could not connect to MQTT broker at ${profile.host}:${profile.mqttPort}")
                MqttClientException.REASON_CODE_CLIENT_TIMEOUT.toInt() ->
                    PublishOutcome.Failed("MQTT publish timed out after 10 seconds")
                else ->
                    PublishOutcome.Failed("MQTT publish failed: ${e.message ?: "reason code ${e.reasonCode}"}")
            }
        } catch (e: Exception) {
            PublishOutcome.Failed("MQTT publish failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                if (client.isConnected) {
                    client.disconnectForcibly(1000)
                }
            } catch (_: Exception) {}
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }
}

private fun userPropertiesOf(headers: List<MqttHeader>): MqttProperties =
    MqttProperties().apply { userProperties = headers.map { UserProperty(it.key, it.value) } }
