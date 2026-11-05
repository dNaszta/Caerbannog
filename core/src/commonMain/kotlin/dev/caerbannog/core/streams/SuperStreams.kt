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
 * M2-07 COMPILE STUB (test developer, no behaviour): every stream is a [StreamEntry.Single]. The real function is the one place of the
 * "an entry is a stream or a super stream, partitions are not top-level" rule; see the brief and `SuperStreamGroupingTest`.
 */
internal fun groupStreams(streams: List<StreamSummary>, layouts: List<SuperStreamLayout>): List<StreamEntry> =
    streams.map { StreamEntry.Single(it) }
