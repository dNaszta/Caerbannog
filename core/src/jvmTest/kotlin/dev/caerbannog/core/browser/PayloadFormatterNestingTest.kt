package dev.caerbannog.core.browser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5-07 call site #1 (docs/tasks/M5-07-nesting-guard-for-untrusted-json.md): a message body comes from any publisher, so a body nested
 * ten thousand levels deep must give a fixed `Invalid` message, never a StackOverflowError (deliberately not caught here: JUnit fails the
 * test by itself). Limit 128. Text and Hex views do not depend on formatJson.
 */
class PayloadFormatterNestingTest {

    private val formatter = PayloadFormatter()
    private val tooDeepMessage = "The body is nested too deeply to format"

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)
    private fun objects(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    @Test
    fun `ten thousand nested arrays give the fixed too-deep message`() {
        val body = arrays(10_000)
        val result = formatter.formatJson(body.encodeToByteArray())
        assertEquals(JsonFormatResult.Invalid(tooDeepMessage), result)
        assertFalse((result as JsonFormatResult.Invalid).message.contains("[["), "the message must not echo the body")
    }

    @Test
    fun `ten thousand nested objects give the fixed too-deep message`() {
        assertEquals(JsonFormatResult.Invalid(tooDeepMessage), formatter.formatJson(objects(10_000).encodeToByteArray()))
    }

    @Test
    fun `128 levels still format and 129 are too deep`() {
        val atLimit = formatter.formatJson(arrays(128).encodeToByteArray())
        assertTrue(atLimit is JsonFormatResult.Valid, "128 levels: $atLimit")
        assertEquals(JsonFormatResult.Invalid(tooDeepMessage), formatter.formatJson(arrays(129).encodeToByteArray()))
        assertTrue(formatter.formatJson(objects(128).encodeToByteArray()) is JsonFormatResult.Valid)
        assertEquals(JsonFormatResult.Invalid(tooDeepMessage), formatter.formatJson(objects(129).encodeToByteArray()))
    }

    @Test
    fun `surrounding whitespace does not count and the guard looks at the trimmed text`() {
        val result = formatter.formatJson(("  \n" + arrays(10_000) + "\n ").encodeToByteArray())
        assertEquals(JsonFormatResult.Invalid(tooDeepMessage), result)
    }

    @Test
    fun `brackets inside a string do not make a body too deep`() {
        val body = "{\"k\":\"" + "[".repeat(10_000) + "\"}"
        assertTrue(formatter.formatJson(body.encodeToByteArray()) is JsonFormatResult.Valid)
    }

    @Test
    fun `the existing messages stay for not JSON and for non UTF-8`() {
        assertEquals(JsonFormatResult.Invalid("The body is not valid JSON"), formatter.formatJson("not json".encodeToByteArray()))
        assertEquals(
            JsonFormatResult.Invalid("The body is not valid JSON (non-UTF8 characters)"),
            formatter.formatJson(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)),
        )
    }

    @Test
    fun `ordinary JSON output is the same as before`() {
        val result = formatter.formatJson("""{"orderId":"A-1202","lines":[{"sku":"x","qty":2}]}""".encodeToByteArray())
        val expected = "{\n  \"orderId\": \"A-1202\",\n  \"lines\": [\n    {\n      \"sku\": \"x\",\n      \"qty\": 2\n    }\n  ]\n}"
        assertEquals(JsonFormatResult.Valid(expected), result)
    }

    @Test
    fun `inspect of a deep JSON body warns in the JSON view and keeps the Text and Hex views`() {
        val body = arrays(10_000)
        val message = StreamMessage(
            offset = 1211L,
            timestamp = 1700000000000L,
            payload = body.encodeToByteArray(),
            properties = mapOf("content-type" to "application/json"),
        )
        val json = formatter.inspect(message, requestedKind = PayloadViewKind.JSON, showAll = true)
        assertEquals(tooDeepMessage, json.validationWarning)

        val text = formatter.inspect(message, requestedKind = PayloadViewKind.TEXT, showAll = true)
        assertEquals(body, text.displayText)

        val hex = formatter.inspect(message, requestedKind = PayloadViewKind.HEX)
        assertTrue(hex.displayText.contains("5b 5b 5b"), "the bytes of '[[[' expected in the hex dump")
    }
}
