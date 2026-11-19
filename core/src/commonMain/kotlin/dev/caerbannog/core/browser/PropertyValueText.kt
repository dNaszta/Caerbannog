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

    /** Literal text queued between the parts of a container; a private type so no property value can be mistaken for it. */
    private class Literal(val text: String)

    /**
     * Iterative on purpose: a recursive renderer overflows the thread stack at a depth that depends on the platform
     * (it failed on the Linux CI at 1,000 levels). The explicit stack holds one token iterator per open container.
     */
    private fun render(root: Any?): String {
        val out = StringBuilder()
        val stack = ArrayDeque<Iterator<Any?>>()
        var next: Any? = root
        var pending = true
        while (true) {
            if (!pending) {
                val top = stack.lastOrNull() ?: break
                if (!top.hasNext()) {
                    stack.removeLast()
                    continue
                }
                next = top.next()
            }
            pending = false
            when (val v = next) {
                is Literal -> out.append(v.text)
                null -> out.append("null")
                is String -> out.append(v)
                is ByteArray -> v.joinTo(out, " ") { hex(it) }
                is Map<*, *> -> {
                    out.append('{')
                    stack.addLast(mapTokens(v))
                }
                is Iterable<*> -> {
                    out.append('[')
                    stack.addLast(listTokens(v.iterator()))
                }
                is Array<*> -> {
                    out.append('[')
                    stack.addLast(listTokens(v.iterator()))
                }
                is IntArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is LongArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is ShortArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is DoubleArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is FloatArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is BooleanArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                is CharArray -> out.append(v.joinToString(prefix = "[", postfix = "]"))
                else -> out.append(v.toString())
            }
        }
        return out.toString()
    }

    private val COMMA = Literal(", ")
    private val COLON = Literal(": ")
    private val CLOSE_LIST = Literal("]")
    private val CLOSE_MAP = Literal("}")

    private fun listTokens(items: Iterator<Any?>): Iterator<Any?> = iterator {
        var first = true
        for (item in items) {
            if (!first) yield(COMMA)
            first = false
            yield(item)
        }
        yield(CLOSE_LIST)
    }

    private fun mapTokens(map: Map<*, *>): Iterator<Any?> = iterator {
        var first = true
        for ((k, v) in map) {
            if (!first) yield(COMMA)
            first = false
            yield(k)
            yield(COLON)
            yield(v)
        }
        yield(CLOSE_MAP)
    }

    private fun hex(b: Byte): String {
        val i = b.toInt() and 0xFF
        return "" + HEX_CHARS[i ushr 4] + HEX_CHARS[i and 0x0F]
    }
}
