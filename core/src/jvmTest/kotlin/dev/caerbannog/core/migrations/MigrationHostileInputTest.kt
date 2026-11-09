package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.nested
import dev.caerbannog.core.migrations.MigrationTestSupport.only
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-04 hostile input in the new keys. A StackOverflowError is never caught here on purpose (M5-07): the text guard must prevent it.
 */
class MigrationHostileInputTest {

    private val notValid = "the arguments are not valid or nested too deep"
    private fun queue(arguments: String) = fileWith("""{ "action": "declareQueue", "name": "q", "arguments": $arguments }""")
    private fun policy(definition: String) = fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "definition": $definition }""")

    @Test
    fun `ten thousand nested arrays in arguments are refused by the file guard`() {
        assertEquals("the file is nested too deep", reason(queue("""{ "x": ${nested(10_000)} }""")))
    }

    @Test
    fun `ten thousand nested objects in a definition are refused by the file guard`() {
        val deep = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000)
        assertEquals("the file is nested too deep", reason(policy(deep)))
    }

    @Test
    fun `ten thousand nested levels in the arguments of an exchange and a binding are refused by the file guard`() {
        val deep = nested(10_000)
        assertEquals("the file is nested too deep", reason(fileWith("""{ "action": "declareExchange", "name": "x", "arguments": { "a": $deep } }""")))
        assertEquals("the file is nested too deep", reason(fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "arguments": { "a": $deep } }""")))
    }

    @Test
    fun `32 levels inside one argument pass and 33 are refused by the conversion limit`() {
        only(queue("""{ "x": ${nested(32)} }"""))
        assertEquals(notValid, reason(queue("""{ "x": ${nested(33)} }""")))
        assertEquals(notValid, reason(policy("""{ "x": ${nested(33)} }""")))
        val objects = "{\"a\":".repeat(33) + "1" + "}".repeat(33)
        assertEquals(notValid, reason(queue("""{ "x": $objects }""")))
    }

    @Test
    fun `NaN, Infinity, a bare word and a 400-digit number are refused like in M8-01`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "SENTINELBARE", "1" + "0".repeat(399))) {
            val message = reason(queue("""{ "x": $bad }"""))
            assertEquals(notValid, message, "for $bad")
            assertFalse(message.contains("SENTINELBARE"))
        }
        assertEquals(notValid, reason(policy("""{ "max-length": NaN }""")))
    }

    @Test
    fun `a very large but valid number of arguments and a long text value are accepted and typed`() {
        val many = (1..2_000).joinToString(", ") { "\"k$it\": $it" }
        val q = only(queue("{ $many }")) as MigrationAction.DeclareQueue
        assertEquals(2_000, q.arguments.size)
        val big = "x".repeat(100_000)
        val q2 = only(queue("""{ "x-big": "$big" }""")) as MigrationAction.DeclareQueue
        assertTrue(q2.arguments.getValue("x-big").toString().length > 100_000)
    }
}
