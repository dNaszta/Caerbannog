package dev.caerbannog.core.streams

private const val GROUP = 3
private const val ROW_TYPE = "stream"

/** `48300` as `48,300`, always the same, no locale (one place to change the format). */
internal fun groupDigits(value: Long): String {
    val digits = value.toString()
    val negative = digits.startsWith("-")
    val body = if (negative) digits.drop(1) else digits
    val grouped = body.reversed().chunked(GROUP).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}

/**
 * Builds a row from the broker's summary and what is known of its first offset. [firstOffset] null means the call
 * is still running; [publishers] null means "not shown" (D8: unavailable).
 */
internal fun streamRow(summary: StreamSummary, firstOffset: FirstOffset?, publishers: Int?): StreamRow {
    val messages = summary.messages
    val first = when (firstOffset) {
        null -> StreamListTexts.LOADING
        is FirstOffset.Value -> firstOffset.offset.toString()
        FirstOffset.Empty -> ""
        FirstOffset.Unavailable -> StreamListTexts.UNAVAILABLE
    }
    // Last offset = first + messages - 1, only when both are known (the count is approximate, spec "Stream list").
    val last = when {
        firstOffset == null -> StreamListTexts.LOADING
        firstOffset == FirstOffset.Empty -> ""
        firstOffset !is FirstOffset.Value || messages == null -> StreamListTexts.UNAVAILABLE
        messages == 0L -> ""
        else -> (firstOffset.offset + messages - 1).toString()
    }
    return StreamRow(
        name = summary.name,
        type = ROW_TYPE,
        messages = messages?.let(::groupDigits) ?: StreamListTexts.NOT_AVAILABLE,
        firstOffset = first,
        lastOffset = last,
        publishers = publishers?.toString() ?: StreamListTexts.UNAVAILABLE,
        consumers = summary.consumers.toString(),
        retention = summary.retention.displayMaxAge(),
    )
}
