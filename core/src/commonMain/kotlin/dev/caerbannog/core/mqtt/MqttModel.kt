package dev.caerbannog.core.mqtt

/** One MQTT subscription of a client session. [qos] is 0, 1, or 2. */
data class MqttSubscription(
    val topic: String,
    val qos: Int = 0,
)

/** One MQTT client connection or retained session in RabbitMQ. */
data class MqttClientRow(
    val clientId: String,
    val connectionName: String?,
    val vhost: String,
    val username: String,
    val peerHost: String,
    val peerPort: Int,
    val protocol: String,
    val connectedAt: Long?,
    val ssl: Boolean,
    val cleanSession: Boolean? = null,
    val subscriptions: List<MqttSubscription> = emptyList(),
    val connected: Boolean = true,
)
