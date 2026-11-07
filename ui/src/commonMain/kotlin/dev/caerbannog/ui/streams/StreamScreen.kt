package dev.caerbannog.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.DeleteStreamGate
import dev.caerbannog.core.publish.PublishGate
import dev.caerbannog.core.publish.PublishMessageState
import dev.caerbannog.core.streams.DeleteStreamState
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.ui.components.LostConnectionBanner
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.UpdatedAge
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize
import dev.caerbannog.ui.publish.PublishEvents
import dev.caerbannog.ui.publish.PublishTestMessageDialog

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

private val ClientsHorizontalPadding = 24.dp
private const val MESSAGES_TAB = "Messages"
private const val PUBLISHERS_TAB = "Publishers and consumers"

/** A tab of the stream screen. The shell takes a list so the Messages tab (M3) is one more entry, not a redesign. */
private class StreamTab(val title: String, val content: @Composable () -> Unit)

/**
 * The stream screen (`docs/ux/Clients.dc.html` and `docs/ux/Browser.dc.html`): app frame with the back arrow, the tab list, and the tab content. It draws the
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
    browserState: dev.caerbannog.core.browser.MessageBrowserState? = null,
    browserEvents: MessageBrowserEvents = EmptyMessageBrowserEvents,
    initialTab: Int = 0,
    publishState: PublishMessageState? = null,
    publishEvents: PublishEvents? = null,
    showPublishDialog: Boolean = false,
    onOpenPublishDialog: () -> Unit = {},
    deleteStreamState: DeleteStreamState? = null,
    deleteStreamGate: DeleteStreamGate = DeleteStreamGate.Enabled,
    onDeleteConfirmationChange: (String) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onOpenDeleteDialog: () -> Unit = {},
) {
    val tabs = remember(stream, state, events, browserState, browserEvents) {
        if (browserState != null) {
            listOf(
                StreamTab(MESSAGES_TAB) { MessageBrowserView(browserState, browserEvents) },
                StreamTab(PUBLISHERS_TAB) { ClientsTab(stream, state, events) },
            )
        } else {
            listOf(StreamTab(PUBLISHERS_TAB) { ClientsTab(stream, state, events) })
        }
    }
    var selected by remember(stream, initialTab, tabs.size) { mutableIntStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }
    Box(Modifier.fillMaxSize()) {
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
            actions = {
                ClientsActions(
                    state = state,
                    nowMillis = nowMillis,
                    events = events,
                    publishState = publishState,
                    onOpenPublishDialog = onOpenPublishDialog,
                    deleteStreamState = deleteStreamState,
                    deleteStreamGate = deleteStreamGate,
                    onOpenDeleteDialog = onOpenDeleteDialog,
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                TabList(tabs, selected) { selected = it }
                Box(Modifier.weight(1f).fillMaxWidth()) { tabs[selected].content() }
            }
        }
        if (showPublishDialog && publishState != null && publishEvents != null) {
            PublishTestMessageDialog(
                state = publishState,
                events = publishEvents,
            )
        }
        if (deleteStreamState != null && deleteStreamState.isOpen) {
            DeleteStreamDialog(
                state = deleteStreamState,
                onConfirmationChange = onDeleteConfirmationChange,
                onConfirm = onConfirmDelete,
                onCancel = onCancelDelete,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.ClientsActions(
    state: StreamClientsState,
    nowMillis: Long,
    events: StreamClientsEvents,
    publishState: PublishMessageState?,
    onOpenPublishDialog: () -> Unit,
    deleteStreamState: DeleteStreamState? = null,
    deleteStreamGate: DeleteStreamGate = DeleteStreamGate.Enabled,
    onOpenDeleteDialog: () -> Unit = {},
) {
    if (publishState != null) {
        val gate = publishState.gate
        val reason = (gate as? PublishGate.Disabled)?.reason
        val button = @Composable {
            FilledTonalButton(
                onClick = onOpenPublishDialog,
                enabled = reason == null,
                modifier = Modifier
                    .testTag("publishTestMessageButton")
                    .semantics {
                        if (reason != null) {
                            contentDescription = reason
                        }
                    },
            ) {
                ConnectionIcon(AppIcon.Send, SmallIconSize)
                Spacer(Modifier.width(8.dp))
                Text("Publish test message")
            }
        }
        if (reason == null) {
            button()
        } else {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(reason) } },
                state = rememberTooltipState(),
            ) { button() }
        }
    }

    if (deleteStreamState != null) {
        val deleteReason = (deleteStreamGate as? DeleteStreamGate.Disabled)?.reason
        val deleteBtn = @Composable {
            FilledTonalButton(
                onClick = onOpenDeleteDialog,
                enabled = deleteReason == null,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .testTag("deleteStreamButton")
                    .semantics {
                        contentDescription = "Delete stream"
                        if (deleteReason != null) {
                            stateDescription = deleteReason
                        }
                    },
            ) {
                ConnectionIcon(AppIcon.Delete, SmallIconSize)
                Spacer(Modifier.width(8.dp))
                Text("Delete stream")
            }
        }
        if (deleteReason == null) {
            deleteBtn()
        } else {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(deleteReason) } },
                state = rememberTooltipState(),
            ) { deleteBtn() }
        }
    }

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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ClientsHorizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
internal fun LastOffset(state: StreamClientsState) {
    if (state.lastOffset.isNotBlank()) {
        Text(
            "${StreamClientsTexts.LAST_OFFSET_LABEL} ${state.lastOffset}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("lastOffset"),
        )
    }
}

/**
 * The tab body: ONE lazy list, so only the rows near the viewport are composed however many clients a stream has. A lazy list cannot
 * sit inside a scrolling page, hence both tables are items of this list. The header is not sticky (it scrolls away with the page, as
 * before). The list state and the two sideways scroll states are hoisted here, so a refresh keeps the position.
 */
@Composable
private fun ClientsSections(state: StreamClientsState, loading: Boolean, events: StreamClientsEvents) {
    val listState = rememberLazyListState()
    val scrolls = rememberClientsScrollStates()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxWidth - ClientsHorizontalPadding * 2
        // One container for the whole list: mouse selection works in the rows, the other items opt out with DisableSelection.
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = ClientsHorizontalPadding, vertical = 16.dp),
            ) {
                state.banner?.let { banner ->
                    item(key = "banner") { Box(Modifier.padding(bottom = 16.dp)) { DisableSelection { LostConnectionBanner(banner, StreamListTexts.RETRY, events::retry, tag = "clientsBanner") } } }
                }
                state.ownConnectionsNotice?.let { notice ->
                    item(key = "notice") {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            DisableSelection {
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ConnectionIcon(AppIcon.Info, SmallIconSize)
                                        Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ownConnectionsNotice"))
                                    }
                                }
                            }
                        }
                    }
                }
                val stale = state.stale && !loading
                publishersSection(if (loading) null else state.publishers, stale, contentWidth, scrolls.publishers)
                consumersSection(if (loading) null else state.consumers, stale, state, contentWidth, scrolls.consumers)
                if (!loading) {
                    item(key = "footnote") {
                        DisableSelection {
                            Text(
                                StreamClientsTexts.FOOTNOTE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("clientsFootnote"),
                            )
                        }
                    }
                }
            }
        }
    }
}
