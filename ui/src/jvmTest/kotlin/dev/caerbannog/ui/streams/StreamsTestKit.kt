package dev.caerbannog.ui.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.StreamRow
import dev.caerbannog.core.streams.StreamRowKind
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.ui.appframe.AppDestination

/** Shared builders of the M2-05c Compose tests: states as the view model builds them, and an event recorder. */
internal class RecordedEvents : StreamListEvents {
    val calls = mutableListOf<String>()

    /** The calls without the setShown bookkeeping, for tests about what the user pressed. */
    fun sent(): List<String> = calls.filterNot { it.startsWith("setShown:") }
    override fun refreshNow() { calls += "refreshNow" }
    override fun retry() { calls += "retry" }
    override fun setFilter(text: String) { calls += "setFilter:$text" }
    override fun setSort(sort: StreamSort) { calls += "setSort:$sort" }
    override fun nextPage() { calls += "nextPage" }
    override fun previousPage() { calls += "previousPage" }
    override fun createStream() { calls += "createStream" }
    override fun setShown(shown: Boolean) { calls += "setShown:$shown" }
    override fun toggleExpanded(name: String) { calls += "toggleExpanded:$name" }
    override fun setKindFilter(kind: StreamKindFilter) { calls += "setKindFilter:$kind" }
}

internal fun row(
    name: String = "orders-events",
    type: String = "stream",
    messages: String = "48,300",
    first: String = "0",
    last: String = "48299",
    publishers: String = "2",
    consumers: String = "3",
    retention: String = "7D",
    kind: StreamRowKind = StreamRowKind.Stream,
    partitionCount: Int = 0,
    partitionOf: String? = null,
    expanded: Boolean = false,
) = StreamRow(name, type, messages, first, last, publishers, consumers, retention, kind, partitionCount, partitionOf, expanded)

/** M2-07: a super stream row as the view model builds it (the mockup's "orders": 96,100 messages, offsets a dash, 4 and 6). */
internal fun superRow(name: String = "orders", partitionCount: Int = 3, expanded: Boolean = false) = row(
    name, type = "Super stream", messages = "96,100", first = "\u2013", last = "\u2013", publishers = "4", consumers = "6", retention = "7D",
    kind = StreamRowKind.SuperStream, partitionCount = partitionCount, expanded = expanded,
)

/** M2-07: a partition row as the view model builds it. */
internal fun partitionRow(name: String, of: String = "orders", messages: String = "31,200", last: String = "31199") = row(
    name, type = "partition", messages = messages, first = "0", last = last, publishers = "1", consumers = "2", retention = "7D",
    kind = StreamRowKind.Partition, partitionOf = of,
)

/** The mockup's expanded super stream: orders and its three partitions, behind one plain stream. */
internal val expandedRows = listOf(
    row("audit-log", messages = "1,204,000", first = "880000", last = "2083999"),
    superRow(expanded = true),
    partitionRow("orders-0", messages = "31,200", last = "31199"),
    partitionRow("orders-1", messages = "32,900", last = "32899"),
    partitionRow("orders-2", messages = "32,000", last = "31999"),
)

/** The state the view model builds for rows (message null, no banner). */
internal fun rowsState(
    rows: List<StreamRow> = listOf(row()),
    filter: String = "",
    sort: StreamSort = StreamSort.NameAscending,
    totalStreams: Int = rows.size,
    rangeText: String = if (rows.isEmpty()) "" else "1 to ${rows.size} of $totalStreams",
    hasNextPage: Boolean = false,
    hasPreviousPage: Boolean = false,
    stale: Boolean = false,
    banner: String? = null,
    action: String? = null,
    updatedAtMillis: Long? = null,
    kindFilter: StreamKindFilter = StreamKindFilter.All,
    groupingNotice: String? = null,
) = StreamListState(
    screen = StreamListScreen.Rows, message = null, action = action, rows = rows, filter = filter, sort = sort,
    totalStreams = totalStreams, rangeText = rangeText, hasNextPage = hasNextPage, hasPreviousPage = hasPreviousPage,
    stale = stale, banner = banner, updatedAtMillis = updatedAtMillis, kindFilter = kindFilter, groupingNotice = groupingNotice,
)

/** A state without rows, with the message and action the view model gives that screen. */
internal fun messageState(screen: StreamListScreen, message: String?, action: String? = null) =
    StreamListState(screen = screen, message = message, action = action)

internal val notConnected = messageState(StreamListScreen.NotConnected, StreamListTexts.NOT_CONNECTED)
internal val connecting = messageState(StreamListScreen.Connecting, StreamListTexts.CONNECTING)
internal val loading = messageState(StreamListScreen.Loading, StreamListTexts.LOADING)
internal val empty = messageState(StreamListScreen.Empty, StreamListTexts.NO_STREAMS, StreamListTexts.CREATE_STREAM)

/** Denied: the literal of the brief (item 14), not the constant, so a rewording is a visible change. */
internal const val DENIED_TEXT = "This user cannot read stream statistics. Ask for the monitoring tag."
internal val denied = messageState(StreamListScreen.CannotReadStatistics, DENIED_TEXT)

internal const val LOAD_FAILED_TEXT = "The stream list could not be loaded. Check that the virtual host exists and that this user may use it."
internal val loadFailed = messageState(StreamListScreen.Failed(LOAD_FAILED_TEXT), LOAD_FAILED_TEXT, "Retry")

internal val connected = ConnectionControlState(
    chip = ConnectionChipState(ChipState.Connected, ChipIcon.Link, "prod-eu", null, null, null, "Connection prod-eu, Connected. Open connection menu", false),
    rows = emptyList(),
    emptyText = null,
    refresh = RefreshSectionState(RefreshInterval.Every5, enabled = true, note = "Not loaded yet"),
)

internal val noControlEvents: ConnectionControlEvents = object : ConnectionControlEvents {
    override fun connect(name: String) = Unit
    override fun disconnect(name: String) = Unit
    override fun retry(name: String) = Unit
    override fun setRefreshInterval(interval: RefreshInterval) = Unit
    override fun refreshNow() = Unit
    override fun openConnections() = Unit
}

/** What the screen is showing now; assign to change it in a running test (then `waitForIdle()`). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal class Shown(state: StreamListState, gate: CreateStreamGate, now: Long, val events: RecordedEvents) {
    var state by mutableStateOf(state)
    var gate by mutableStateOf(gate)
    var now by mutableStateOf(now)
}

/** Sets the content to the screen once; change it later through the returned [Shown]. Tags: see the KDoc of [StreamsScreenUiTest]. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.screen(
    state: StreamListState,
    events: RecordedEvents = RecordedEvents(),
    gate: CreateStreamGate = CreateStreamGate.Enabled,
    nowMillis: Long = 0,
    onNavigate: (AppDestination) -> Unit = {},
    wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
): Shown {
    val shown = Shown(state, gate, nowMillis, events)
    setContent {
        wrap {
            StreamsScreen(shown.state, shown.now, shown.gate, events, connected, noControlEvents, onNavigate)
        }
    }
    return shown
}
