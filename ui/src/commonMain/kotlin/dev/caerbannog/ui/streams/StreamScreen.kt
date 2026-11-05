package dev.caerbannog.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.PublisherRowState
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.UpdatedAge
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/** Events out of the stream screen (M2-06b). Every one is asynchronous in the view model; read the result from the state. */
interface StreamClientsEvents {
    /** The tab is on screen for [stream] (enters the composition). The view model calls the broker only while shown (D3). */
    fun show(stream: String)

    /** The tab left the composition. */
    fun hide()

    fun refreshNow()
    fun retry()

    /** The back arrow and the Back to streams button: close the stream screen, the list comes back. */
    fun back()
}

private const val LOADING_STATE = "Loading"
private const val STALE_STATE = "Stale"
private const val LOADING_PLACEHOLDER_ROWS = 2
private const val PUBLISHERS_TAB = "Publishers and consumers"
private val CellPadding = 6.dp
private val OkGreen = Color(0xFF386A20)

/** A tab of the stream screen. The shell takes a list so the Messages tab (M3) is one more entry, not a redesign. */
private class StreamTab(val title: String, val content: @Composable () -> Unit)

/**
 * The stream screen (`docs/ux/Clients.dc.html`): app frame with the back arrow, the tab list, and the tab content. It draws the
 * strings of [state] and decides none. [nowMillis] is the clock for "Updated N s ago" (ticked by the caller only while this is shown).
 * The Publishers and consumers tab reports [StreamClientsEvents.show] on entering and `hide` on leaving the composition (D3).
 */
@Composable
fun StreamScreen(
    /** The stream that is open: the title, and the argument of [StreamClientsEvents.show] (the state has no stream before its first load). */
    stream: String,
    state: StreamClientsState,
    nowMillis: Long,
    events: StreamClientsEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
) {
    val tabs = listOf(StreamTab(PUBLISHERS_TAB) { ClientsTab(stream, state, events) })
    var selected by remember { mutableIntStateOf(0) }
    AppFrame(
        title = stream,
        control = control,
        events = controlEvents,
        onNavigate = onNavigate,
        // The stream screen is a level below Streams: the drawer keeps Streams selected.
        activeItem = AppDestination.Streams.name,
        leading = {
            IconButton(
                onClick = events::back,
                modifier = Modifier.testTag("backArrow").semantics { contentDescription = StreamClientsTexts.BACK_TO_STREAMS },
            ) { ConnectionIcon(AppIcon.ArrowBack, IconSize) }
        },
        actions = { ClientsActions(state, nowMillis, events) },
    ) {
        Column(Modifier.fillMaxSize()) {
            TabList(tabs, selected) { selected = it }
            Box(Modifier.weight(1f).fillMaxWidth()) { tabs[selected].content() }
        }
    }
}

@Composable
private fun RowScope.ClientsActions(state: StreamClientsState, nowMillis: Long, events: StreamClientsEvents) {
    UpdatedAge.text(nowMillis, state.updatedAtMillis)?.let { age ->
        Text(
            age,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("updatedAge"),
        )
    }
    // Same rule as the list: Refresh is useful only while rows are on screen and nothing is wrong; a double press is harmless (D3).
    val canRefresh = state.screen is StreamClientsScreen.Content && state.banner == null
    IconButton(
        onClick = events::refreshNow,
        enabled = canRefresh,
        modifier = Modifier.testTag("refreshIcon").semantics { contentDescription = StreamClientsTexts.REFRESH },
    ) { ConnectionIcon(AppIcon.Refresh, IconSize) }
}

@Composable
private fun TabList(tabs: List<StreamTab>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(Modifier.testTag("tabList").padding(horizontal = 24.dp)) {
            tabs.forEachIndexed { index, tab ->
                val on = index == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tab.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag("tab:${tab.title}")
                            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Box(Modifier.height(3.dp).width(32.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider()
    }
}

/** The tab. It tells the view model it is shown for [stream] while it is in the composition (D3). */
@Composable
private fun ClientsTab(stream: String, state: StreamClientsState, events: StreamClientsEvents) {
    val currentEvents by rememberUpdatedState(events)
    DisposableEffect(stream) {
        currentEvents.show(stream)
        onDispose { currentEvents.hide() }
    }
    when (val screen = state.screen) {
        StreamClientsScreen.Content, StreamClientsScreen.Loading -> ClientsSections(state, loading = screen == StreamClientsScreen.Loading, events)
        StreamClientsScreen.PluginMissing -> ClientsScroll {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pluginMissingBanner").padding(16.dp))
            }
            LastOffset(state)
        }
        StreamClientsScreen.StreamGone -> Message(state.message) {
            Button(onClick = events::back) { Text(StreamClientsTexts.BACK_TO_STREAMS, modifier = Modifier.testTag("backToStreams")) }
        }
        is StreamClientsScreen.Failed -> Message(state.message ?: screen.reason) {
            Button(onClick = events::retry) { Text(StreamListTexts.RETRY) }
        }
        StreamClientsScreen.NotConnected, StreamClientsScreen.Connecting, StreamClientsScreen.CannotReadStatistics -> Message(state.message)
    }
}

@Composable
private fun ClientsScroll(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun LastOffset(state: StreamClientsState) {
    if (state.lastOffset.isNotBlank()) {
        Text(
            "${StreamClientsTexts.LAST_OFFSET_LABEL} ${state.lastOffset}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("lastOffset"),
        )
    }
}

@Composable
private fun ClientsSections(state: StreamClientsState, loading: Boolean, events: StreamClientsEvents) {
    ClientsScroll {
        state.banner?.let { LostConnectionBanner(it, StreamListTexts.RETRY, events::retry, tag = "clientsBanner") }
        state.ownConnectionsNotice?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionIcon(AppIcon.Info, SmallIconSize)
                    Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ownConnectionsNotice"))
                }
            }
        }
        val stale = state.stale && !loading
        val publishers = if (loading) null else state.publishers
        val consumers = if (loading) null else state.consumers
        PublishersSection(publishers, stale)
        ConsumersSection(consumers, stale, state)
        if (!loading) {
            Text(
                StreamClientsTexts.FOOTNOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("clientsFootnote"),
            )
        }
    }
}

