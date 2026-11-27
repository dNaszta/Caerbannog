package dev.caerbannog.core.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M10-04: the contract of [defaultPatternEngine] (RE2/J, step 3): which constructs it reads, which it rejects, and that it never throws. The accepted rows are the
 * ones the live table measured as agreeing with the broker; the rejected ones are patterns the broker cannot store (400 `invalid_regexp`) and PCRE constructs RE2 does not read.
 * Red until step 3 (the actual is a stub that throws).
 */
class PatternEngineTest {
    private val engine get() = defaultPatternEngine()

    private fun contains(pattern: String, name: String): Boolean =
        (engine.compile(pattern) as? PatternCompilation.Compiled ?: error("pattern [$pattern] was rejected")).pattern.containsMatchIn(name)

    @Test
    fun `the search is unanchored and case sensitive`() {
        assertTrue(contains("ex", "my-ex.orders"))
        assertFalse(contains("EX", "ex"))
        assertFalse(contains("^ex$", "ex.orders"))
        assertTrue(contains("(?i)ex", "EX"))
    }

    @Test
    fun `constructs that the live rows measured as agreeing are read`() {
        for (pattern in listOf(".*", "^ex\\.", "[a-z]+", "[^a]", "[[:alpha:]]+", "\\d+", "\\w+", "a{1,3}", "^a|b$", "(?s)a.b", "(?m)^b$", "(?<n>ex)", "(?P<n>ex)", "\\x41", "\\p{L}+", "^(a+)+$", "a.*?b", "\\Aex\\z", "é")) {
            assertTrue(engine.compile(pattern) is PatternCompilation.Compiled, "[$pattern] should be read")
        }
    }

    @Test
    fun `constructs the broker cannot store or RE2 does not read are rejected, and compile never throws`() {
        for (pattern in listOf("[", "(", "*a", "a{2,1}", "[z-a]", "ab\\", "([unclosed", "(?=ex)ex", "ex(?!x)", "(?<=my-)ex", "(a)\\1", "a++b", "(?x) ex", "\\Aex\\Z")) {
            assertEquals(PatternCompilation.Rejected, engine.compile(pattern), "[$pattern] should be rejected")
        }
    }

    @Test
    fun `a pattern is matched in linear time, not backtracked`() {
        val started = System.nanoTime()
        assertFalse(contains("^(a+)+$", "a".repeat(255) + "b"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "a nested quantifier on 255 characters took long: the engine backtracks")
    }
}
