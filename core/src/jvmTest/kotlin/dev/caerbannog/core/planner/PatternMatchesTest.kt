package dev.caerbannog.core.planner

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: `PatternMatches.of(pattern, names)` over the app's own matcher (`PolicyPatternMatcher`, `internal` in `core.migrate3x`: the same
 * module, so `core.planner` may call it, checked 2026-11-17 by `ExactPatternTest` calling it from this package). Contract:
 *   sealed interface PatternMatchResult { data class Matches(val names: List<String>); data object Unevaluable }
 *   object PatternMatches { fun of(pattern: String, names: Collection<String>): PatternMatchResult }
 * The names come back in the order given; a pattern matches somewhere in a name unless it says `^` or `$` (the broker's rule); it is never `kotlin.text.Regex`.
 */
class PatternMatchesTest {
    private val names = listOf("orders", "invoices", "orders-archive", "reorders", "Orders")

    private fun matches(pattern: String, all: Collection<String> = names) = (PatternMatches.of(pattern, all) as PatternMatchResult.Matches).names

    @Test
    fun `an anchored pattern matches the names it starts with, in the order given`() {
        assertEquals(listOf("orders", "orders-archive"), matches("^orders"))
    }

    @Test
    fun `an unanchored pattern matches anywhere in the name`() {
        assertEquals(listOf("orders", "orders-archive", "reorders"), matches("orders"))
    }

    @Test
    fun `both anchors match the whole name only`() {
        assertEquals(listOf("orders"), matches("^orders$"))
    }

    @Test
    fun `matching is case sensitive`() {
        assertEquals(listOf("Orders"), matches("^O"))
    }

    @Test
    fun `alternation and groups`() {
        assertEquals(listOf("orders", "invoices"), matches("^(orders|invoices)$"))
    }

    @Test
    fun `a match-all pattern and no names`() {
        assertEquals(names, matches(".*"))
        assertEquals(emptyList(), matches("^orders", emptyList()))
        assertEquals(emptyList(), matches("^zzz"))
    }

    @Test
    fun `a duplicate name is reported as given`() {
        assertEquals(listOf("a", "a"), matches("^a$", listOf("a", "b", "a")))
    }

    @Test
    fun `a pattern outside what the app can evaluate is unevaluable, never an exception`() {
        listOf("(?=orders)", "(?<=a)b", "(a)\\1", "(", "[", "*").forEach {
            assertEquals(PatternMatchResult.Unevaluable, PatternMatches.of(it, names), "pattern <$it>")
        }
    }

    @Test
    fun `a pattern over 1024 characters is unevaluable`() {
        assertEquals(PatternMatchResult.Unevaluable, PatternMatches.of("a".repeat(1025), names))
    }

    @Test
    fun `a name over 1024 characters matches nothing`() {
        assertEquals(emptyList(), matches(".*", listOf("a".repeat(1025))))
    }

    @Test
    fun `hostile patterns cannot hang (the M8-03 cases)`() {
        val longName = "a".repeat(2000)
        val some = listOf("a".repeat(60), "a".repeat(60) + "b", longName, "b")
        listOf("(.*a){40}", "(a*)*b", "(a|a)*c", "^(.*)*$", "((a+)+)+b", "(x+x+)+y").forEach { pattern ->
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                val result = PatternMatches.of(pattern, some)
                assertTrue(result is PatternMatchResult.Matches || result == PatternMatchResult.Unevaluable, "pattern <$pattern> gave $result")
            }
        }
    }

    @Test
    fun `a huge number of names stays quick`() {
        val many = (1..50_000).map { "queue-$it" }
        assertTimeoutPreemptively(Duration.ofSeconds(20)) { assertEquals(listOf("queue-1"), matches("^queue-1$", many)) }
    }
}
