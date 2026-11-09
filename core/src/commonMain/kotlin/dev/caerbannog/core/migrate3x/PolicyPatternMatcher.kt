package dev.caerbannog.core.migrate3x

/** The result of compiling a policy pattern: a matcher, or the reason the app cannot evaluate it. */
internal sealed interface PatternCompilation {
    data class Compiled(val matcher: PolicyPatternMatcher) : PatternCompilation
    data class Unevaluable(val problem: PatternProblem) : PatternCompilation
}

/**
 * Decides whether a RabbitMQ policy pattern matches somewhere in a queue name (unanchored unless the pattern says `^` or `$`),
 * for the regular subset of the pattern language only (see [PatternParser]). Why it is not `kotlin.text.Regex`: the core module has
 * no regex timeout and a running match cannot be cancelled, and an export is untrusted input, so a pattern such as `(.*a){40}`
 * must not be able to hang the analysis. This matcher costs at most (states x name length): states are limited to 256,
 * the pattern to 1,024 characters.
 *
 * Contract: [compile] never throws; a pattern outside the subset is [PatternCompilation.Unevaluable]. [matches] is false for a
 * name over 1,024 characters (a broker cannot hold one).
 */
internal class PolicyPatternMatcher private constructor(private val simulator: PatternSimulator) {
    fun matches(name: String): Boolean = name.length <= MAX_NAME_LENGTH && simulator.matchesSomewhere(name)

    companion object {
        const val MAX_PATTERN_LENGTH = 1024
        const val MAX_NAME_LENGTH = 1024

        fun compile(pattern: String): PatternCompilation {
            if (pattern.length > MAX_PATTERN_LENGTH) return PatternCompilation.Unevaluable(PatternProblem.TooLarge)
            return try {
                val program = PatternProgramBuilder().build(PatternParser(pattern).parse())
                PatternCompilation.Compiled(PolicyPatternMatcher(PatternSimulator(program)))
            } catch (e: PatternException) {
                PatternCompilation.Unevaluable(e.problem)
            }
        }
    }
}
