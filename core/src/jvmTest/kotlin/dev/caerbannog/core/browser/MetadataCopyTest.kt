package dev.caerbannog.core.browser

import dev.caerbannog.core.topology.InspectedQueueMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M7-09: what "Copy metadata" puts on the clipboard, for a stream message and for an inspected queue message. */
class MetadataCopyTest {

    private val formatter = PayloadFormatter()

    @Test
    fun `stream message metadata lists offset, timestamp, routing, properties and headers in that order`() {
        val text = formatter.copyHeadersAndProperties(
            StreamMessage(
                offset = 42, timestamp = 1_700_000_000_000, payload = byteArrayOf(),
                properties = linkedMapOf("contentType" to "text/plain", "userId" to "svc"),
                applicationProperties = linkedMapOf("trace-id" to "abc"),
                exchange = "amq.topic", routingKey = "sensors.room1",
            ),
        )
        assertEquals(
            """
            Offset: 42
            Timestamp: 1700000000000
            Exchange: amq.topic
            Routing Key: sensors.room1

            Properties:
              contentType: text/plain
              userId: svc

            Headers / Application Properties:
              trace-id: abc
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a stream message without routing, properties or headers has only offset and timestamp`() {
        val text = formatter.copyHeadersAndProperties(StreamMessage(offset = 0, timestamp = 5, payload = byteArrayOf(), exchange = "", routingKey = null))
        assertEquals("Offset: 0\nTimestamp: 5", text)
    }

    @Test
    fun `a null property value copies as empty text, not the word null`() {
        val text = formatter.copyHeadersAndProperties(
            StreamMessage(offset = 1, timestamp = 1, payload = byteArrayOf(), properties = mapOf("replyTo" to null), applicationProperties = mapOf("h" to null)),
        )
        assertFalse(text.contains("null"), text)
        assertTrue(text.contains("  replyTo: "), text)
    }

    private fun queueMessage(
        exchange: String = "", routingKey: String = "", redelivered: Boolean = false, count: Long? = null,
        properties: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(),
    ) = InspectedQueueMessage(
        index = 1, payload = "x".encodeToByteArray(), payloadBytes = 1, payloadEncoding = "string", redelivered = redelivered,
        exchange = exchange, routingKey = routingKey, messageCount = count, properties = properties, headers = headers,
    )

    @Test
    fun `queue message metadata names the default exchange and an empty routing key in words`() {
        assertEquals(
            "Exchange: (default exchange)\nRouting key: (empty)\nRedelivered: no\nPayload size: 1 B (string)",
            queueMessage().formatMetadataForCopy(),
        )
    }

    @Test
    fun `queue message metadata shows redelivered, remaining count, delivery mode in words, properties and headers`() {
        val text = queueMessage(
            exchange = "orders", routingKey = "order.created", redelivered = true, count = 4,
            properties = linkedMapOf("delivery_mode" to "2", "content_type" to "text/plain"), headers = mapOf("trace" to "t-1"),
        ).formatMetadataForCopy()
        assertEquals(
            """
            Exchange: orders
            Routing key: order.created
            Redelivered: yes
            Payload size: 1 B (string)
            Remaining in queue: 4

            Properties:
              delivery_mode: 2 (Persistent)
              content_type: text/plain

            Headers:
              trace: t-1
            """.trimIndent(),
            text,
        )
    }
}
