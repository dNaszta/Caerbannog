package dev.caerbannog.core.browser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadFormatterTest {

    private val formatter = PayloadFormatter()

    @Test
    fun `pretty print json formats indented with one field per line`() {
        val rawJson = """{"orderId":"A-1202","status":"created","items":2}"""
        val result = formatter.formatJson(rawJson.encodeToByteArray())

        assertTrue(result is JsonFormatResult.Valid)
        val formatted = result.formattedText
        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("  \"orderId\": \"A-1202\""))
        assertTrue(formatted.contains("  \"status\": \"created\""))
        assertTrue(formatted.contains("  \"items\": 2"))
    }

    @Test
    fun `valid text body is returned as text`() {
        val text = "heartbeat"
        val formatted = formatter.formatText(text.encodeToByteArray())
        assertEquals("heartbeat", formatted)
    }

    @Test
    fun `hex view formats byte sequence`() {
        val bytes = "heartbeat".encodeToByteArray()
        val hex = formatter.formatHex(bytes)
        // 68 65 61 72 74 62 65 61 74
        assertTrue(hex.contains("68 65 61 72 74 62 65 61 74"))
    }

    @Test
    fun `non-utf8 or binary payload is detected as binary and falls back to hex`() {
        // Gzip magic header or random non-text control bytes
        val binaryData = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte())
        assertTrue(formatter.isBinary(binaryData))

        val text = formatter.formatText(binaryData)
        assertNull(text) // Text view does not show garbled characters

        val inspection = formatter.inspect(
            StreamMessage(
                offset = 1206L,
                timestamp = 1700000000000L,
                payload = binaryData,
            )
        )
        assertEquals(PayloadViewKind.HEX, inspection.recommendedKind)
        assertFalse(inspection.availableKinds.contains(PayloadViewKind.TEXT))
    }

    @Test
    fun `body claiming to be json that is invalid reports invalid json and keeps text view`() {
        val invalidJson = "not json".encodeToByteArray()
        val result = formatter.formatJson(invalidJson)

        assertTrue(result is JsonFormatResult.Invalid)
        assertTrue(result.message.contains("not valid JSON", ignoreCase = true))

        val text = formatter.formatText(invalidJson)
        assertEquals("not json", text)

        val inspection = formatter.inspect(
            StreamMessage(
                offset = 1210L,
                timestamp = 1700000000000L,
                payload = invalidJson,
                properties = mapOf("content-type" to "application/json"),
            ),
            requestedKind = PayloadViewKind.JSON,
        )
        val warning = assertNotNull(inspection.validationWarning)
        assertTrue(warning.contains("not valid JSON", ignoreCase = true))
        assertTrue(inspection.availableKinds.contains(PayloadViewKind.TEXT))
    }

    @Test
    fun `very large bodies are capped and offer show all action`() {
        val largeText = "A".repeat(100_000)
        val msg = StreamMessage(
            offset = 1300L,
            timestamp = 1700000000000L,
            payload = largeText.encodeToByteArray(),
        )

        val cappedInspection = formatter.inspect(msg, capLimit = 10_000, showAll = false)
        assertTrue(cappedInspection.isCapped)
        assertEquals(10_000, cappedInspection.displayText.length)

        val allInspection = formatter.inspect(msg, capLimit = 10_000, showAll = true)
        assertFalse(allInspection.isCapped)
        assertEquals(100_000, allInspection.displayText.length)
    }

    @Test
    fun `properties and application properties are extracted`() {
        val msg = StreamMessage(
            offset = 1202L,
            timestamp = 1700000000000L,
            payload = """{"orderId":"A-1202"}""".encodeToByteArray(),
            properties = mapOf(
                "content-type" to "application/json",
                "message-id" to "A-1202",
            ),
            applicationProperties = mapOf(
                "tenant" to "eu",
                "source" to "checkout",
            )
        )

        val inspection = formatter.inspect(msg)
        assertEquals("application/json", inspection.properties["content-type"])
        assertEquals("A-1202", inspection.properties["message-id"])
        assertEquals("eu", inspection.applicationProperties["tenant"])
        assertEquals("checkout", inspection.applicationProperties["source"])
    }

    @Test
    fun `copy helpers extract exact published body and offset`() {
        val payload = """{"raw":"content"}""".encodeToByteArray()
        val msg = StreamMessage(
            offset = 1202L,
            timestamp = 1700000000000L,
            payload = payload,
        )

        assertEquals("1202", formatter.copyOffset(msg))
        assertEquals("""{"raw":"content"}""", formatter.copyBody(msg))
    }

    @Test
    fun `json array is formatted properly`() {
        val rawJson = """[{"id":1},{"id":2}]"""
        val result = formatter.formatJson(rawJson.encodeToByteArray())
        assertTrue(result is JsonFormatResult.Valid)
        assertTrue(result.formattedText.contains("\n"))
        assertTrue(result.formattedText.contains("\"id\": 1"))
    }

    @Test
    fun `empty payload is handled gracefully`() {
        val empty = ByteArray(0)
        assertFalse(formatter.isBinary(empty))
        assertEquals("", formatter.formatText(empty))
        assertEquals("", formatter.formatHex(empty))
        assertTrue(formatter.formatJson(empty) is JsonFormatResult.Invalid)
    }

    @Test
    fun `multibyte unicode characters are preserved in text view`() {
        val unicodeText = "Hello 世界 🚀"
        assertFalse(formatter.isBinary(unicodeText.encodeToByteArray()))
        assertEquals(unicodeText, formatter.formatText(unicodeText.encodeToByteArray()))
    }

    @Test
    fun `copying binary body falls back to hex formatting`() {
        val binaryData = byteArrayOf(0x00, 0x1F.toByte(), 0x8B.toByte())
        val msg = StreamMessage(
            offset = 1206L,
            timestamp = 1700000000000L,
            payload = binaryData,
        )
        val copy = formatter.copyBody(msg)
        assertTrue(copy.contains("00 1f 8b"))
    }

    // --- M9-00b-c: list and map property values are readable text ---

    private fun deadLetter() = StreamMessage(
        offset = 5L,
        timestamp = 1700000000000L,
        payload = "x".encodeToByteArray(),
        properties = mapOf(
            "contentType" to "text/plain",
            "x-opt-deaths" to arrayOf<Any?>(linkedMapOf<String, Any?>("queue" to "orders", "reason" to "rejected", "count" to 1L)),
            "x-first-death-reason" to "rejected",
        ),
        applicationProperties = mapOf("order" to 42, "tags" to listOf("a", "b"), "meta" to mapOf("k" to "v")),
    )

    @Test
    fun `inspect shows a list annotation as text and never as an array identity`() {
        val insp = formatter.inspect(deadLetter())

        assertEquals("[{queue: orders, reason: rejected, count: 1}]", insp.properties["x-opt-deaths"])
        assertEquals("rejected", insp.properties["x-first-death-reason"], "plain text values stay as they are")
        assertTrue(insp.properties.values.none { it.startsWith("[Ljava") || it.startsWith("[B@") }, "got ${insp.properties}")
    }

    @Test
    fun `inspect shows list and map application properties as text`() {
        val insp = formatter.inspect(deadLetter())

        assertEquals("42", insp.applicationProperties["order"])
        assertEquals("[a, b]", insp.applicationProperties["tags"])
        assertEquals("{k: v}", insp.applicationProperties["meta"])
    }

    @Test
    fun `inspect shows an empty list annotation as two brackets`() {
        val insp = formatter.inspect(deadLetter().copy(properties = mapOf("x-opt-deaths" to arrayOf<Any?>())))
        assertEquals("[]", insp.properties["x-opt-deaths"])
    }

    @Test
    fun `copy properties writes the same text the screen shows`() {
        val copied = formatter.copyHeadersAndProperties(deadLetter())

        assertTrue(copied.contains("  x-opt-deaths: [{queue: orders, reason: rejected, count: 1}]"), copied)
        assertTrue(copied.contains("  tags: [a, b]"), copied)
        assertFalse(copied.contains("[Ljava"), copied)
        assertFalse(copied.contains("@"), "no array identity in $copied")
    }

    @Test
    fun `the screen text of a property equals the copy text of the same property`() {
        val msg = deadLetter()
        val onScreen = formatter.inspect(msg).properties.getValue("x-opt-deaths")
        assertTrue(formatter.copyHeadersAndProperties(msg).contains("x-opt-deaths: $onScreen"))
    }
}
