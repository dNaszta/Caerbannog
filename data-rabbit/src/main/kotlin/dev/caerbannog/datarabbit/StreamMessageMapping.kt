package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Message
import dev.caerbannog.core.browser.StreamMessage

/**
 * Maps one stream-protocol [message] to the app's [StreamMessage]; pure, so it is unit-tested without a broker.
 * Properties that are absent, empty or zero are left out, the `userId` bytes are decoded as text, and message annotations
 * are copied as properties as well (`x-exchange` and `x-routing-key` also become [StreamMessage.exchange] and `routingKey`).
 * The timestamp is the chunk timestamp when the broker gave one, else the message's own creation time, else 0.
 */
internal fun toStreamMessage(offset: Long, contextTimestamp: Long, message: Message): StreamMessage {
    val rawPayload = message.bodyAsBinary ?: ByteArray(0)
    val timestamp = contextTimestamp.takeIf { it > 0L }
        ?: message.properties?.creationTime ?: 0L
    // Extract exchange and routing key from message annotations or properties
    val exchange = message.messageAnnotations?.get("x-exchange")?.toString()
    val routingKey = message.messageAnnotations?.get("x-routing-key")?.toString()
    val props = buildMap<String, Any?> {
        message.properties?.let { p ->
            p.messageIdAsString?.let { put("messageId", it) }
            p.correlationIdAsString?.let { put("correlationId", it) }
            p.contentType?.let { put("contentType", it) }
            p.contentEncoding?.let { put("contentEncoding", it) }
            p.replyTo?.let { put("replyTo", it) }
            p.to?.let { put("to", it) }
            p.subject?.let { put("subject", it) }
            p.userId?.takeIf { it.isNotEmpty() }?.let {
                runCatching { it.decodeToString() }.getOrNull()?.takeIf { s -> s.isNotEmpty() }?.let { s -> put("userId", s) }
            }
            p.groupId?.let { put("groupId", it) }
            if (p.groupSequence > 0L) put("groupSequence", p.groupSequence)
            p.replyToGroupId?.let { put("replyToGroupId", it) }
            if (p.creationTime > 0L) put("creationTime", p.creationTime)
            if (p.absoluteExpiryTime > 0L) put("absoluteExpiryTime", p.absoluteExpiryTime)
        }
        message.messageAnnotations?.forEach { (k, v) ->
            if (v != null) put(k, v)
        }
    }
    val appProps = message.applicationProperties ?: emptyMap()
    return StreamMessage(offset, timestamp, rawPayload, props, appProps, exchange, routingKey)
}
