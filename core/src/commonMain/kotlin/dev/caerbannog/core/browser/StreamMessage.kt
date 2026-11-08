package dev.caerbannog.core.browser

/**
 * A message read from a RabbitMQ stream.
 *
 * @property offset 0-based stream offset of this message.
 * @property timestamp Epoch timestamp in milliseconds when the message was published, or 0 if unset.
 * @property payload Raw message payload bytes.
 * @property properties Standard AMQP / Stream properties (e.g. messageId, correlationId, contentType).
 * @property applicationProperties User-defined message headers and properties.
 * @property exchange The exchange through which this message was published, if available.
 * @property routingKey The routing key used when publishing, if available.
 */
data class StreamMessage(
    val offset: Long,
    val timestamp: Long,
    val payload: ByteArray,
    val properties: Map<String, Any?> = emptyMap(),
    val applicationProperties: Map<String, Any?> = emptyMap(),
    val exchange: String? = null,
    val routingKey: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamMessage) return false
        if (offset != other.offset) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (properties != other.properties) return false
        if (applicationProperties != other.applicationProperties) return false
        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + properties.hashCode()
        result = 31 * result + applicationProperties.hashCode()
        return result
    }
}
