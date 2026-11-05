package dev.caerbannog.core.streams

/** M2-07: one super stream as the broker defines it: [partitions] are the partition stream names, already in the broker's partition order. */
data class SuperStreamLayout(val name: String, val vhost: String, val partitions: List<String>)

/** M2-07: one entry of the list: a plain stream or a super stream with the partitions that were found among the loaded streams. */
internal sealed interface StreamEntry {
    val name: String

    data class Single(val stream: StreamSummary) : StreamEntry {
        override val name: String get() = stream.name
    }

    data class Group(override val name: String, val vhost: String, val partitions: List<StreamSummary>) : StreamEntry
}

/**
 * The one place of the "an entry is a stream or a super stream, partitions are not top-level" rule (paging, filter and render read its
 * result and never recount). A layout becomes a [StreamEntry.Group] of the partitions that are among [streams], in the layout's order;
 * a layout with none of its partitions loaded gives no entry (nothing to expand or count). A partition listed twice, or in two layouts,
 * belongs to the first super stream by name. Every stream that is no partition stays a [StreamEntry.Single]; grouping follows the
 * bindings, never the names. The order of the result is unspecified (the view model sorts).
 */
internal fun groupStreams(streams: List<StreamSummary>, layouts: List<SuperStreamLayout>): List<StreamEntry> {
    val byName = streams.associateBy { it.name }
    val claimed = mutableSetOf<String>()
    val groups = mutableListOf<StreamEntry.Group>()
    for (layout in layouts.sortedWith(compareBy<SuperStreamLayout> { it.name }.thenBy { it.vhost })) {
        val found = layout.partitions.filter { it in byName && claimed.add(it) }.map { byName.getValue(it) }
        if (found.isNotEmpty()) groups += StreamEntry.Group(layout.name, layout.vhost, found)
    }
    return streams.filter { it.name !in claimed }.map { StreamEntry.Single(it) } + groups
}
