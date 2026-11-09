package dev.caerbannog.core.migrate3x

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier

/**
 * M8-03 round 1 (F1): the own linear-time policy pattern matcher. Tables for every supported construct, the unevaluable patterns with
 * their reason, a differential test against the JVM regex (the oracle; jvmTest only), and pathological patterns under a hang guard.
 * Names with line terminators and astral characters are left out on purpose (the line-terminator rules of `.` and `$` differ by dialect).
 */
class PolicyPatternMatcherTest {
    private fun matcher(pattern: String): PolicyPatternMatcher = when (val c = PolicyPatternMatcher.compile(pattern)) {
        is PatternCompilation.Compiled -> c.matcher
        is PatternCompilation.Unevaluable -> fail("pattern <$pattern> should be supported but is ${c.problem}")
    }

    private fun check(pattern: String, name: String, expected: Boolean) =
        assertEquals(expected, matcher(pattern).matches(name), "pattern <$pattern> against <$name>")

    private fun table(pattern: String, vararg cases: Pair<String, Boolean>) = cases.forEach { (name, expected) -> check(pattern, name, expected) }

    // ---- supported syntax ----

    @Test fun `literals match anywhere in the name`() {
        table("orders", "orders" to true, "my-orders-q" to true, "order" to false, "" to false)
        table("", "" to true, "anything" to true)
    }

    @Test fun `escaped literals`() {
        table("q\\.1", "q.1" to true, "qx1" to false)
        table("\\$", "a\$b" to true, "ab" to false)
        table("\\^a", "x^a" to true, "a" to false)
        table("a\\|b", "a|b" to true, "a" to false, "b" to false)
        table("\\(x\\)", "(x)" to true, "x" to false)
        table("a\\-b", "a-b" to true)
        table("\\*", "a*" to true, "a" to false)
    }

    @Test fun `dot matches any single character`() {
        table("a.b", "axb" to true, "a.b" to true, "ab" to false, "axxb" to false)
        table("^.$", "x" to true, "é" to true, "" to false, "xy" to false)
    }

    @Test fun `character classes with ranges, negation and escapes`() {
        table("[abc]", "xbx" to true, "xyz" to false)
        table("^[a-c]+$", "abcabc" to true, "abd" to false, "" to false)
        table("[^a-c]", "abc" to false, "abcd" to true)
        table("^[^a-c]+$", "xyz" to true, "xaz" to false)
        table("[.]", "a.b" to true, "ab" to false)
        table("[\\]]", "a]" to true, "a" to false)
        table("[a\\-z]", "-" to true, "m" to false, "z" to true)
        table("[-a]", "-" to true, "b" to false)
        table("[a-]", "-" to true, "b" to false)
        table("[\\\\]", "a\\b" to true, "ab" to false)
        table("^[0-9]+\$", "0123" to true, "01a" to false)
    }

    @Test fun `shorthand classes also inside classes`() {
        table("\\d", "a1" to true, "ab" to false)
        table("\\D", "12" to false, "1a" to true)
        table("\\w", "!!" to false, "!_" to true, "é" to false)
        table("\\W", "ab_1" to false, "a-b" to true, "é" to true)
        table("\\s", "a b" to true, "ab" to false)
        table("\\S", " " to false, " x" to true)
        table("^[\\d\\s]+$", "1 2 3" to true, "1a" to false)
        table("^[\\w-]+$", "my-queue_1" to true, "my queue" to false)
        table("^[^\\d]+$", "abc" to true, "ab1" to false)
        table("^[\\D]+$", "abc" to true, "a1" to false)
        table("^[\\S]+$", "abc" to true, "a b" to false)
        table("[\\W\\d]", "ab" to false, "a1" to true, "a!" to true)
    }

    @Test fun `anchors alone and combined`() {
        table("^orders", "orders-1" to true, "my-orders" to false)
        table("orders$", "my-orders" to true, "orders-1" to false)
        table("^orders$", "orders" to true, "orders1" to false, "1orders" to false)
        table("^$", "" to true, "a" to false)
        table("^", "" to true, "abc" to true)
        table("$", "" to true, "abc" to true)
        table("a^b", "a^b" to false, "ab" to false)
        table("a\$b", "ab" to false)
        table("(^a|b$)", "ax" to true, "xb" to true, "xax" to false, "xbx" to false)
    }

