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
    val last = lastOffsetText(firstOffset, messages)
    return StreamRow(
        name = summary.name,
        type = ROW_TYPE,
        messages = messages?.let(::groupDigits) ?: StreamListTexts.NOT_AVAILABLE,
        firstOffset = first,
        lastOffset = last,
        publishers = publishers?.let { groupDigits(it.toLong()) } ?: StreamListTexts.UNAVAILABLE,
        consumers = groupDigits(summary.consumers.toLong()),
        retention = summary.retention.displayMaxAge(),
    )
}

/**
 * Last offset = first + messages - 1, only when both are known (the count is approximate, spec "Stream list"). One rule for the list
 * row and the stream tab. [firstOffset] null means the call is still running; an empty stream reads blank.
 */
internal fun lastOffsetText(firstOffset: FirstOffset?, messages: Long?): String = when {
    firstOffset == null -> StreamListTexts.LOADING
    firstOffset == FirstOffset.Empty -> ""
    firstOffset !is FirstOffset.Value || messages == null -> StreamListTexts.UNAVAILABLE
    messages == 0L -> ""
    else -> (firstOffset.offset + messages - 1).toString()
}

/** A partition row: an ordinary stream row with its own cells, typed "partition" and pointing at its super stream. */
internal fun partitionRow(summary: StreamSummary, firstOffset: FirstOffset?, publishers: Int?, superStream: String): StreamRow =
    streamRow(summary, firstOffset, publishers).copy(
        type = StreamListTexts.PARTITION,
        kind = StreamRowKind.Partition,
        partitionOf = superStream,
    )

/**
 * The row of a super stream, from all its partitions (also while collapsed). Sums are never partial: messages only when every
 * partition has a count, publishers only when every partition's count is shown (D8, [publisherCounts] null means not shown).
 * Consumers add up attachments, not distinct clients. Offsets do not apply: order exists only inside a partition.
 */
internal fun superStreamRow(group: StreamEntry.Group, publisherCounts: Map<String, Int>?, expanded: Boolean): StreamRow {
    val partitions = group.partitions
    val counts = partitions.map { it.messages }
    val retentions = partitions.map { it.retention.displayMaxAge() }.distinct()
    return StreamRow(
        name = group.name,
        type = StreamListTexts.SUPER_STREAM,
        messages = if (counts.any { it == null }) StreamListTexts.NOT_AVAILABLE else groupDigits(counts.sumOf { it ?: 0L }),
        firstOffset = StreamListTexts.NOT_APPLICABLE,
        lastOffset = StreamListTexts.NOT_APPLICABLE,
        publishers = publisherCounts?.let { c -> groupDigits(partitions.sumOf { c[it.name] ?: 0 }.toLong()) } ?: StreamListTexts.UNAVAILABLE,
        consumers = groupDigits(partitions.sumOf { it.consumers }.toLong()),
        retention = retentions.singleOrNull() ?: StreamListTexts.MIXED,
        kind = StreamRowKind.SuperStream,
        partitionCount = partitions.size,
        expanded = expanded,
    )
}
