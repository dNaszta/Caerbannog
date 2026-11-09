package dev.caerbannog.core.migrations

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5-07 call site #2 (docs/tasks/M5-07-nesting-guard-for-untrusted-json.md): an imported migration file nested ten thousand levels deep
 * is `Invalid("the file is nested too deep")`, limit 64, never a StackOverflowError (not caught here on purpose).
 */
class MigrationParserNestingTest {

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)
    private fun objects(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    private val valid = """
        {
          "version": "0001",
          "description": "create a queue",
          "up": [ { "action": "declareQueue", "name": "orders", "type": "quorum" } ]
        }
    """.trimIndent()

    @Test
    fun `ten thousand nested arrays are refused with the nested-too-deep reason`() {
        val result = MigrationParser.parse(arrays(10_000))
        assertEquals(MigrationValidationResult.Invalid("the file is nested too deep"), result)
    }

    @Test
    fun `ten thousand nested objects are refused and the reason does not echo the file`() {
        val result = MigrationParser.parse(objects(10_000))
        assertEquals(MigrationValidationResult.Invalid("the file is nested too deep"), result)
        assertFalse((result as MigrationValidationResult.Invalid).reason.contains("{"))
    }

    @Test
    fun `a deep value inside an otherwise valid file is refused`() {
        val text = """{ "version": "0002", "up": [], "extra": ${arrays(10_000)} }"""
        assertEquals(MigrationValidationResult.Invalid("the file is nested too deep"), MigrationParser.parse(text))
    }

    @Test
    fun `64 levels pass the guard and 65 do not`() {
        val at64 = """{ "version": "0003", "up": [], "extra": ${arrays(63)} }"""      // root object + 63 arrays = 64 levels, the limit
        val over = """{ "version": "0003", "up": [], "extra": ${arrays(64)} }"""      // root object + 64 arrays = 65 levels
        assertFalse(
            (MigrationParser.parse(at64) as? MigrationValidationResult.Invalid)?.reason == "the file is nested too deep",
            "64 levels must not be refused as too deep",
        )
        assertEquals(MigrationValidationResult.Invalid("the file is nested too deep"), MigrationParser.parse(over))
    }

    @Test
    fun `a real migration file is still valid`() {
        assertTrue(MigrationParser.parse(valid) is MigrationValidationResult.Valid, "got ${MigrationParser.parse(valid)}")
    }

    @Test
    fun `text that is not JSON keeps its message`() {
        assertEquals(MigrationValidationResult.Invalid("the file is not valid JSON"), MigrationParser.parse("not valid json {"))
    }
}
