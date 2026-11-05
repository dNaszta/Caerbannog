package dev.caerbannog.ui.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.core.streams.UpdatedAge
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/** Events out of the stream list screen. Every one is asynchronous in the view model; read the result from the state. */
interface StreamListEvents {
    fun refreshNow()
    fun retry()
    fun setFilter(text: String)
    fun setSort(sort: StreamSort)
    fun nextPage()
    fun previousPage()

    /** Expand or collapse the super stream [name]. */
    fun toggleExpanded(name: String)

    /** The kind chips (All, Streams, Super streams). */
    fun setKindFilter(kind: StreamKindFilter)

    /** The Create stream button (enabled by [CreateStreamGate]). The dialog is M3: the app wires this to nothing in M2-05. */
    fun createStream()

    /**
     * The list is on screen (true when the screen enters the composition, false when it leaves). The view model calls the broker only
     * while it is shown (D3), so a screen that forgets this polls forever or never loads.
     */
    fun setShown(shown: Boolean)
}

/**
 * The stream list (`docs/ux/Streams.dc.html`), drawn inside the app frame. It draws the strings of [state] and decides none
 * (message, range text, note, cells). [nowMillis] is the clock for "Updated N s ago"; the caller ticks it once a second, and only
 * while this screen is shown. The screen reports [StreamListEvents.setShown] on entering and leaving the composition.
 */
@Composable
fun StreamsScreen(
    state: StreamListState,
    nowMillis: Long,
    createStream: CreateStreamGate,
    events: StreamListEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
    /** The name cell of a row is a link: click or Enter calls this with the stream name (M2-06b). */
    onOpenStream: (String) -> Unit = {},
) {
    val currentEvents by rememberUpdatedState(events)
    DisposableEffect(Unit) {
        currentEvents.setShown(true)
        onDispose { currentEvents.setShown(false) }
    }
    AppFrame(
        title = AppDestination.Streams.name,
        control = control,
        events = controlEvents,
        onNavigate = onNavigate,
        actions = { StreamsActions(state, nowMillis, createStream, events) },
    ) {
        StreamsBody(state, createStream, events, onOpenStream)
    }
}

@Composable
private fun RowScope.StreamsActions(state: StreamListState, nowMillis: Long, gate: CreateStreamGate, events: StreamListEvents) {
    UpdatedAge.text(nowMillis, state.updatedAtMillis)?.let { age ->
        Text(
            age,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("updatedAge"),
        )
    }
    // Refresh is useful only while a list is on screen and nothing is wrong; a double press is harmless (latest started wins, D3).
    val canRefresh = (state.screen is StreamListScreen.Rows || state.screen is StreamListScreen.Empty) && state.banner == null
    IconButton(
        onClick = events::refreshNow,
        enabled = canRefresh,
        modifier = Modifier.testTag("refreshIcon").semantics { contentDescription = StreamClientsTexts.REFRESH },
    ) {
        ConnectionIcon(AppIcon.Refresh, IconSize)
    }
    CreateStreamButton(gate, onClick = events::createStream, modifier = Modifier.testTag("createStream"), filled = false)
}

