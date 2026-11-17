package dev.caerbannog.core.browser

/**
 * Readable text for a message property value. Dead-letter annotations such as `x-opt-deaths`
 * arrive as lists of maps; their `toString()` is useless for arrays and byte arrays, so every
 * place that shows, copies, filters or returns a property value goes through here.
 */
object PropertyValueText {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Top-level null is empty text; nested null is "null"; nothing is capped or sorted. */
    fun of(value: Any?): String = if (value == null) "" else render(value)

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is ByteArray -> value.joinToString(" ") { hex(it) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { "${render(it.key)}: ${render(it.value)}" }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { render(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { render(it) }
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is CharArray -> value.joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }

    private fun hex(b: Byte): String {
        val i = b.toInt() and 0xFF
        return "" + HEX_CHARS[i ushr 4] + HEX_CHARS[i and 0x0F]
    }
}
