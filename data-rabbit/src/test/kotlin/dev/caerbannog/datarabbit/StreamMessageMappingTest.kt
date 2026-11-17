package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Codec
import com.rabbitmq.stream.Message
import com.rabbitmq.stream.codec.QpidProtonCodec
import dev.caerbannog.core.browser.PropertyValueText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // --- M9-00b-c: list and map values become readable text through the one core rule ---

    private fun deathMap(queue: String, reason: String, count: Long = 1L, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>("queue" to queue, "reason" to reason, "count" to count, "exchange" to "", "routing-keys" to listOf(queue)).apply { putAll(extra) }

    /**
     * A message as the reader really gets it: built with Qpid Proton, written as AMQP 1.0 bytes and decoded by the stream client's own
     * codec (`QpidProtonCodec.decode`). The builder of the client cannot hold a list or a map, and only the wire decode gives the
     * real raw types (`Object[]` for an AMQP list), which is the whole point of M9-00b-c.
     */
    private fun overTheWire(annotations: Map<String, Any?>, applicationProperties: Map<String, Any?> = emptyMap()): Message {
        val proton = org.apache.qpid.proton.message.Message.Factory.create()
        proton.body = org.apache.qpid.proton.amqp.messaging.Data(org.apache.qpid.proton.amqp.Binary("body".encodeToByteArray()))
        proton.messageAnnotations = org.apache.qpid.proton.amqp.messaging.MessageAnnotations(
            LinkedHashMap<org.apache.qpid.proton.amqp.Symbol, Any?>().also { m -> annotations.forEach { (k, v) -> m[org.apache.qpid.proton.amqp.Symbol.valueOf(k)] = v } },
        )
        if (applicationProperties.isNotEmpty()) {
            proton.applicationProperties = org.apache.qpid.proton.amqp.messaging.ApplicationProperties(LinkedHashMap(applicationProperties))
        }
        val bytes = ByteArray(65_536)
        val length = proton.encode(bytes, 0, bytes.size)
        return codec.decode(io.netty.buffer.Unpooled.wrappedBuffer(bytes, 0, length), length)
    }

    private fun deadLetter(vararg deaths: Map<String, Any?>): Message = overTheWire(
        linkedMapOf(
            "x-exchange" to "dlx", "x-routing-key" to "orders",
            "x-first-death-reason" to "rejected", "x-first-death-queue" to "orders", "x-first-death-exchange" to "",
            "x-last-death-reason" to "rejected", "x-last-death-queue" to "orders", "x-last-death-exchange" to "",
            "x-opt-deaths" to deaths.toList().toTypedArray<Any?>(),
        ),
        mapOf("order" to 42),
    )

    @Test
    fun `x-opt-deaths of a dead letter reads as text with the queue and the reason, never as an array identity`() {
        val m = toStreamMessage(3L, 0L, deadLetter(deathMap("orders", "rejected")))

        val text = PropertyValueText.of(m.properties["x-opt-deaths"])
        assertTrue(text.contains("queue: orders"), text)
        assertTrue(text.contains("reason: rejected"), text)
        assertEquals("[{queue: orders, reason: rejected, count: 1, exchange: , routing-keys: [orders]}]", text)
    }

    @Test
    fun `x-opt-deaths sent as an AMQP list instead of an array reads the same`() {
        val m = toStreamMessage(3L, 0L, overTheWire(mapOf("x-opt-deaths" to listOf(deathMap("orders", "rejected")))))
        assertEquals("[{queue: orders, reason: rejected, count: 1, exchange: , routing-keys: [orders]}]", PropertyValueText.of(m.properties["x-opt-deaths"]))
    }

    @Test
    fun `an expired dead letter shows its ttl and two death entries show both`() {
        val m = toStreamMessage(3L, 0L, deadLetter(deathMap("a", "expired", extra = mapOf("ttl" to 1000L)), deathMap("b", "rejected", 2L)))

        val text = PropertyValueText.of(m.properties["x-opt-deaths"])
        assertTrue(text.contains("reason: expired") && text.contains("ttl: 1000"), text)
        assertTrue(text.contains("queue: b") && text.contains("count: 2"), text)
    }

    @Test
    fun `an empty x-opt-deaths list is two brackets`() {
        // Proton cannot write an empty AMQP array, so the empty case is an empty AMQP list.
        assertEquals("[]", PropertyValueText.of(toStreamMessage(3L, 0L, overTheWire(mapOf("x-opt-deaths" to emptyList<Any?>()))).properties["x-opt-deaths"]))
    }

    @Test
    fun `no mapped annotation or property value prints as an array identity, and exchange and routing key stay`() {
        val m = toStreamMessage(3L, 0L, deadLetter(deathMap("orders", "rejected")))

        for ((k, v) in m.properties + m.applicationProperties) {
            val text = PropertyValueText.of(v)
            assertFalse(text.startsWith("[L") || text.startsWith("[B@") || text.contains("java.lang.Object;"), "$k printed as $text")
        }
        assertEquals("dlx", m.exchange)
        assertEquals("orders", m.routingKey)
        assertEquals("rejected", PropertyValueText.of(m.properties["x-first-death-reason"]))
    }

    /**
     * The recorded S-C7 stream message (`fixtures/m9-00/s-c7-stream-message.json` pins its annotation and application keys):
     * a message with exactly those keys is mapped, and every one of them reads as text. Values are those of the spike results
     * (S-C7 table); the fixture holds keys only.
     */
    @Test
    fun `the S-C7 fixture keys all map and read as text`() {
        val fixture = Json.parseToJsonElement(FixtureFiles.text("m9-00/s-c7-stream-message.json")).jsonObject
        val annotationKeys = fixture.getValue("annotationKeys").jsonArray.map { it.jsonPrimitive.content }
        val applicationKeys = fixture.getValue("applicationKeys").jsonArray.map { it.jsonPrimitive.content }
        val annotations = LinkedHashMap<String, Any?>()
        for (key in annotationKeys) {
            annotations[key] = when (key) {
                "x-opt-deaths" -> listOf(deathMap("orders", "rejected"))
                "x-exchange" -> "dlx"
                "x-routing-key" -> "orders"
                else -> if (key.endsWith("-reason")) "rejected" else if (key.endsWith("-queue")) "orders" else ""
            }
        }
        val m = toStreamMessage(0L, 0L, overTheWire(annotations, applicationKeys.associateWith { 42 }))

        for (key in annotationKeys) assertTrue(key in m.properties, "annotation $key missing from ${m.properties.keys}")
        for (key in applicationKeys) assertTrue(key in m.applicationProperties, "application property $key missing")
        assertEquals("[{queue: orders, reason: rejected, count: 1, exchange: , routing-keys: [orders]}]", PropertyValueText.of(m.properties["x-opt-deaths"]))
        assertEquals("42", PropertyValueText.of(m.applicationProperties["order"]))
        assertTrue(m.properties.values.none { PropertyValueText.of(it).startsWith("[L") })
    }

    // --- Probe (M9-00b-c open point): which other value types the client hands over, and how the rule shows them ---

    @Test
    fun `probe, the raw class the client hands over for each AMQP kind, and the text the rule gives`() {
        val sym = org.apache.qpid.proton.amqp.Symbol::valueOf
        val raw = linkedMapOf<String, Any?>(
            "binary" to org.apache.qpid.proton.amqp.Binary(byteArrayOf(0x0a, 0xff.toByte())),
            "uuid" to java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            "unsigned" to org.apache.qpid.proton.amqp.UnsignedInteger.valueOf(7),
            "symbol" to sym("a-symbol"),
            "date" to java.util.Date(1_700_000_000_000L),
            "double" to 1.5,
            "list" to listOf("a", 2),
            "array" to arrayOf<Any?>(linkedMapOf("k" to "v")),
            "map" to linkedMapOf("k" to "v"),
            "char" to 'c',
        )
        val m = toStreamMessage(0L, 0L, overTheWire(raw, raw))
        val report = StringBuilder()
        for ((k, v) in m.applicationProperties) {
            val text = PropertyValueText.of(v)
            report.appendLine("PROBE application $k: ${v?.javaClass?.name} -> '$text'")
            assertFalse(text.startsWith("[B@") || text.startsWith("[L") || text.contains("@"), "application property $k printed as '$text'")
        }
        for ((k, v) in m.properties) {
            val text = PropertyValueText.of(v)
            report.appendLine("PROBE annotation $k: ${v?.javaClass?.name} -> '$text'")
            assertFalse(text.startsWith("[B@") || text.startsWith("[L") || text.contains("@"), "annotation $k printed as '$text'")
        }
        println(report)
        assertEquals("0a ff", PropertyValueText.of(m.applicationProperties["binary"]))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", PropertyValueText.of(m.applicationProperties["uuid"]))
        assertEquals("[a, 2]", PropertyValueText.of(m.applicationProperties["list"]))
        assertEquals("{k: v}", PropertyValueText.of(m.applicationProperties["map"]))
    }
}
