package dev.caerbannog.core.migrate3x

// M8-03 round 1 (test developer): COMPILE STUB ONLY. The senior developer deletes this file and writes PolicyPatternMatcher.kt with the
// same API (internal, free of java.* and of kotlin.text.Regex). The API below is what the tests call:
//   PolicyPatternMatcher.compile(pattern): PatternCompilation   -> Compiled(matcher) or Unevaluable(problem)
//   PolicyPatternMatcher.matches(name): Boolean                 -> "matches somewhere" semantics, false for a name over 1,024 characters

internal enum class PatternProblem { Invalid, Unsupported, TooLarge }

internal sealed interface PatternCompilation {
    data class Compiled(val matcher: PolicyPatternMatcher) : PatternCompilation
    data class Unevaluable(val problem: PatternProblem) : PatternCompilation
}

internal class PolicyPatternMatcher private constructor() {
    fun matches(name: String): Boolean = TODO("M8-03 round 1: PolicyPatternMatcher.matches")

    companion object {
        fun compile(pattern: String): PatternCompilation = TODO("M8-03 round 1: PolicyPatternMatcher.compile")
    }
}
