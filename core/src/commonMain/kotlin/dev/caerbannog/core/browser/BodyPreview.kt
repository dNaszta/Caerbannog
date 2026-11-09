package dev.caerbannog.core.browser

/**
 * Pure formatting of a message body for the browser's table cell and hover tooltip.
 * One place decides "text or binary" so the cell and the tooltip never disagree.
 */
object BodyPreview {
    const val CELL_TEXT_CHARS = 120
    const val CELL_HEX_BYTES = 32
    const val TOOLTIP_TEXT_BYTES = 1000
    const val TOOLTIP_HEX_BYTES = 256

    private const val REPLACEMENT_CHAR = '�'

    /** One-line preview for the table cell: text with newlines flattened, or the leading bytes as hex. */
    fun cell(payload: ByteArray): String {
        val text = payload.decodeToString()
        return if (isBinary(text)) hex(payload, CELL_HEX_BYTES) else text.replace("\n", " ").take(CELL_TEXT_CHARS)
    }

    /**
     * Bounded text for the tooltip. Binary is decided on the whole payload, so a multi-byte character cut by the
     * truncation limit cannot turn valid text into hex. Never renders more than [TOOLTIP_TEXT_BYTES] bytes of text
     * or [TOOLTIP_HEX_BYTES] bytes of hex.
     */
    fun tooltip(payload: ByteArray): String {
        val full = payload.decodeToString()
        val truncatedNote = "\n\n... (truncated, full length: ${payload.size} bytes)"
        return when {
            isBinary(full) -> hex(payload, TOOLTIP_HEX_BYTES) + if (payload.size > TOOLTIP_HEX_BYTES) truncatedNote else ""
            payload.size <= TOOLTIP_TEXT_BYTES -> full
            else -> textPrefix(payload, TOOLTIP_TEXT_BYTES) + truncatedNote
        }
    }

    private fun isBinary(text: String): Boolean =
        text.any { (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') || it == REPLACEMENT_CHAR }

    private fun hex(payload: ByteArray, limit: Int): String {
        val body = payload.take(limit).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return if (payload.size > limit) "$body …" else body
    }

    /** Decodes at most [limit] bytes, backing up so a split multi-byte character is dropped, not mangled. */
    private fun textPrefix(payload: ByteArray, limit: Int): String {
        var end = limit
        while (end > 0 && (payload[end].toInt() and 0xC0) == 0x80) end-- // payload[end] is a continuation byte
        return payload.copyOf(end).decodeToString()
    }
}
