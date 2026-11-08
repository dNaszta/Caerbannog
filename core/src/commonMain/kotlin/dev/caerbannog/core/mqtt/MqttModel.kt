package dev.caerbannog.core.mqtt

/** MQTT Quality of Service level. */
enum class MqttQos(val value: Int) {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2);

    companion object {
        fun fromInt(value: Int): MqttQos = entries.firstOrNull { it.value == value } ?: AT_MOST_ONCE
    }
}

/** Request payload and delivery options for publishing an MQTT message. */
data class PublishMqttRequest(
    val topic: String,
    val payload: ByteArray,
    val qos: MqttQos = MqttQos.AT_MOST_ONCE,
    val retain: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublishMqttRequest) return false
        if (topic != other.topic) return false
        if (qos != other.qos) return false
        if (retain != other.retain) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retain.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

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
