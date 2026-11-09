package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-01 (round 0): ArgumentValue nesting is limited to MAX_ARGUMENT_DEPTH = 32 levels. Untrusted input: no stack overflow, no truncation. */
class DefinitionsParserNestingTest {

    /** `levels` lists nested in each other, closed: `[[[...]]]`. */
    private fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)

    private fun queue(levels: Int) = """{"queues":[{"name":"q","vhost":"/","arguments":{"x":${nested(levels)}}}]}"""
    private fun policy(levels: Int) = """{"policies":[{"name":"p","vhost":"/","pattern":".*","definition":{"x":${nested(levels)}}}]}"""
    private fun user(levels: Int) = """{"users":[{"name":"u","limits":{"x":${nested(levels)}}}]}"""
    private fun operatorPolicy(levels: Int) =
        """{"parameters":[{"component":"operator_policy","name":"o","vhost":"/","value":{"pattern":".*","definition":{"x":${nested(levels)}}}}]}"""

    private fun depthOf(value: ArgumentValue?): Int = when (value) {
        is ArgumentValue.Items -> 1 + (value.items.maxOfOrNull { depthOf(it) } ?: 0)
        is ArgumentValue.Table -> 1 + (value.entries.values.maxOfOrNull { depthOf(it) } ?: 0)
        else -> 0
    }

    @Test
    fun `the limit is 32`() = assertEquals(32, MAX_ARGUMENT_DEPTH)

    @Test
    fun `32 levels in a queue argument parse to the same depth`() {
        val arg = parsedDocument(queue(32)).queues.single().arguments["x"]
        assertEquals(32, depthOf(arg))
    }

    @Test
    fun `33 levels are malformed with the fixed message and no argument text`() {
        val reason = rejection(queue(33))
        assertEquals(DefinitionsRejection.Malformed("queues"), reason)
        assertEquals("""The file is not a definitions file: the section "queues" is malformed""", reason.message)
        assertFalse(reason.message.contains("["))
    }

    @Test
    fun `10000 levels are malformed and do not overflow the stack`() {
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection(queue(10_000)))
    }

    @Test
    fun `tables nest under the same limit as lists`() {
        fun tables(n: Int) = """{"queues":[{"name":"q","vhost":"/","arguments":{"x":""" + """{"k":""".repeat(n) + "1" + "}".repeat(n) + "}}]}"
        assertEquals(32, depthOf(parsedDocument(tables(32)).queues.single().arguments["x"]))
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection(tables(33)))
    }

    @Test
    fun `a policy definition has the same limit`() {
        assertEquals(32, depthOf(parsedDocument(policy(32)).policies.single().definition["x"]))
        assertEquals(DefinitionsRejection.Malformed("policies"), rejection(policy(33)))
        assertEquals(DefinitionsRejection.Malformed("policies"), rejection(policy(10_000)))
    }

    @Test
    fun `a user limits table has the same limit`() {
        assertEquals(32, depthOf(parsedDocument(user(32)).users.single().limits["x"]))
        assertEquals(DefinitionsRejection.Malformed("users"), rejection(user(33)))
        assertEquals(DefinitionsRejection.Malformed("users"), rejection(user(10_000)))
    }

    @Test
    fun `an operator policy definition has the same limit and reports the parameters section`() {
        assertEquals(32, depthOf(parsedDocument(operatorPolicy(32)).operatorPolicies.single().definition["x"]))
        assertEquals(DefinitionsRejection.Malformed("parameters"), rejection(operatorPolicy(33)))
        assertEquals(DefinitionsRejection.Malformed("parameters"), rejection(operatorPolicy(10_000)))
    }

    @Test
    fun `exchange and binding arguments have the limit too`() {
        val ex = """{"exchanges":[{"name":"e","vhost":"/","type":"direct","arguments":{"x":${nested(33)}}}]}"""
        val b = """{"bindings":[{"source":"e","destination":"q","destination_type":"queue","vhost":"/","arguments":{"x":${nested(33)}}}]}"""
        assertEquals(DefinitionsRejection.Malformed("exchanges"), rejection(ex))
        assertEquals(DefinitionsRejection.Malformed("bindings"), rejection(b))
    }

    @Test
    fun `a wide but shallow value is fine`() {
        val items = (1..10_000).joinToString(",")
        val arg = parsedDocument("""{"queues":[{"name":"q","vhost":"/","arguments":{"x":[$items]}}]}""").queues.single().arguments["x"]
        assertTrue(arg is ArgumentValue.Items && arg.items.size == 10_000)
    }
}
