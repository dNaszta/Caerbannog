package dev.caerbannog.core.planner

import dev.caerbannog.core.migrate3x.PatternCompilation
import dev.caerbannog.core.migrate3x.PolicyPatternMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M9-08 slice 2, written first: `ExactPattern.of(names: List<String>): String`, the pattern a retention policy gets so it covers exactly the streams the
 * form added: `^name$` for one name and `^(a|b)$` for several, every character of `\.^$*+?()[]{}|` escaped with a backslash. `M9-09` reuses it (and adds `nameOf`).
 * Checked against the app's own matcher, the one `PatternMatches` uses, not against `kotlin.text.Regex`. This test lives in `core.planner` and calls the
 * `internal` matcher of `core.migrate3x`: the proof that the same module may.
 */
class ExactPatternTest {
    private fun matcher(pattern: String): PolicyPatternMatcher = when (val c = PolicyPatternMatcher.compile(pattern)) {
        is PatternCompilation.Compiled -> c.matcher
        is PatternCompilation.Unevaluable -> fail("pattern <$pattern> is not evaluable by the app's matcher: ${c.problem}")
    }

    private fun assertCoversExactly(names: List<String>, others: List<String>) {
        val pattern = ExactPattern.of(names)
        val m = matcher(pattern)
        names.forEach { assertTrue(m.matches(it), "<$pattern> must match <$it>") }
        others.forEach { assertTrue(!m.matches(it), "<$pattern> must NOT match <$it>") }
    }

    @Test
    fun `one name is anchored at both ends`() {
        assertEquals("^telemetry$", ExactPattern.of(listOf("telemetry")))
    }

    @Test
    fun `several names are a group of alternatives, in the order given`() {
        assertEquals("^(telemetry|telemetry-dln)$", ExactPattern.of(listOf("telemetry", "telemetry-dln")))
        assertEquals("^(a|b|c)$", ExactPattern.of(listOf("a", "b", "c")))
    }

    @Test
    fun `the pattern of one and of two names matches exactly those names`() {
        assertCoversExactly(listOf("telemetry"), listOf("telemetry-dln", "my-telemetry", "telemetry2", "Telemetry", "telemetr", ""))
        assertCoversExactly(listOf("telemetry", "telemetry-dln"), listOf("telemetry-d", "xtelemetry", "telemetry-dlnx", "telemetrytelemetry-dln", ""))
    }

    @Test
    fun `every special character is escaped with a backslash`() {
        "\\.^$*+?()[]{}|".forEach { ch ->
            val name = "a${ch}b"
            val pattern = ExactPattern.of(listOf(name))
            assertEquals("^a\\${ch}b$", pattern, "the character <$ch>")
            assertCoversExactly(listOf(name), listOf("axb", "ab", "a${ch}${ch}b", "a${ch}bc"))
        }
    }

    @Test
    fun `a name made only of special characters is covered exactly`() {
        val name = "\\.^$*+?()[]{}|"
        assertCoversExactly(listOf(name), listOf("", name.drop(1), name + "x", ".", "x"))
    }

    @Test
    fun `characters that need no escape stay as they are`() {
        assertEquals("^orders_v-2.dln$".replace(".", "\\."), ExactPattern.of(listOf("orders_v-2.dln")))
        assertEquals("^é-ü$", ExactPattern.of(listOf("é-ü")))
        assertCoversExactly(listOf("é-ü"), listOf("e-u", "é-ü!"))
    }

    @Test
    fun `names with special characters in a group do not leak into each other`() {
        assertCoversExactly(listOf("a.b", "c|d"), listOf("axb", "c", "d", "a", "b", "a.bc|d", "c|d|"))
    }

    @Test
    fun `a long name is covered exactly`() {
        val name = "s".repeat(120)
        assertCoversExactly(listOf(name), listOf("s".repeat(119), "s".repeat(121)))
    }

    @Test
    fun `the pattern of a very long name is still the exact text, even where the app's own matcher cannot evaluate it`() {
        // Measured on the reference: the matcher allows 256 states, so the pattern of a 255-character name is Unevaluable for the app (a hint says so);
        // the broker has no such limit. The text of the pattern must not change because of that.
        val name = "s".repeat(255)
        assertEquals("^$name$", ExactPattern.of(listOf(name)))
    }
}
