package dev.caerbannog.core.migrate3x

/** Why the app cannot evaluate a policy pattern. The reason codes in the report are `invalid`, `unsupported`, `too-large`. */
internal enum class PatternProblem(val code: String, val inWords: String) {
    Invalid("invalid", "the pattern is not a valid regular expression"),
    Unsupported("unsupported", "it uses a construct this app does not evaluate"),
    TooLarge("too-large", "the pattern is too large"),
}

/** Thrown inside the pattern compiler only; [PolicyPatternMatcher.compile] turns it into a result. */
internal class PatternException(val problem: PatternProblem) : RuntimeException(problem.code)

/** One member of a character class: a range, or a shorthand (`\d`, `\w`, `\s`) that may be negated (`\D`, `\W`, `\S`). */
internal sealed interface ClassMember {
    fun matches(c: Char): Boolean

    data class Range(val low: Char, val high: Char) : ClassMember {
        override fun matches(c: Char): Boolean = c in low..high
    }

    /** [kind] is `d`, `w` or `s`. Ranges are ASCII only, as in the regular expression engine of RabbitMQ. */
    data class Shorthand(val kind: Char, val negated: Boolean) : ClassMember {
        override fun matches(c: Char): Boolean = inKind(c) != negated

        private fun inKind(c: Char): Boolean = when (kind) {
            'd' -> c in '0'..'9'
            'w' -> c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '_'
            else -> c == ' ' || c in '\t'..'\r'
        }
    }
}

/** A set of characters: the members, or everything else when [negated]. */
internal data class CharSet(val members: List<ClassMember>, val negated: Boolean = false) {
    fun matches(c: Char): Boolean = members.any { it.matches(c) } != negated

    companion object {
        fun single(c: Char): CharSet = CharSet(listOf(ClassMember.Range(c, c)))

        /** `.`: every character except a line feed. */
        val anyButLineFeed: CharSet = CharSet(listOf(ClassMember.Range('\n', '\n')), negated = true)
    }
}

/** The syntax tree of a pattern. */
internal sealed interface PatternNode {
    data object Empty : PatternNode
    data class Character(val set: CharSet) : PatternNode
    data object LineStart : PatternNode
    data object LineEnd : PatternNode
    data class Sequence(val parts: List<PatternNode>) : PatternNode
    data class Choice(val branches: List<PatternNode>) : PatternNode

    /** [max] null means unbounded. */
    data class Repeat(val body: PatternNode, val min: Int, val max: Int?) : PatternNode
}
