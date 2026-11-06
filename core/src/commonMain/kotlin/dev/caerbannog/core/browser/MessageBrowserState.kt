package dev.caerbannog.core.browser

/**
 * State of the message browser.
 *
 * Unidirectional state representing the current page of messages, stream bounds,
 * navigation constraints, and error/edge notices.
 */
sealed interface MessageBrowserState {
    val vhost: String
    val stream: String

    data class Loading(
        override val vhost: String,
        override val stream: String,
        val start: BrowseStart,
        val pageSize: Int,
    ) : MessageBrowserState

    data class Empty(
        override val vhost: String,
        override val stream: String,
        val streamFirstOffset: Long = 0L,
        val streamLastOffset: Long = 0L,
    ) : MessageBrowserState

    data class NoMessagesAtOrAfterTime(
        override val vhost: String,
        override val stream: String,
        val epochMs: Long,
        val streamFirstOffset: Long,
        val streamLastOffset: Long,
    ) : MessageBrowserState

    data class OutsideRange(
        override val vhost: String,
        override val stream: String,
        val requestedOffset: Long,
        val validRange: LongRange,
        val nearestOffset: Long,
    ) : MessageBrowserState

    data class Loaded(
        override val vhost: String,
        override val stream: String,
        val messages: List<StreamMessage>,
        val streamFirstOffset: Long,
        val streamLastOffset: Long,
        val approximateTotal: Long?,
        val pageSize: Int,
        val startMode: BrowseStart,
        val canGoNext: Boolean,
        val canGoPrevious: Boolean,
        val selectedMessage: StreamMessage? = null,
        val retentionShiftNotice: String? = null,
    ) : MessageBrowserState {
        val summaryText: String
            get() {
                if (messages.isEmpty()) return "No messages"
                val first = messages.first().offset
                val last = messages.last().offset
                val totalStr = approximateTotal?.let { " of about $it" } ?: ""
                return "Offsets $first to $last$totalStr"
            }
    }

    data class Error(
        override val vhost: String,
        override val stream: String,
        val message: String,
    ) : MessageBrowserState
}
