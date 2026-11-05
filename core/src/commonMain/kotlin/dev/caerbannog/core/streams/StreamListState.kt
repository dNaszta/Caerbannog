package dev.caerbannog.core.streams

/** What the stream list screen shows (spec "Stream list"). Display strings only: what is shown is what is copied (UI1). */
sealed interface StreamListScreen {
    data object NotConnected : StreamListScreen

    data object Connecting : StreamListScreen

    data object Loading : StreamListScreen

    /** Rows, possibly an empty page after a filter that matches nothing. */
    data object Rows : StreamListScreen

    /** The broker has no streams. */
    data object Empty : StreamListScreen

    /** The user has no management tag (visibility NONE). */
    data object CannotReadStatistics : StreamListScreen

    data class Failed(val reason: String) : StreamListScreen
}

enum class StreamSort { NameAscending, NameDescending }

/** M2-07: what a row stands for. */
enum class StreamRowKind { Stream, SuperStream, Partition }

/** M2-07: the kind chips of the list (default [All]). */
enum class StreamKindFilter { All, Streams, SuperStreams }

/**
 * One row, all cells as text. There is no size (N1). M2-07: [kind], [partitionCount] (super stream rows, else 0), [partitionOf]
 * (partition rows: the super stream's name, else null; M3 reads it for the page title) and [expanded] (super stream rows).
 */
data class StreamRow(
    val name: String,
    val type: String,
    val messages: String,
    val firstOffset: String,
    val lastOffset: String,
    val publishers: String,
    val consumers: String,
    val retention: String,
    val kind: StreamRowKind = StreamRowKind.Stream,
    val partitionCount: Int = 0,
    val partitionOf: String? = null,
    val expanded: Boolean = false,
)

/** The whole state of the stream list. [rows] are the visible page only. */
data class StreamListState(
    val screen: StreamListScreen = StreamListScreen.NotConnected,
    val message: String? = StreamListTexts.NOT_CONNECTED,
    val action: String? = null,
    val rows: List<StreamRow> = emptyList(),
    val filter: String = "",
    val sort: StreamSort = StreamSort.NameAscending,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val totalStreams: Int = 0,
    val rangeText: String = "",
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    /** The rows are older than the last attempt to refresh them (failed refresh or lost connection). */
    val stale: Boolean = false,
    val banner: String? = null,
    val refreshInterval: RefreshInterval = DEFAULT_REFRESH,
    /** Clock time of the last successful load; null before the first one. The screen computes "Updated N s ago" from it. */
    val updatedAtMillis: Long? = null,
    val approximateNote: String = StreamListTexts.APPROXIMATE,
    /** M2-07: the selected kind chip. */
    val kindFilter: StreamKindFilter = StreamKindFilter.All,
    /** M2-07: set while the super stream grouping was never read (the list is flat); null otherwise. */
    val groupingNotice: String? = null,
)

/** Texts of the stream list, shared by core and the screen. */
object StreamListTexts {
    const val NOT_CONNECTED: String = "Not connected"
    const val CONNECTING: String = "Connecting..."
    const val UNAVAILABLE: String = "unavailable"
    const val NOT_AVAILABLE: String = "n/a"
    const val LOADING: String = "loading..."
    const val NO_STREAMS: String = "There are no streams on this broker yet."
    const val CREATE_STREAM: String = "Create stream"
    const val RETRY: String = "Retry"
    const val LOAD_FAILED: String = "The stream list could not be loaded. Check that the virtual host exists and that this user may use it."
    const val SUPER_STREAM: String = "Super stream"
    const val PARTITION: String = "partition"
    const val MIXED: String = "mixed"
    const val NOT_APPLICABLE: String = "\u2013"
    const val PARTITION_ORDER_NOTE: String = "Messages are ordered only inside a partition. Choose a partition to browse it."
    const val GROUPING_UNAVAILABLE: String = "Super stream grouping is unavailable. Partitions are listed as separate streams."
    const val APPROXIMATE: String = "Message counts and offsets are approximate."
}