private class ClientColumn(val key: String, val header: String, val width: Dp, val numeric: Boolean = false)

private val PublisherColumns = listOf(
    ClientColumn("Reference", StreamClientsTexts.HEADER_REFERENCE, 200.dp),
    ClientColumn("Connection", StreamClientsTexts.HEADER_CONNECTION, 300.dp),
    ClientColumn("Published", StreamClientsTexts.HEADER_PUBLISHED, 110.dp, numeric = true),
    ClientColumn("Confirmed", StreamClientsTexts.HEADER_CONFIRMED, 110.dp, numeric = true),
    ClientColumn("Errors", StreamClientsTexts.HEADER_ERRORS, 90.dp, numeric = true),
)

private val ConsumerColumns = listOf(
    ClientColumn("Consumer", StreamClientsTexts.HEADER_CONSUMER, 200.dp),
    ClientColumn("Connection", StreamClientsTexts.HEADER_CONNECTION, 300.dp),
    ClientColumn("Offset", StreamClientsTexts.HEADER_OFFSET, 110.dp, numeric = true),
    ClientColumn("Behind", StreamClientsTexts.HEADER_MESSAGES_BEHIND, 150.dp, numeric = true),
    ClientColumn("Status", StreamClientsTexts.HEADER_STATUS, 160.dp),
)

@Composable
private fun PublishersSection(rows: List<PublisherRowState>?, stale: Boolean) {
    ClientsSection("publishers", StreamClientsTexts.PUBLISHERS_TITLE, rows?.size, PublisherColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_PUBLISHERS, stale) { scale ->
        rows?.forEachIndexed { index, row -> PublisherRow(index, row, scale) }
    }
}

@Composable
private fun ConsumersSection(rows: List<ConsumerRowState>?, stale: Boolean, state: StreamClientsState) {
    ClientsSection(
        "consumers", StreamClientsTexts.CONSUMERS_TITLE, rows?.size, ConsumerColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_CONSUMERS, stale,
        captionExtra = { if (rows != null) LastOffset(state) },
    ) { scale ->
        // The key is the row identity (a consumer that disappears or reorders keeps the others' state).
        rows?.forEach { row -> key(row.key) { ConsumerRow(row, scale) } }
    }
}

/**
 * One section: caption with the count, the header, and either the rows, "loading..." rows ([count] null) or the empty sentence.
 * The table node announces the caption (so the count is heard) and carries "Loading" or "Stale" as its state description,
 * because dimming alone is a colour-only signal.
 */
