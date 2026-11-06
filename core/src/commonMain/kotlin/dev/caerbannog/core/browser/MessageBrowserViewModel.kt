package dev.caerbannog.core.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * M3-02: View model driving the message browser's paging state machine.
 *
 * Implements unidirectional data flow over [StreamMessageReader] without mutating
 * broker state or consumer offsets.
 */
class MessageBrowserViewModel(
    private val reader: StreamMessageReader,
    val vhost: String,
    val stream: String,
    private val scope: CoroutineScope,
    initialPageSize: Int = 100,
    private val approximateTotalProvider: (suspend (vhost: String, stream: String) -> Long?)? = null,
) {
    var pageSize: Int = initialPageSize
        private set

    private val _state = MutableStateFlow<MessageBrowserState>(
        MessageBrowserState.Loading(vhost, stream, BrowseStart.First, initialPageSize)
    )
    val state: StateFlow<MessageBrowserState> = _state.asStateFlow()

    private var pendingRetentionNotice: String? = null

    fun browseFirst() {
        browse(BrowseStart.First)
    }

    fun browseLastPage() {
        browse(BrowseStart.LastPage(pageSize))
    }

    fun browseOffset(offset: Long) {
        browse(BrowseStart.Offset(offset))
    }

    fun browseTimestamp(epochMs: Long) {
        browse(BrowseStart.Timestamp(epochMs))
    }

    fun nextPage() {
        val current = _state.value as? MessageBrowserState.Loaded ?: return
        if (!current.canGoNext) return
        val nextOffset = current.messages.last().offset + 1L
        browse(BrowseStart.Offset(nextOffset))
    }

    fun previousPage() {
        val current = _state.value as? MessageBrowserState.Loaded ?: return
        if (!current.canGoPrevious) return
        val prevOffset = maxOf(current.streamFirstOffset, current.messages.first().offset - pageSize)
        browse(BrowseStart.Offset(prevOffset))
    }

    fun jumpToOffset(offset: Long) {
        browse(BrowseStart.Offset(offset))
    }

    fun jumpToNearestValidOffset() {
        val outside = _state.value as? MessageBrowserState.OutsideRange ?: return
        browse(BrowseStart.Offset(outside.nearestOffset))
    }

    fun setPageSize(size: Int) {
        require(size > 0) { "pageSize must be positive" }
        pageSize = size
        when (val current = _state.value) {
            is MessageBrowserState.Loaded -> {
                val startOffset = current.messages.firstOrNull()?.offset ?: current.streamFirstOffset
                browse(BrowseStart.Offset(startOffset))
            }
            is MessageBrowserState.Loading -> {
                browse(current.start)
            }
            else -> browse(BrowseStart.First)
        }
    }

    fun selectMessage(offset: Long?) {
        val current = _state.value as? MessageBrowserState.Loaded ?: return
        val selected = offset?.let { off -> current.messages.firstOrNull { it.offset == off } }
        _state.value = current.copy(selectedMessage = selected)
    }

    private fun browse(start: BrowseStart) {
        _state.value = MessageBrowserState.Loading(vhost, stream, start, pageSize)
        scope.launch {
            when (val result = reader.readPage(vhost, stream, start, pageSize)) {
                is BrowseResult.Success -> {
                    val firstShown = result.messages.first().offset
                    val lastShown = result.messages.last().offset
                    val canGoPrevious = firstShown > result.streamFirstOffset
                    val canGoNext = lastShown < result.streamLastOffset && result.messages.size >= pageSize
                    val total = approximateTotalProvider?.invoke(vhost, stream)
                        ?: (result.streamLastOffset - result.streamFirstOffset + 1L).coerceAtLeast(0L)

                    val notice = pendingRetentionNotice
                    pendingRetentionNotice = null

                    _state.value = MessageBrowserState.Loaded(
                        vhost = vhost,
                        stream = stream,
                        messages = result.messages,
                        streamFirstOffset = result.streamFirstOffset,
                        streamLastOffset = result.streamLastOffset,
                        approximateTotal = total,
                        pageSize = pageSize,
                        startMode = start,
                        canGoNext = canGoNext,
                        canGoPrevious = canGoPrevious,
                        selectedMessage = null,
                        retentionShiftNotice = notice,
                    )
                }
                is BrowseResult.Empty -> {
                    _state.value = MessageBrowserState.Empty(vhost, stream, result.streamFirstOffset, result.streamLastOffset)
                }
                is BrowseResult.NoMessagesAtOrAfterTime -> {
                    _state.value = MessageBrowserState.NoMessagesAtOrAfterTime(
                        vhost = vhost,
                        stream = stream,
                        epochMs = result.epochMs,
                        streamFirstOffset = result.streamFirstOffset,
                        streamLastOffset = result.streamLastOffset,
                    )
                }
                is BrowseResult.OutsideRange -> {
                    // Check if retention shifted stream bounds past requested start offset
                    if (start is BrowseStart.Offset && start.offset < result.validRange.first) {
                        pendingRetentionNotice = "Stream start moved to offset ${result.validRange.first}"
                        // Automatically re-request at new valid stream start
                        browse(BrowseStart.Offset(result.validRange.first))
                        return@launch
                    }

                    val nearest = if (result.offset < result.validRange.first) {
                        result.validRange.first
                    } else {
                        result.validRange.last
                    }
                    _state.value = MessageBrowserState.OutsideRange(
                        vhost = vhost,
                        stream = stream,
                        requestedOffset = result.offset,
                        validRange = result.validRange,
                        nearestOffset = nearest,
                    )
                }
                is BrowseResult.NotFound -> {
                    _state.value = MessageBrowserState.Error(vhost, stream, "Stream '$stream' not found")
                }
                is BrowseResult.AccessDenied -> {
                    _state.value = MessageBrowserState.Error(vhost, stream, "Access denied for stream '$stream'")
                }
                is BrowseResult.Failed -> {
                    _state.value = MessageBrowserState.Error(vhost, stream, result.reason)
                }
            }
        }
    }
}
