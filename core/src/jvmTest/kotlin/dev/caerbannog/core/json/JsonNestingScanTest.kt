package dev.caerbannog.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-07 (docs/tasks/M5-07-nesting-guard-for-untrusted-json.md): the shared iterative scan `JsonNestingScan.findTooDeep(text, maxDepth)`
 * and the entry point `parseUntrustedJson(text, maxDepth, json)`. Neither may recurse or throw on 10,000 levels; a StackOverflowError
 * is deliberately not caught here, JUnit reports it as a failure.
 */
class JsonNestingScanTest {

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)
    private fun objects(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    @Test
    fun `nesting exactly at the limit passes`() {
        assertNull(JsonNestingScan.findTooDeep(arrays(64), 64))
        assertNull(JsonNestingScan.findTooDeep(objects(64), 64))
    }

    @Test
    fun `nesting one over the limit is found`() {
        assertNotNull(JsonNestingScan.findTooDeep(arrays(65), 64), "65 arrays with limit 64")
        assertNotNull(JsonNestingScan.findTooDeep(objects(65), 64), "65 objects with limit 64")
    }

    @Test
    fun `the limit is the argument, not a constant`() {
        assertNull(JsonNestingScan.findTooDeep(arrays(128), 128))
        assertNotNull(JsonNestingScan.findTooDeep(arrays(129), 128))
        assertNull(JsonNestingScan.findTooDeep(arrays(32), 32))
        assertNotNull(JsonNestingScan.findTooDeep(arrays(33), 32))
    }

    @Test
    fun `brackets inside strings and escaped quotes do not count`() {
        val inString = "{\"k\":\"" + "[".repeat(10_000) + "\"}"
        val escaped = "{\"k\":\"say \\\" " + "[".repeat(10_000) + " \\\\\"}"
        assertNull(JsonNestingScan.findTooDeep(inString, 64))
        assertNull(JsonNestingScan.findTooDeep(escaped, 64))
    }

    @Test
    fun `a bracket after a string that ends in an escaped backslash still counts`() {
        val text = "[\"a\\\\\"" + ",[".repeat(70) + "]".repeat(70) + "]"
        assertNotNull(JsonNestingScan.findTooDeep(text, 64))
    }

    @Test
    fun `one megabyte of opening brackets is found without recursion`() {
        assertNotNull(JsonNestingScan.findTooDeep("[".repeat(1_000_000), 64))
        assertNotNull(JsonNestingScan.findTooDeep("{\"a\":".repeat(200_000), 64))
    }

    @Test
    fun `ten thousand levels of arrays and of objects are found`() {
        assertNotNull(JsonNestingScan.findTooDeep(arrays(10_000), 128))
        assertNotNull(JsonNestingScan.findTooDeep(objects(10_000), 128))
    }

    @Test
    fun `the section is the top-level key that holds the deep value`() {
        val text = "{\"queues\":" + arrays(100) + "}"
        assertEquals(JsonNestingScan.TooDeep("queues"), JsonNestingScan.findTooDeep(text, 64))
    }

    @Test
    fun `the section is null when the root is an array`() {
        assertEquals(JsonNestingScan.TooDeep(null), JsonNestingScan.findTooDeep(arrays(100), 64))
    }

    @Test
    fun `empty and flat text pass`() {
        assertNull(JsonNestingScan.findTooDeep("", 64))
        assertNull(JsonNestingScan.findTooDeep("{}", 64))
        assertNull(JsonNestingScan.findTooDeep("not json at all", 64))
    }

    // --- parseUntrustedJson ---

    @Test
    fun `valid JSON within the limit is Parsed`() {
        val result = parseUntrustedJson("""{"a":[1,2,{"b":true}]}""", 64)
        assertTrue(result is UntrustedJson.Parsed, "got $result")
        assertTrue((result as UntrustedJson.Parsed).element is JsonObject)
    }

    @Test
    fun `valid JSON exactly at the limit is Parsed and one over is TooDeep`() {
        val atLimit = parseUntrustedJson(arrays(64), 64)
        assertTrue(atLimit is UntrustedJson.Parsed && atLimit.element is JsonArray, "got $atLimit")
        assertEquals(UntrustedJson.TooDeep, parseUntrustedJson(arrays(65), 64))
    }

    @Test
    fun `ten thousand levels is TooDeep and does not throw`() {
        assertEquals(UntrustedJson.TooDeep, parseUntrustedJson(arrays(10_000), 128))
        assertEquals(UntrustedJson.TooDeep, parseUntrustedJson(objects(10_000), 128))
    }

    @Test
    fun `text that is not JSON is NotJson`() {
        assertEquals(UntrustedJson.NotJson, parseUntrustedJson("not json {", 64))
        assertEquals(UntrustedJson.NotJson, parseUntrustedJson("""{"a":""", 64))
        assertEquals(UntrustedJson.NotJson, parseUntrustedJson("[1,,]", 64))
    }

    @Test
    fun `the caller's Json configuration is used`() {
        val lenient = kotlinx.serialization.json.Json { isLenient = true }
        assertEquals(UntrustedJson.NotJson, parseUntrustedJson("{a:1}", 64))
        assertTrue(parseUntrustedJson("{a:1}", 64, lenient) is UntrustedJson.Parsed)
    }
}