    @Test fun `groups with and without capture marker`() {
        table("^(ab)+$", "abab" to true, "aba" to false)
        table("^(?:ab)+$", "abab" to true, "aba" to false)
        table("((a))", "a" to true, "b" to false)
        table("(a(b)?)+c", "aabc" to true, "ac" to true, "bc" to false)
        table("()", "x" to true)
    }

    @Test fun `alternation including empty alternatives`() {
        table("^(a|b)c$", "ac" to true, "bc" to true, "cc" to false)
        table("^(?:q|r)\\d{1,2}$", "q1" to true, "r12" to true, "r123" to false, "s1" to false)
        table("a|", "x" to true)
        table("|a", "x" to true)
        table("^(a|)b$", "b" to true, "ab" to true, "cb" to false)
        table("cat|dog", "hotdog" to true, "bird" to false)
    }

    @Test fun `quantifiers star plus question`() {
        table("^ab*c$", "ac" to true, "abbbc" to true, "adc" to false)
        table("^ab+c$", "ac" to false, "abc" to true, "abbc" to true)
        table("^ab?c$", "ac" to true, "abc" to true, "abbc" to false)
        table("^(ab)*c$", "c" to true, "ababc" to true, "abac" to false)
        table("^(ab)?c$", "c" to true, "abc" to true, "ababc" to false)
        table("x*", "" to true, "y" to true)
    }

    @Test fun `counted quantifiers on atoms and groups`() {
        table("^a{2}$", "aa" to true, "a" to false, "aaa" to false)
        table("^a{2,}$", "a" to false, "aa" to true, "aaaaa" to true)
        table("^a{1,3}b$", "b" to false, "ab" to true, "aaab" to true, "aaaab" to false)
        table("^(ab){2}$", "abab" to true, "ab" to false, "ababab" to false)
        table("^(a|b){2,3}$", "ab" to true, "abb" to true, "abba" to false, "a" to false)
        table("^[a-c]{3}$", "abc" to true, "ab" to false)
        table("^a{0}b$", "b" to true, "ab" to false)
        table("^a{0,1}b$", "b" to true, "ab" to true, "aab" to false)
        table("^\\d{3}-\\d{2,4}$", "123-45" to true, "123-4" to false, "123-45678" to false)
    }

    @Test fun `a lazy suffix is accepted and gives the same boolean`() {
        table("a.*?b", "axxb" to true, "axx" to false)
        table("a+?", "aaa" to true, "b" to false)
        table("^a*?$", "aaa" to true, "ab" to false)
        table("^a{2,3}?$", "aa" to true, "aaa" to true, "a" to false)
        table("^(ab)??c$", "c" to true, "abc" to true)
    }

    @Test fun `the search starts at any position`() {
        table("bc", "abcd" to true, "abd" to false)
        table("b.d", "abcd" to true)
        table("(ab)+c", "xxababc" to true, "xxabab" to false)
    }

    // ---- unevaluable patterns ----

    private fun problem(pattern: String): PatternProblem = when (val c = PolicyPatternMatcher.compile(pattern)) {
        is PatternCompilation.Unevaluable -> c.problem
        is PatternCompilation.Compiled -> fail("pattern <${pattern.take(30)}> must be unevaluable but compiled")
    }

    @Test fun `constructs outside the regular subset are Unsupported`() {
        listOf("(?=a)", "(?!a)", "(?<=a)b", "(?<n>a)", "\\1", "(a)\\1", "(?i)a", "(?s:a)", "a++", "a*+", "\\bx", "\\p{L}", "\\x41", "\\Qa\\E")
            .forEach { assertEquals(PatternProblem.Unsupported, problem(it), "pattern <$it>") }
    }

    @Test fun `broken syntax is Invalid`() {
        listOf("(", ")", "(a", "a)", "[a", "[", "[a-", "*a", "+a", "?a", "a{2,1}", "(*a)", "|*", "[z-a]")
            .forEach { assertEquals(PatternProblem.Invalid, problem(it), "pattern <$it>") }
    }

    @Test fun `patterns that expand beyond the limits are TooLarge`() {
        assertEquals(PatternProblem.TooLarge, problem("a{1000}"))
        assertEquals(PatternProblem.TooLarge, problem("(ab){300}"))
        assertEquals(PatternProblem.TooLarge, problem("(a{20}){20}"))
        assertEquals(PatternProblem.TooLarge, problem("a".repeat(1025)))
    }

