package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.mqtt.MqttMessagePublisher
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttClient
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttSecurityException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Real MQTT message publisher using Eclipse Paho MQTT 3.1.1 client.
 * Connects to the profile's configured [ConnectionProfile.mqttPort] over plain TCP or TLS,
 * publishes the message with QoS and retain flags, and disconnects cleanly.
 */
class RabbitMqMqttPublisher internal constructor(
    private val profile: ConnectionProfile,
    private val password: String,
    private val transport: BrokerTransport,
    private val clientFactory: (serverUri: String, clientId: String) -> IMqttClient = { uri, id ->
        MqttClient(uri, id, MemoryPersistence())
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

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            userName = profile.username
            password = this@RabbitMqMqttPublisher.password.toCharArray()
            if (transport is BrokerTransport.Tls) {
                socketFactory = TlsSupport.managementSslContextOf(transport.trust, transport.verifyHostName).socketFactory
            }
        }

        val message = MqttMessage(request.payload).apply {
            qos = request.qos.value
            isRetained = request.retain
        }

        try {
            client.connect(options)
            client.publish(request.topic, message)
            val retainDesc = if (request.retain) ", retained" else ""
            PublishOutcome.Success("Message published to '${request.topic}' (QoS ${request.qos.value}$retainDesc)")
        } catch (e: MqttSecurityException) {
            PublishOutcome.Refused("MQTT authentication refused: ${e.message ?: "invalid credentials"}")
        } catch (e: MqttException) {
            when (e.reasonCode.toShort()) {
                MqttException.REASON_CODE_NOT_AUTHORIZED ->
                    PublishOutcome.Refused("Not authorized to publish to topic '${request.topic}'")
                MqttException.REASON_CODE_FAILED_AUTHENTICATION ->
                    PublishOutcome.Refused("MQTT authentication refused: invalid credentials")
                MqttException.REASON_CODE_SERVER_CONNECT_ERROR ->
                    PublishOutcome.Failed("Could not connect to MQTT broker at ${profile.host}:${profile.mqttPort}")
                MqttException.REASON_CODE_CLIENT_TIMEOUT ->
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
