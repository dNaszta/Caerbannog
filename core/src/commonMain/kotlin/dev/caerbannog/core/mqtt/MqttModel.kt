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

/**
 * Inbound binding into a RabbitMQ stream, with MQTT topic translation when bound to `amq.topic`.
 */
data class StreamInboundRoute(
    val exchange: String,
    val routingKey: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    /** True when this binding comes from RabbitMQ's MQTT exchange (`amq.topic`). */
    val isMqttTopicExchange: Boolean
        get() = exchange == "amq.topic"

    /**
     * Converts an AMQP topic routing key (`sensors.#`, `devices.*.telemetry`) to its MQTT topic filter
     * equivalent (`sensors/#`, `devices/+/telemetry`), or null when not bound to `amq.topic`.
     */
    val mqttTopicFilter: String?
        get() = if (isMqttTopicExchange) routingKeyToMqttFilter(routingKey) else null

    /**
     * Suggests a valid, concrete MQTT publish topic that will match this binding's [routingKey]
     * (replacing `*`/`+` with `item` and `#` with `test`, or `test` if empty), or null when not bound to `amq.topic`.
     */
    val suggestedPublishTopic: String?
        get() = if (isMqttTopicExchange) routingKeyToSuggestedMqttTopic(routingKey) else null

    companion object {
        fun routingKeyToMqttFilter(routingKey: String): String {
            val trimmed = routingKey.trim()
            if (trimmed.isEmpty()) return "#"
            return trimmed.split('.', '/')
                .filter { it.isNotEmpty() }
                .joinToString("/") { seg -> if (seg == "*") "+" else seg }
        }

        fun routingKeyToSuggestedMqttTopic(routingKey: String): String {
            val trimmed = routingKey.trim()
            if (trimmed.isEmpty()) return "test"
            val segments = trimmed.split('.', '/').mapNotNull { seg ->
                when (seg) {
                    "#" -> "test"
                    "*", "+" -> "item"
                    "" -> null
                    else -> seg
                }
            }
            return if (segments.isEmpty()) "test" else segments.joinToString("/")
        }
    }
}
