package dev.caerbannog.core.json

/**
 * Finds text that nests brackets too deep to hand to the JSON tree reader, which recurses and overflows the stack
 * on thousands of levels (measured with kotlinx-serialization 1.7.3). Iterative on purpose: it is the guard in
 * front of the recursion, so it must not recurse itself.
 */
public object JsonNestingScan {

    /** [section] is the top-level key that holds the deep value, or null when there is none (not a definitions-shaped text). */
    public data class TooDeep(val section: String?)

    /** Null when the nesting stays within [maxDepth]. Quotes and escapes are honoured so brackets inside strings do not count. */
    public fun findTooDeep(text: String, maxDepth: Int): TooDeep? {
        var depth = 0
        var topLevelKey: String? = null
        var rootIsObject = false
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    val end = endOfString(text, i)
                    if (depth == 1 && rootIsObject) topLevelKey = text.substring(i + 1, minOf(end, text.length))
                    i = end
                }
                '[', '{' -> {
                    if (depth == 0) rootIsObject = text[i] == '{'
                    depth++
                    if (depth > maxDepth) return TooDeep(if (rootIsObject) topLevelKey else null)
                }
                ']', '}' -> if (depth > 0) depth--
            }
            i++
        }
        return null
    }

    /** Index of the closing quote of the string that opens at [start], or the text length when it never closes. */
    private fun endOfString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
        return minOf(i, text.length)
    }
}
