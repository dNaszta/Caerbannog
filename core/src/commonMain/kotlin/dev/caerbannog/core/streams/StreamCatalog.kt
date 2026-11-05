package dev.caerbannog.core.streams

/**
 * Read-only view of the streams of one broker, served by the management API (spec "Management API facts").
 * Gateway pattern: core knows no HTTP client, `data-rabbit` implements it, tests use a fake. Every call returns a
 * [CatalogResult]; failures the user can act on are values, not exceptions. Nothing here reads or changes a message.
 */
interface StreamCatalog {
    /** One page of streams. The broker pages over ALL queues, so a page can hold fewer streams than the page size. */
    suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage>

    /** One stream with its retention arguments; [CatalogResult.NotFound] when it does not exist (any more). */
    suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary>

    /** Publishers of one stream; [CatalogResult.PluginMissing] without `rabbitmq_stream_management`. */
    suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>>

    /**
     * Publisher count per stream of [vhost] in ONE call (D8); streams without a publisher are absent. Only the
     * stream list calls it, and only for a user who sees every connection (a partial number is never shown).
     */
    suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>>

    /** Consumers of one stream; the broker has no per-stream path, so the implementation filters on the stream name. */
    suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>>

    /**
     * M2-07: the super streams of [vhost] and their partitions, in the broker's partition order. A super stream is a `direct` exchange with
     * `arguments.x-super-stream` = true; its partitions are the queue destinations of its bindings (never inferred from names).
     * A failure here never fails the stream list (the list falls back to flat, brief M2-07 item 7).
     */
    suspend fun superStreams(vhost: String): CatalogResult<List<SuperStreamLayout>>

    /** What this broker and this user allow: plugin present or not, and how much of the lists the user sees. */
    suspend fun capabilities(): CatalogResult<Capabilities>
}

/** First offset of a stream, from the stream protocol (the management API does not carry it). */
interface StreamOffsets {
    /** Never throws for a broker failure: it answers [FirstOffset.Unavailable]. */
    suspend fun firstOffset(vhost: String, stream: String): FirstOffset
}

/** Outcome of [StreamOffsets.firstOffset]. */
sealed interface FirstOffset {
    data class Value(val offset: Long) : FirstOffset

    /** The stream has no first offset yet (nothing published). */
    data object Empty : FirstOffset

    /** Denied, missing, silent stream port, timeout: the row shows no offset, the list still works. */
    data object Unavailable : FirstOffset
}

/** Texts about the catalog shared by core and screens. */
object CatalogMessages {
    const val NOT_MANAGEMENT_USER: String = "This user cannot read stream statistics. Ask for the monitoring tag."
}
