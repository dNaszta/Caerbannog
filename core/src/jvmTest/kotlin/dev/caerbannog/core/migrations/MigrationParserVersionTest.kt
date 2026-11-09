package dev.caerbannog.core.migrations

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M5-08b (M5-07 finding): the version becomes a file name, so it must match `[A-Za-z0-9_-]+`, and a numeric version must be a whole number.
 * Red today: the parser accepts `1e3`, `NaN`, `1.5` and any text.
 */
class MigrationParserVersionTest {

    private fun file(versionJson: String) =
        """{ "version": $versionJson, "description": "d", "up": [ { "action": "declareQueue", "name": "q" } ] }"""

    private fun versionOf(versionJson: String): String = when (val r = MigrationParser.parse(file(versionJson))) {
        is MigrationValidationResult.Valid -> r.migration.version
        is MigrationValidationResult.Invalid -> fail("expected a valid file for version $versionJson but got Invalid(\"${r.reason}\")")
    }

    private fun refusal(versionJson: String): String = when (val r = MigrationParser.parse(file(versionJson))) {
        is MigrationValidationResult.Invalid -> r.reason
        is MigrationValidationResult.Valid -> fail("expected version $versionJson to be refused but it parsed as \"${r.migration.version}\"")
    }

    @Test
    fun `a whole number is the text of the number`() {
        assertEquals("3", versionOf("3"))
        assertEquals("0001", versionOf("\"0001\""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e3", "NaN", "1.5", "1.0", "Infinity"])
    fun `a numeric version that is not a whole number is refused with the wrong type reason`(number: String) {
        assertEquals("the value of \"version\" has the wrong type", refusal(number))
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1", "2026-11-09_a", "a-b_C9", "NaN"])
    fun `letters digits underscore and hyphen in a text version are accepted`(text: String) {
        assertEquals(text, versionOf("\"$text\""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["../0001", "a/b", "a\\\\b", "0001.json", "..", ".", "1.5", "a b", "a\\u0000b", "C:\\\\x", "/etc/passwd", "é", "a:b"],
    )
    fun `a text version outside the allowed characters is refused and the reason names the version, not its value`(text: String) {
        val reason = refusal("\"$text\"")
        assertTrue(reason.contains("version"), "the reason should say it is about the version: $reason")
        assertTrue(!reason.contains("passwd") && !reason.contains("0001.json"), "the reason must not echo the input: $reason")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CON", "con", "Con", "PRN", "NUL", "aux", "AUX", "COM1", "com9", "COM5", "LPT1", "lpt9"])
    fun `a Windows device name is refused as a version, in any case, and the reason does not echo it`(name: String) {
        val reason = refusal("\"$name\"")
        assertTrue(reason.contains("version"), "the reason should say it is about the version: $reason")
        if (name !in listOf("CON", "con", "Con", "NUL")) assertTrue(!reason.contains(name), "the reason must not echo the input: $reason")
    }

    @ParameterizedTest
    @ValueSource(strings = ["COM10", "COM0", "LPT0", "LPT10", "console", "auxiliary", "nullable", "CON1", "prn2", "0001"])
    fun `names that only start like a device name are accepted`(name: String) {
        assertEquals(name, versionOf("\"$name\""))
    }
}
