package dev.caerbannog.core.browser

/** Start specification for stream message browsing. */
sealed interface BrowseStart {
    /** Start from the first message in the stream (offset 0 or retention head). */
    data object First : BrowseStart

    /** Start from the last page of the stream, showing up to [pageSize] latest messages. */
    data class LastPage(val pageSize: Int) : BrowseStart

    /** Start from a specific 0-based offset. */
    data class Offset(val offset: Long) : BrowseStart

    /** Start from a timestamp in epoch milliseconds. */
    data class Timestamp(val epochMs: Long) : BrowseStart
}

/** Result of [StreamMessageReader.readPage]. */
sealed interface BrowseResult {
    /** Page successfully read. [messages] are ordered by offset ascending. */
    data class Success(
        val messages: List<StreamMessage>,
        val streamFirstOffset: Long,
        val streamLastOffset: Long,
    ) : BrowseResult

    /** The stream currently has no messages. */
    data class Empty(
        val streamFirstOffset: Long,
        val streamLastOffset: Long,
    ) : BrowseResult

    /** The requested timestamp is after the last message in the stream. */
    data class NoMessagesAtOrAfterTime(
        val epochMs: Long,
        val streamFirstOffset: Long,
        val streamLastOffset: Long,
    ) : BrowseResult

    /** The requested offset is outside the valid range [validRange]. */
    data class OutsideRange(
        val offset: Long,
        val validRange: LongRange,
    ) : BrowseResult

    /** Stream does not exist. */
    data object NotFound : BrowseResult

    /** Read permission on this stream was denied by the broker. */
    data object AccessDenied : BrowseResult

    /** The read failed or the connection is lost. */
    data class Failed(val reason: String) : BrowseResult
}

/**
 * Reads messages from a stream page by page.
 *
 * Implementations MUST adhere to the M3 safety rule: browsing consumers MUST be unnamed
 * and configured with `noTrackingStrategy()`, so browsing never alters stored offsets,
 * writes tracking entries, or alters stream message counts on the broker.
 */
interface StreamMessageReader {
    suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult
}
