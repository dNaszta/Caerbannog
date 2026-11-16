package dev.caerbannog.core.planner

/** The naming styles of the planner (owner decisions O10, O11). The style is advice for new names; nothing blocks on it. */
enum class NamingStyle(val label: String) {
    KEBAB("kebab-case"),
    SNAKE("snake_case"),
    DOT("dot.separated"),
    CAMEL("camelCase"),
    NONE("No convention"),
}

/** The fixed words that derived names (dead-lettering, retention) are built from. */
enum class DerivedWord(val word: String) {
    DLN("dln"),
    DLQ("dlq"),
    DLX("dlx"),
    RETENTION("retention"),
}

/**
 * Pure naming rules. Plain character loops, no regular expression, and no example is built for a name longer than [MAX_EXAMPLE_LENGTH]:
 * a name is untrusted text once a file is opened.
 */
object PlanNaming {
    private const val MAX_EXAMPLE_LENGTH = 128

    /** The base as typed plus the separator of the style and the word (camelCase: the word with a capital first letter). NONE counts as kebab-case. */
    fun derived(base: String, word: DerivedWord, style: NamingStyle): String = when (style) {
        NamingStyle.KEBAB, NamingStyle.NONE -> "$base-${word.word}"
        NamingStyle.SNAKE -> "${base}_${word.word}"
        NamingStyle.DOT -> "$base.${word.word}"
        NamingStyle.CAMEL -> base + capitalized(word.word)
    }

    fun follows(name: String, style: NamingStyle): Boolean = when (style) {
        NamingStyle.NONE -> true
        NamingStyle.KEBAB -> separatedLowerCase(name, '-')
        NamingStyle.SNAKE -> separatedLowerCase(name, '_')
        NamingStyle.DOT -> separatedLowerCase(name, '.')
        NamingStyle.CAMEL -> name.isNotEmpty() && name[0].isLowerCase() && name.all { it.isLetterOrDigit() }
    }

    /** The name rebuilt in [style], or null when no word can be built (or the name is too long to look at). */
    fun example(name: String, style: NamingStyle): String? {
        if (style == NamingStyle.NONE || name.length > MAX_EXAMPLE_LENGTH) return null
        val words = words(name).map { it.lowercase() }
        if (words.isEmpty()) return null
        val built = when (style) {
            NamingStyle.KEBAB -> words.joinToString("-")
            NamingStyle.SNAKE -> words.joinToString("_")
            NamingStyle.DOT -> words.joinToString(".")
            NamingStyle.CAMEL -> words.first() + words.drop(1).joinToString("") { capitalized(it) }
            NamingStyle.NONE -> return null
        }
        return built.takeIf { follows(it, style) }
    }

    /** Advice for a new name; null for an empty name, for [NamingStyle.NONE] and for a name that follows. */
    fun hint(name: String, style: NamingStyle): String? {
        if (name.isEmpty() || style == NamingStyle.NONE || follows(name, style)) return null
        val head = "This name does not follow ${style.label}"
        val example = example(name, style) ?: return head
        return "$head, for example $example"
    }

    /** The base of a name that was derived with [word] in any style; null when it is not derived or has no base. */
    fun baseOf(name: String, word: DerivedWord): String? {
        for (separator in listOf('-', '_', '.')) {
            val suffix = "$separator${word.word}"
            if (name.endsWith(suffix)) return name.dropLast(suffix.length).ifEmpty { null }
        }
        val camel = capitalized(word.word)
        if (name.endsWith(camel)) return name.dropLast(camel.length).ifEmpty { null }
        return null
    }

    private fun separatedLowerCase(name: String, separator: Char): Boolean {
        if (name.isEmpty()) return false
        var wordLength = 0
        for (ch in name) {
            if (ch == separator) {
                if (wordLength == 0) return false
                wordLength = 0
            } else if (ch.isLowerCase() || ch.isDigit()) {
                wordLength++
            } else {
                return false
            }
        }
        return wordLength > 0
    }

    /** Splits at anything that is not a letter or digit, and before an upper-case letter that follows a lower-case letter or a digit. */
    private fun words(name: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var previous: Char? = null
        for (ch in name) {
            if (!ch.isLetterOrDigit()) {
                if (current.isNotEmpty()) result += current.toString()
                current.clear()
            } else {
                val boundary = ch.isUpperCase() && previous != null && (previous.isLowerCase() || previous.isDigit())
                if (boundary && current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                current.append(ch)
            }
            previous = ch
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun capitalized(word: String): String = word.replaceFirstChar { it.uppercaseChar() }
}