/**
 * The Create stream button, gated (never hidden). A disabled one shows the reason as a tooltip AND as its state description,
 * because a tooltip is not reachable from a keyboard or a screen reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateStreamButton(gate: CreateStreamGate, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    val reason = (gate as? CreateStreamGate.Disabled)?.reason
    val content: @Composable () -> Unit = {
        val button: @Composable () -> Unit = {
            ConnectionIcon(AppIcon.Add, SmallIconSize)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(StreamListTexts.CREATE_STREAM)
        }
        val buttonModifier = modifier.semantics { if (reason != null) stateDescription = reason }
        if (filled) Button(onClick, buttonModifier, enabled = reason == null) { button() }
        else FilledTonalButton(onClick, buttonModifier, enabled = reason == null) { button() }
    }
    if (reason == null) {
        content()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(reason) } },
            state = rememberTooltipState(),
        ) { content() }
    }
}

@Composable
private fun StreamsBody(state: StreamListState, gate: CreateStreamGate, events: StreamListEvents, onOpen: (String) -> Unit) {
    when (val screen = state.screen) {
        StreamListScreen.Rows -> StreamsRowsView(state, events, onOpen)
        StreamListScreen.Loading -> Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            StreamsTable(rows = null, state = state, events = events)
        }
        StreamListScreen.Empty -> Message(state.message) {
            CreateStreamButton(gate, onClick = events::createStream, modifier = Modifier.testTag("createStreamEmpty"), filled = true)
        }
        is StreamListScreen.Failed -> Message(state.message ?: screen.reason) {
            Button(onClick = events::retry) { Text(state.action ?: StreamListTexts.RETRY) }
        }
        StreamListScreen.NotConnected, StreamListScreen.Connecting, StreamListScreen.CannotReadStatistics -> Message(state.message)
    }
}

/** A centred message, with an optional action under it. */
@Composable
internal fun Message(text: String?, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

@Composable
private fun StreamsRowsView(state: StreamListState, events: StreamListEvents, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.banner?.let { LostConnectionBanner(it, state.action ?: StreamListTexts.RETRY, events::retry) }
        state.groupingNotice?.let { GroupingNotice(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StreamsSearchField(state.filter, events::setFilter)
            KindChips(state.kindFilter, events::setKindFilter)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { StreamsTable(state.rows, state, events, onOpen) }
        StreamsFooter(state, events)
    }
}

/** The kind chips of the mockup; the selected one follows the state, a click sends the kind. */
@Composable
private fun KindChips(selected: StreamKindFilter, onSelect: (StreamKindFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StreamKindFilter.entries.forEach { kind ->
            val label = when (kind) {
                StreamKindFilter.All -> "All"
                StreamKindFilter.Streams -> "Streams"
                StreamKindFilter.SuperStreams -> "Super streams"
            }
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(label) },
                // The label is also the node's own text, so the chip reads the same in the unmerged tree.
                modifier = Modifier.testTag("kindChip:${kind.name}").semantics { text = AnnotatedString(label) },
            )
        }
    }
}

/** Shown while the super stream grouping was never read: the list is flat and says why. Not the lost-connection banner. */
@Composable
private fun GroupingNotice(message: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(AppIcon.Info, SmallIconSize)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("groupingNotice"),
            )
        }
    }
}

/** The lost-connection banner (D2): the rows below stay, dimmed; the user decides when to retry. */
@Composable
internal fun LostConnectionBanner(message: String, action: String, onRetry: () -> Unit, tag: String = "streamsBanner") {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .testTag(tag)
                // The row carries the message itself (a polite live region says it when it appears); the visible text is hidden from
                // the tree so it is not read twice.
                .semantics { text = AnnotatedString(message); liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(AppIcon.Error, SmallIconSize, tint = MaterialTheme.colorScheme.error)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
            )
            TextButton(onClick = onRetry) { Text(action) }
        }
    }
}

@Composable
private fun StreamsFooter(state: StreamListState, events: StreamListEvents) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val style = MaterialTheme.typography.bodySmall
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        Text(state.approximateNote, style = style, color = dim, modifier = Modifier.weight(1f).testTag("approximateNote"))
        Text(state.rangeText, style = style, color = dim, modifier = Modifier.testTag("rangeText"))
        PageButton(AppIcon.ChevronLeft, "Previous page", "pagePrevious", state.hasPreviousPage, events::previousPage)
        PageButton(AppIcon.ChevronRight, "Next page", "pageNext", state.hasNextPage, events::nextPage)
    }
}

@Composable
private fun PageButton(icon: AppIcon, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = description }) {
        ConnectionIcon(icon, IconSize)
    }
}
