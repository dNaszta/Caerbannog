package dev.caerbannog.core.planner

import dev.caerbannog.core.migrate3x.PatternCompilation
import dev.caerbannog.core.migrate3x.PolicyPatternMatcher

/** What a policy pattern matches among some names. */
sealed interface PatternMatchResult {
    /** The matching [names], in the order given. */
    data class Matches(val names: List<String>) : PatternMatchResult

    /** The app cannot evaluate this pattern (outside its regular subset, or too large): it makes no claim. */
    data object Unevaluable : PatternMatchResult
}

/**
 * Which names a pattern matches, by the app's own matcher: a pattern is untrusted once a file is opened, so `kotlin.text.Regex` is never used
 * (it cannot be cancelled and has no timeout).
 */
object PatternMatches {
    fun of(pattern: String, names: Collection<String>): PatternMatchResult =
        when (val compiled = PolicyPatternMatcher.compile(pattern)) {
            is PatternCompilation.Compiled -> PatternMatchResult.Matches(names.filter { compiled.matcher.matches(it) })
            is PatternCompilation.Unevaluable -> PatternMatchResult.Unevaluable
        }
}

/** The pattern that covers exactly some names (a retention policy for the streams a form adds). */
object ExactPattern {
    private const val SPECIAL: String = "\\.^$*+?()[]{}|"

    /** `^name$` for one name, `^(a|b)$` for several, every special character escaped with a backslash. */
    fun of(names: List<String>): String {
        val escaped = names.map { name -> buildString { name.forEach { if (it in SPECIAL) append('\\'); append(it) } } }
        return if (escaped.size == 1) "^${escaped.single()}$" else "^(${escaped.joinToString("|")})$"
    }
}
