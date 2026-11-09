package dev.caerbannog.core.migrate3x

/**
 * Reads the regular subset of RabbitMQ policy patterns into a [PatternNode] tree: literals, escaped punctuation, `.`, classes,
 * `\d \w \s` and their negations, `^ $`, groups, `|`, and the quantifiers `* + ? {n} {n,} {n,m}` (a lazy `?` is accepted: the
 * boolean result is the same). Anything else raises [PatternException] with [PatternProblem.Unsupported], broken syntax with
 * [PatternProblem.Invalid]. Recursive descent; the pattern length is limited by the caller, so the depth is too.
 */
internal class PatternParser(private val pattern: String) {
    private var position = 0

    fun parse(): PatternNode {
        val node = alternation()
        if (position < pattern.length) throw PatternException(PatternProblem.Invalid) // a ")" without a "("
        return node
    }

    private fun peek(): Char? = pattern.getOrNull(position)

    private fun alternation(): PatternNode {
        val branches = mutableListOf(sequence())
        while (peek() == '|') {
            position++
            branches += sequence()
        }
        return if (branches.size == 1) branches.single() else PatternNode.Choice(branches)
    }

    private fun sequence(): PatternNode {
        val parts = mutableListOf<PatternNode>()
        while (peek() != null && peek() != '|' && peek() != ')') parts += quantified()
        return when (parts.size) {
            0 -> PatternNode.Empty
            1 -> parts.single()
            else -> PatternNode.Sequence(parts)
        }
    }

    private fun quantified(): PatternNode {
        val atom = atom()
        val bounds = quantifier() ?: return atom
        when (peek()) {
            '?' -> position++ // lazy: the same boolean result
            '+' -> throw PatternException(PatternProblem.Unsupported) // possessive
        }
        if (peek() == '*' || peek() == '+' || peek() == '?') throw PatternException(PatternProblem.Invalid)
        return PatternNode.Repeat(atom, bounds.first, bounds.second)
    }

    /** Consumes a quantifier and returns its (min, max), or consumes nothing and returns null when none starts here. */
    private fun quantifier(): Pair<Int, Int?>? = when (peek()) {
        '*' -> { position++; 0 to null }
        '+' -> { position++; 1 to null }
        '?' -> { position++; 0 to 1 }
        '{' -> counted()
        else -> null
    }

    /** `{n}`, `{n,}` or `{n,m}`; a `{` that does not start one of these is an ordinary character (as in PCRE). */
    private fun counted(): Pair<Int, Int?>? {
        val start = position
        position++
        val min = number()
        if (min == null) { position = start; return null }
        var max: Int? = min
        if (peek() == ',') {
            position++
            max = number()
        }
        if (peek() != '}') { position = start; return null }
        position++
        if (max != null && max < min) throw PatternException(PatternProblem.Invalid)
        return min to max
    }

    private fun number(): Int? {
        val start = position
        while (peek()?.isAsciiDigit() == true) position++
        if (start == position) return null
        val digits = pattern.substring(start, position).trimStart('0')
        return if (digits.length > MAX_DIGITS) MAX_REPEAT else (digits.toIntOrNull() ?: 0).coerceAtMost(MAX_REPEAT)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun atom(): PatternNode {
        if (peek() == '{' && counted() != null) throw PatternException(PatternProblem.Invalid) // a quantifier with nothing to repeat
        val c = pattern[position++]
        return when (c) {
            '(' -> group()
            '[' -> PatternNode.Character(characterClass())
            '.' -> PatternNode.Character(CharSet.anyButLineFeed)
            '^' -> PatternNode.LineStart
            '$' -> PatternNode.LineEnd
            '\\' -> PatternNode.Character(escape())
            '*', '+', '?' -> throw PatternException(PatternProblem.Invalid)
            else -> literal(c)
        }
    }

    private fun literal(c: Char) = PatternNode.Character(CharSet.single(c))

    private fun group(): PatternNode {
        if (peek() == '?') {
            if (pattern.getOrNull(position + 1) != ':') throw PatternException(PatternProblem.Unsupported)
            position += 2
        }
        val inner = alternation()
        if (peek() != ')') throw PatternException(PatternProblem.Invalid)
        position++
        return inner
    }

    /** The escape after a backslash that was just read: a shorthand class or an escaped punctuation character. */
    private fun escape(): CharSet {
        val c = pattern.getOrNull(position++) ?: throw PatternException(PatternProblem.Invalid)
        val shorthand = shorthandOf(c)
        return when {
            shorthand != null -> CharSet(listOf(shorthand))
            c.isLetterOrDigit() -> throw PatternException(PatternProblem.Unsupported) // backreference, \b, \p, \x, \Q ...
            else -> CharSet.single(c)
        }
    }

    private fun shorthandOf(c: Char): ClassMember.Shorthand? = when (c) {
        'd', 'w', 's' -> ClassMember.Shorthand(c, negated = false)
        'D', 'W', 'S' -> ClassMember.Shorthand(c.lowercaseChar(), negated = true)
        else -> null
    }

    private fun characterClass(): CharSet {
        val negated = peek() == '^'
        if (negated) position++
        val members = mutableListOf<ClassMember>()
        var first = true
        while (true) {
            val c = pattern.getOrNull(position++) ?: throw PatternException(PatternProblem.Invalid)
            if (c == ']' && !first) return CharSet(members, negated)
            first = false
            members += classMember(c)
        }
    }

    private fun classMember(c: Char): ClassMember {
        if (c == '[' && peek() == ':') throw PatternException(PatternProblem.Unsupported) // [:alpha:]
        val low = if (c == '\\') classEscape() else ClassMember.Range(c, c)
        val isRange = low is ClassMember.Range && peek() == '-' && pattern.getOrNull(position + 1).let { it != null && it != ']' }
        if (!isRange) return low
        position++
        val highChar = pattern[position++]
        val high = if (highChar == '\\') classEscape() else ClassMember.Range(highChar, highChar)
        if (high !is ClassMember.Range || (low as ClassMember.Range).low > high.low) throw PatternException(PatternProblem.Invalid)
        return ClassMember.Range(low.low, high.low)
    }

    private fun classEscape(): ClassMember {
        val c = pattern.getOrNull(position++) ?: throw PatternException(PatternProblem.Invalid)
        return shorthandOf(c) ?: if (c.isLetterOrDigit()) throw PatternException(PatternProblem.Unsupported) else ClassMember.Range(c, c)
    }

    private companion object {
        const val MAX_DIGITS = 6
        const val MAX_REPEAT = 100_000
    }
}