@Composable
private fun ClientsSection(
    prefix: String,
    title: String,
    count: Int?,
    columns: List<ClientColumn>,
    empty: Boolean,
    emptyText: String,
    stale: Boolean,
    captionExtra: @Composable () -> Unit = {},
    rows: @Composable (scale: Float) -> Unit,
) {
    val loading = count == null
    val caption = if (count == null) title else "$title $count"
    Column(Modifier.testTag("${prefix}Section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.clearAndSetSemantics {
                    set(SemanticsProperties.TestTag, "${prefix}Caption")
                    set(SemanticsProperties.Text, listOfNotNull(AnnotatedString(title), count?.let { AnnotatedString(it.toString()) }))
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                count?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            captionExtra()
        }
        val total = columns.fold(0.dp) { sum, column -> sum + column.width }
        BoxWithConstraints {
            val scale = if (maxWidth.value.isFinite()) (maxWidth / total).coerceAtLeast(1f) else 1f
            Column(
                Modifier
                    .testTag("${prefix}Table")
                    .semantics {
                        contentDescription = caption
                        if (loading) stateDescription = LOADING_STATE else if (stale) stateDescription = STALE_STATE
                    }
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = total),
            ) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    columns.forEach { column ->
                        Text(
                            column.header,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding).testTag("${prefix}Header:${column.key}"),
                        )
                    }
                }
                HorizontalDivider()
                when {
                    loading -> repeat(LOADING_PLACEHOLDER_ROWS) { index -> LoadingRow(columns, scale, first = index == 0) }
                    empty -> Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("${prefix}Empty").padding(vertical = 16.dp, horizontal = CellPadding),
                    )
                    // One SelectionContainer per table: mouse selection as in the list; every cell adds the keyboard copy (UI1).
                    else -> SelectionContainer {
                        Column(Modifier.alpha(if (stale) 0.6f else 1f)) { rows(scale) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(columns: List<ClientColumn>, scale: Float, first: Boolean) {
    // Only the first row is read by a screen reader; the table already says "Loading".
    Row(
        Modifier.heightIn(min = 48.dp).then(if (first) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Text(
                StreamClientsTexts.LOADING,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding),
            )
        }
    }
}

// Cells are top aligned on purpose: the selection container orders its texts by position, and a centred one-line cell next to a
// two-line connection cell would be ordered after the connection, so a drag over it selected the wrong text.
@Composable
private fun PublisherRow(index: Int, row: PublisherRowState, scale: Float) {
    val tag = "publisherCell:$index"
    Column {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.reference, "$tag:Reference", PublisherColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, PublisherColumns[1], scale)
            ValueCell(row.published, "$tag:Published", PublisherColumns[2], scale)
            ValueCell(row.confirmed, "$tag:Confirmed", PublisherColumns[3], scale)
            ValueCell(row.errors, "$tag:Errors", PublisherColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ConsumerRow(row: ConsumerRowState, scale: Float) {
    val tag = "consumerCell:${row.key}"
    Column {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.name, "$tag:Name", ConsumerColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, ConsumerColumns[1], scale)
            ValueCell(row.offset, "$tag:Offset", ConsumerColumns[2], scale)
            ValueCell(row.behind, "$tag:Behind", ConsumerColumns[3], scale)
            StatusCell(row.status, "$tag:Status", row.key, ConsumerColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ValueCell(text: String, tag: String, column: ClientColumn, scale: Float, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    // The padding is on the box, not on the text node: a drag that starts at the edge of the cell must still start a selection.
    Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = 6.dp)) {
        CopyableText(text, tag, style, if (column.numeric) TextAlign.End else TextAlign.Start)
    }
}

/** The socket pair with the user under it; a missing user is an em dash that screen readers hear as the NO_USER_NAME text. */
@Composable
private fun ConnectionCell(connection: String, user: String, tag: String, column: ClientColumn, scale: Float) {
    Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = 6.dp)) {
        Column {
            CopyableText(connection, "$tag:Connection", MaterialTheme.typography.bodyMedium)
            CopyableText(
                user, "$tag:User", MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                extra = if (user == StreamClientsTexts.NO_USER) Modifier.semantics { contentDescription = StreamClientsTexts.NO_USER_NAME } else Modifier,
            )
        }
    }
}

/**
 * Status is text, never colour alone. The stored-offsets hint is a hover tooltip, the state description (screen readers) AND an
 * "i" control the keyboard can focus, because a tooltip cannot be reached from a keyboard. "N behind" is neutral on purpose
 * (lag ruling): never amber or red.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusCell(status: ConsumerStatus, tag: String, rowKey: String, column: ClientColumn, scale: Float) {
    val hint = status.hint
    val cell: @Composable () -> Unit = {
        Row(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // CopyableText fills the width it is given, so the weighted box keeps room for the "i" control.
            Box(Modifier.weight(1f)) {
                CopyableText(
                    status.text, tag, MaterialTheme.typography.bodyMedium,
                    color = if (status == ConsumerStatus.CaughtUp) OkGreen else Color.Unspecified,
                    extra = if (hint != null) Modifier.semantics { stateDescription = hint } else Modifier,
                )
            }
            if (hint != null) {
                Spacer(Modifier.width(6.dp))
                StatusHintControl(hint, rowKey)
            }
        }
    }
    if (hint == null) {
        cell()
    } else {
        // The area shows on hover after a delay and never on keyboard focus; the "i" control covers the keyboard.
        TooltipArea(
            tooltip = {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.widthIn(max = 320.dp).padding(8.dp))
                }
            },
        ) { cell() }
    }
}

/**
 * A focusable "i" that shows [hint] in an INLINE popup while it has keyboard focus (not a Popup window: that would add a second root).
 * Escape closes the popup and keeps the focus; the popup is drawn above the cell with zero layout size so the row does not move.
 */
@Composable
private fun StatusHintControl(hint: String, rowKey: String) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(20.dp)
            .onKeyEvent { event ->
                if (focused && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    dismissed = true
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) dismissed = false
            }
            .testTag("statusHint:$rowKey")
            .clip(shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClickLabel = StreamClientsTexts.HINT_CONTROL_LABEL) { dismissed = !dismissed },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (focused && !dismissed) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
                    layout(0, 0) { placeable.place(0, -placeable.height - 4.dp.roundToPx()) }
                },
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.widthIn(max = 320.dp).padding(8.dp).testTag("statusHintPopup:$rowKey"),
                )
            }
        }
    }
}