    @Test fun `a name over 1024 characters is matched by no pattern`() {
        val long = "a".repeat(1025)
        for (p in listOf(".*", "", "a", "^a+$", "a$", "^")) assertFalse(matcher(p).matches(long), "pattern <$p> against a 1,025-character name")
        assertTrue(matcher(".*").matches("a".repeat(1024)), "1,024 characters is still allowed")
    }

    // ---- differential test against the JVM regex ----

    private val corpusPatterns = listOf(
        "a", "ab", "orders", "^orders", "orders$", "^orders$", "q\\.1", "a.b", "^a.*b$", "[abc]", "[a-c]+", "[^a-c]", "[\\d]", "[\\w-]+",
        "[^\\s]", "\\d+", "\\D", "\\w+", "\\W", "\\s", "\\S", "a|b", "^(a|b)c$", "(?:ab)+", "(ab)*c", "a?b", "a{2}", "a{2,}", "a{1,3}b",
        "(ab){2}", "(a|b){2,3}$", "x*", "^$", "", "a|", "|a", "(a|)b", ".*", "^.{3}$", "^[a-z]+\\.[0-9]+$", "a.*?b", "a+?", "^a*?$", "[.]",
        "[\\]]", "[a\\-z]", "[-a]", "[a-]", "\\\$", "\\^a", "a\\|b", "^(?:q|r)\\d{1,2}$", "((a))", "(a(b)?)+", "[\\d\\s]+", "[^\\d]+",
        "^\\w+@\\w+$", "q\\d", "^q", "1\$", "\\.", "x.y", "(x|y)\\.z", "^(a+)b", "a{0,2}b", "[xyz]{2}", "^[^a]*\$", "é", "\\W{1}", "[é]", "^.$",
    )
    private val corpusNames = listOf(
        "", "a", "ab", "abc", "orders", "my-orders-q", "q.1", "q1", "xxaab", "ABC", "a b", "a_b-1", "é", "aé", "x.y.z", "aaa", "ba", "12",
        "q12", "a\$b", "^a", "a@b", "ab1", "aab", "abab", "x.z", "y.z",
    )

    @Test fun `the matcher equals the JVM regex on a fixed corpus of pattern and name pairs`() {
        val pairs = corpusPatterns.flatMap { p -> corpusNames.map { n -> p to n } }
        assertTrue(pairs.size >= 300, "corpus has ${pairs.size} pairs")
        val differences = pairs.mapNotNull { (p, n) ->
            val oracle = Regex(p).containsMatchIn(n)
            val actual = (PolicyPatternMatcher.compile(p) as? PatternCompilation.Compiled)?.matcher?.matches(n)
            if (actual != oracle) "<$p> on <$n>: oracle=$oracle matcher=${actual ?: "unevaluable"}" else null
        }
        assertTrue(differences.isEmpty(), "${differences.size} differences, first: ${differences.take(10)}")
    }

    // ---- pathological patterns: a hang guard, not a timing assertion ----

    private fun guarded(pattern: String, name: String, expected: Boolean) {
        val actual = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { matcher(pattern).matches(name) })
        assertEquals(expected, actual, "pattern <$pattern> against a ${name.length}-character name")
    }

    private val as250 = "a".repeat(250)

    @Test fun `(dot-star a) repeated 40 times does not hang`() { guarded("(.*a){40}", "b".repeat(250), false); guarded("(.*a){40}", as250, true) }
    @Test fun `(dot-star a) repeated 25 times does not hang`() { guarded("(.*a){25}", "b".repeat(250), false); guarded("(.*a){25}", as250, true) }
    @Test fun `nested plus anchored does not hang`() { guarded("^(a+)+$", as250 + "!", false); guarded("^(a+)+$", as250, true) }
    @Test fun `alternation with overlap does not hang`() { guarded("(a|aa)+$", as250 + "!", false); guarded("(a|aa)+$", as250, true) }
    @Test fun `nested star does not hang`() { guarded("(a*)*b", as250, false); guarded("(a*)*b", as250 + "b", true) }
    @Test fun `six dot-stars do not hang`() { guarded(".*.*.*.*.*.*b", as250, false); guarded(".*.*.*.*.*.*b", as250 + "b", true) }
}
