package dev.caerbannog.core.streams

/** What the Publishers and consumers tab shows (spec "Publishers and consumers tab"). Display strings only (UI1). */
sealed interface StreamClientsScreen {
    data object NotConnected : StreamClientsScreen

    data object Connecting : StreamClientsScreen

    data object Loading : StreamClientsScreen

    /** Both sections, possibly empty. */
    data object Content : StreamClientsScreen

    data object CannotReadStatistics : StreamClientsScreen

    data object PluginMissing : StreamClientsScreen

    data object StreamGone : StreamClientsScreen

    data class Failed(val reason: String) : StreamClientsScreen
}

/** Status of a consumer row. [text] is what the table shows (status is text, never colour alone). */
sealed interface ConsumerStatus {
    val text: String

    /** Tooltip text of the status cell; null when there is none. Only [Behind] carries one ([StreamClientsTexts.STORED_OFFSETS_HINT]). */
    val hint: String? get() = null

    /** `offsetLag` 0 only (lag ruling 2026-11-05). */
    data object CaughtUp : ConsumerStatus { override val text: String = StreamClientsTexts.CAUGHT_UP }

    /** `offsetLag` above 0: the broker's number, grouped digits, neutral style, never a verdict. */
    data class Behind(val lag: Long) : ConsumerStatus {
        override val text: String = "${groupDigits(lag)} behind"
        override val hint: String = StreamClientsTexts.STORED_OFFSETS_HINT
    }

    /** Single-active-consumer standby (brief Open point 3). */
    data object NotActive : ConsumerStatus { override val text: String = StreamClientsTexts.NOT_ACTIVE }

    /** A row whose connection name is not known yet (just attached). */
    data object Updating : ConsumerStatus { override val text: String = StreamClientsTexts.UPDATING }
}

/** [settling]: the broker's connection details are still empty; connection reads "unknown" and the counters read "updating". */
data class PublisherRowState(
    val connection: String,
    val user: String,
    val reference: String,
    val published: String,
    val confirmed: String,
    val errors: String,
    val settling: Boolean,
)

/** [key] is unique among the rows of one state. [browseFromOffset] is the consumer's `offset`, null for a settling row (carried for M3, nothing uses it). */
data class ConsumerRowState(
    val key: String,
    val name: String,
    val connection: String,
    val user: String,
    val offset: String,
    val behind: String,
    val status: ConsumerStatus,
    val browseFromOffset: Long?,
)

data class StreamClientsState(
    val screen: StreamClientsScreen = StreamClientsScreen.NotConnected,
    /** The sentence of a screen without sections (denied, plugin missing, stream gone, failed, not connected...), else null. */
    val message: String? = StreamClientsTexts.NOT_CONNECTED,
    val stream: String = "",
    val lastOffset: String = "",
    val publishers: List<PublisherRowState> = emptyList(),
    val consumers: List<ConsumerRowState> = emptyList(),
    val ownConnectionsNotice: String? = null,
    val stale: Boolean = false,
    val banner: String? = null,
    val updatedAtMillis: Long? = null,
    val refreshInterval: RefreshInterval = DEFAULT_REFRESH,
)

object StreamClientsTexts {
    const val NOT_CONNECTED: String = "Not connected"
    const val CONNECTING: String = "Connecting..."
    const val LOADING: String = "loading..."
    const val UNKNOWN: String = "unknown"
    const val UPDATING: String = "updating"
    const val UNNAMED: String = "unnamed"

    /** Shown for a row whose broker user is not known (an em dash). */
    const val NO_USER: String = "\u2014"
    const val CAUGHT_UP: String = "Caught up"
    const val STORED_OFFSETS_HINT: String = "Stored offsets count as messages, so a consumer that stores its offset can read a few behind when it has read everything."
    const val NOT_ACTIVE: String = "Not active"
    const val OWN_CONNECTIONS_NOTICE: String = "Showing only your own connections. The monitoring tag shows all."
    const val PLUGIN_MISSING: String = "The stream management plugin is not enabled. Enable rabbitmq_stream_management on the broker."
    const val STREAM_GONE: String = "This stream no longer exists."
    const val LOAD_FAILED: String = "Publishers and consumers could not be loaded."
    const val NO_PUBLISHERS: String = "There are no publishers on this stream."
    const val NO_CONSUMERS: String = "There are no consumers on this stream."
    const val LAST_OFFSET_LABEL: String = "Last offset on the stream:"
    const val BACK_TO_STREAMS: String = "Back to streams"
    const val FOOTNOTE: String = "Read from the management API (rabbitmq_stream_management). Messages behind comes from the broker and can be higher " +
        "than the real distance for a consumer that stores its offset (each stored offset counts). Caught up only at 0."
}

/** Something the connection control can refresh and age: the shown screen's view model (M2-06 brief item 6). */
interface RefreshTarget {
    /** Clock time of the last successful load of the shown screen; null before the first one. */
    val updatedAtMillis: kotlinx.coroutines.flow.StateFlow<Long?>

    fun refreshNow()
}
