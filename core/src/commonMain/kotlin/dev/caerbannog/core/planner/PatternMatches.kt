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

    /**
     * The one name [pattern] covers, the exact inverse of [of] for a single name: `^`, characters where a backslash escapes the next one, then `$`
     * and nothing after. Null for every other text (several names, an unescaped special character, no anchors, a trailing backslash). A pattern is
     * untrusted once a file is opened, so this is a plain loop and never a regular expression. `^$` is the empty name; callers that need a real name skip it.
     */
    fun nameOf(pattern: String): String? {
        if (!pattern.startsWith('^')) return null
        val name = StringBuilder()
        var i = 1
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= pattern.length) return null
                    name.append(pattern[i + 1])
                    i += 2
                    continue
                }
                c == '$' -> return if (i == pattern.length - 1) name.toString() else null
                c in SPECIAL -> return null
                else -> name.append(c)
            }
            i++
        }
        return null
    }
}
