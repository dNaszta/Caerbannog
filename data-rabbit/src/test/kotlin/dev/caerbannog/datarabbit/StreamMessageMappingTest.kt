package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Codec
import com.rabbitmq.stream.Message
import com.rabbitmq.stream.codec.QpidProtonCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M7-09 reader mapping, no broker: real `Message` objects built with the stream client's own AMQP 1.0 codec (Qpid Proton),
 * then mapped by [toStreamMessage]. (An encode and decode round trip through the codec threw a Proton DecodeException when
 * tried by hand, so the messages are used as built.)
 */
class StreamMessageMappingTest {

    private val codec: Codec = QpidProtonCodec()

    private fun bare(body: String = "x") = codec.messageBuilder().addData(body.encodeToByteArray()).build()

    @Test
    fun `a message with only a body maps to empty properties, headers and no routing`() {
        val m = toStreamMessage(7L, 0L, bare("hello"))

        assertEquals(7L, m.offset)
        assertEquals("hello", m.payload.decodeToString())
        assertEquals(emptyMap<String, Any?>(), m.properties)
        assertEquals(emptyMap<String, Any?>(), m.applicationProperties)
        assertNull(m.exchange)
        assertNull(m.routingKey)
        assertEquals(0L, m.timestamp)
    }

    private fun fullMessage(): Message {
        val builder = codec.messageBuilder().addData("body".encodeToByteArray())
        builder.properties()
            .messageId("m-1").correlationId("c-1").contentType("application/json").contentEncoding("gzip")
            .replyTo("reply-queue").to("target").subject("greeting").userId("svc-user".encodeToByteArray())
            .groupId("g-1").groupSequence(5L).replyToGroupId("rg-1")
            .creationTime(1_700_000_000_000L).absoluteExpiryTime(1_800_000_000_000L)
        builder.applicationProperties().entry("trace-id", "abc").entry("attempt", 3)
        builder.messageAnnotations().entry("x-exchange", "amq.topic").entry("x-routing-key", "sensors.room1").entry("x-other", "kept")
        return builder.build()
    }

    @Test
    fun `every standard AMQP property is mapped, built with the stream client codec`() {
        val decoded = fullMessage()
        val m = toStreamMessage(1L, 0L, decoded)

        assertEquals("m-1", m.properties["messageId"])
        assertEquals("c-1", m.properties["correlationId"])
        assertEquals("application/json", m.properties["contentType"])
        assertEquals("gzip", m.properties["contentEncoding"])
        assertEquals("reply-queue", m.properties["replyTo"])
        assertEquals("target", m.properties["to"])
        assertEquals("greeting", m.properties["subject"])
        assertEquals("svc-user", m.properties["userId"])
        assertEquals("g-1", m.properties["groupId"])
        assertEquals(5L, m.properties["groupSequence"])
        assertEquals("rg-1", m.properties["replyToGroupId"])
        assertEquals(1_700_000_000_000L, m.properties["creationTime"])
        assertEquals(1_800_000_000_000L, m.properties["absoluteExpiryTime"])
    }

    @Test
    fun `application properties are the headers, with their original value types`() {
        val m = toStreamMessage(1L, 0L, fullMessage())
        assertEquals("abc", m.applicationProperties["trace-id"])
        assertEquals(3, (m.applicationProperties["attempt"] as Number).toInt())
    }

    @Test
    fun `exchange and routing key come from the x- annotations, and every annotation is also a property`() {
        val m = toStreamMessage(1L, 0L, fullMessage())
        assertEquals("amq.topic", m.exchange)
        assertEquals("sensors.room1", m.routingKey)
        assertEquals("amq.topic", m.properties["x-exchange"])
        assertEquals("kept", m.properties["x-other"])
    }

    @Test
    fun `a missing annotation key leaves that field null while the other one is still read`() {
        val builder = codec.messageBuilder().addData("x".encodeToByteArray())
        builder.messageAnnotations().entry("x-exchange", "amq.direct")
        val m = toStreamMessage(1L, 0L, builder.build())

        assertEquals("amq.direct", m.exchange)
        assertNull(m.routingKey)
    }

    @Test
    fun `zero or unset numeric properties and empty strings are left out`() {
        val builder = codec.messageBuilder().addData("x".encodeToByteArray())
        builder.properties().groupSequence(0L).creationTime(0L).absoluteExpiryTime(0L).userId(ByteArray(0))
        val m = toStreamMessage(1L, 0L, builder.build())

        for (key in listOf("groupSequence", "creationTime", "absoluteExpiryTime", "userId")) {
            assertFalse(key in m.properties, "$key should be absent: ${m.properties}")
        }
    }

    @Test
    fun `userId bytes are decoded as text`() {
        val builder = codec.messageBuilder().addData("x".encodeToByteArray())
        builder.properties().userId("guest".encodeToByteArray())
        assertEquals("guest", toStreamMessage(1L, 0L, builder.build()).properties["userId"])
    }

    @Test
    fun `the chunk timestamp wins, the message creation time is the fallback, otherwise zero`() {
        val withCreation = codec.messageBuilder().addData("x".encodeToByteArray())
        withCreation.properties().creationTime(1_700_000_000_000L)
        val message = withCreation.build()

        assertEquals(1_600_000_000_000L, toStreamMessage(1L, 1_600_000_000_000L, message).timestamp)
        assertEquals(1_700_000_000_000L, toStreamMessage(1L, 0L, message).timestamp)
        assertEquals(0L, toStreamMessage(1L, 0L, bare()).timestamp)
    }

    @Test
    fun `an empty body maps to an empty payload`() {
        val m = toStreamMessage(1L, 0L, codec.messageBuilder().build())
        assertTrue(m.payload.isEmpty())
    }
}
