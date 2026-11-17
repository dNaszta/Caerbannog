package dev.caerbannog.core.planner

/**
 * Names a real RabbitMQ 4.x refuses, measured in M9-00c (table S-H of docs/tasks/M9-00c-spike-results.md).
 * Pure: the forms use it so the user hears the refusal in the wizard, not at apply time.
 */
object BrokerNameRules {
    private const val RESERVED_PREFIX: String = "amq."
    private const val QUEUE_NAME_MAX_BYTES: Int = 255

    /** True for a name that starts with the lower-case `amq.`; only that spelling was measured. */
    fun reservedPrefix(name: String): Boolean = name.startsWith(RESERVED_PREFIX)

    /** True for a queue name of more than 255 UTF-8 bytes (bytes, not characters). */
    fun queueNameTooLong(name: String): Boolean = Utf8Length.of(name) > QUEUE_NAME_MAX_BYTES
}

/** UTF-8 byte length counted over code points, so common code needs no JVM `toByteArray`. */
internal object Utf8Length {
    /** The byte count of well-formed [text]; a lone surrogate counts as 3 (out of scope). */
    fun of(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            bytes += when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            i += if (pair) 2 else 1
        }
        return bytes
    }
}
