package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Found at M8-01 step 4: the JSON tree reader accepts bare words (`abc`) as a non-string primitive, and the
 * argument conversion then threw NumberFormatException out of `parse`. The brief says `parse` never throws for bad input.
 */
class DefinitionsParserNonJsonLiteralTest {

    private val queue = """"vhost":"/","durable":true,"auto_delete":false"""

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"queues":[{"name":"a",$LITERAL_QUEUE,"arguments":{"x":SENTINEL-WORD}}]}""",
            """{"queues":[{"name":"a",$LITERAL_QUEUE,"arguments":{"x":[abc]}}]}""",
            """{"policies":[{"vhost":"/","name":"p","pattern":".*","definition":{"x":SENTINEL-WORD}}]}""",
            """{"users":[{"name":"u","limits":{"max-connections":SENTINEL-WORD}}]}""",
            """{"exchanges":[{"vhost":"/","name":"e","type":"direct","arguments":{"x":SENTINEL-WORD}}]}""",
            """{"bindings":[{"vhost":"/","source":"a","destination":"b","destination_type":"queue","arguments":{"x":SENTINEL-WORD}}]}""",
            """{"parameters":[{"component":"operator_policy","name":"n","vhost":"/","value":{"pattern":"x","definition":{"x":SENTINEL-WORD}}}]}""",
        ],
    )
    fun `a bare word as a value is rejected without throwing and without echoing it`(text: String) {
        val result = try {
            DefinitionsParser.parse(text)
        } catch (t: Throwable) {
            fail<Nothing>("parse must never throw for bad input, but threw $t")
        }
        val rejected = result as? DefinitionsParseResult.Rejected ?: fail("Expected Rejected but got ${result::class.simpleName}")
        assertTrue(rejected.reason.message.isNotEmpty())
        assertFalse(rejected.toString().contains("SENTINEL-WORD"), "the rejection must not quote the input")
    }

    @org.junit.jupiter.api.Test
    fun `a capitalised True in an argument never becomes false`() {
        val result = DefinitionsParser.parse("{\"queues\":[{\"name\":\"a\"," + queue + ",\"arguments\":{\"x\":True}}]}")
        val value = (result as? DefinitionsParseResult.Parsed)?.document?.queues?.single()?.arguments?.get("x")
        // Either rejected (preferred: not valid JSON) or Flag(true); Flag(false) silently inverts the value.
        org.junit.jupiter.api.Assertions.assertTrue(result is DefinitionsParseResult.Rejected || value == ArgumentValue.Flag(true), "True became $value")
    }

    private companion object {
        const val LITERAL_QUEUE = """"vhost":"/","durable":true,"auto_delete":false"""
    }
}
