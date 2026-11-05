package dev.caerbannog.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.core.streams.UpdatedAge
import dev.caerbannog.core.topology.TopologyScreen as Screen
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.streams.LostConnectionBanner

/**
 * The read-only Topology screen (`docs/ux/Topology.dc.html`), drawn inside the app frame. It draws the strings of [state] and decides
 * none (message, range text, note, cells, banners), like `StreamsScreen`. [nowMillis] is the clock for "Updated N s ago"; the caller
 * ticks it once a second. The screen reports [TopologyEvents.setShown] on entering and leaving the composition (D3). There is no
 * control that changes topology anywhere (T4): [TopologyEvents] has no such method, so none can be drawn.
 */
@Composable
fun TopologyScreen(
    state: TopologyState,
    nowMillis: Long,
    events: TopologyEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
) {
    val currentEvents by rememberUpdatedState(events)
    DisposableEffect(Unit) {
        currentEvents.setShown(true)
        onDispose { currentEvents.setShown(false) }
    }
    AppFrame(
        title = AppDestination.Topology.name,
        control = control,
        events = controlEvents,
        onNavigate = onNavigate,
        actions = { TopologyActions(state, nowMillis, events) },
    ) {
        TopologyBody(state, events)
    }
}

@Composable
private fun RowScope.TopologyActions(state: TopologyState, nowMillis: Long, events: TopologyEvents) {
    UpdatedAge.text(nowMillis, state.updatedAtMillis)?.let { age ->
        Text(
            age,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("updatedAge"),
        )
    }
    // Refresh is useful only while rows or an unavailable virtual host are on screen and no lost-connection banner shows (a load or a lost
    // link has its own way: wait, or Retry). A double press is harmless (the latest started wins, D3).
    val canRefresh = (state.screen is Screen.Ready || state.screen is Screen.Empty || state.screen is Screen.VhostUnavailable) &&
        state.connectionBanner == null
    IconButton(
        onClick = events::refreshNow,
        enabled = canRefresh,
        modifier = Modifier.testTag("refreshIcon").semantics { contentDescription = StreamClientsTexts.REFRESH },
    ) {
        ConnectionIcon(AppIcon.Refresh, IconSize)
    }
}

@Composable
private fun TopologyBody(state: TopologyState, events: TopologyEvents) {
    when (val screen = state.screen) {
        Screen.NotConnected, Screen.Connecting -> TopologyMessage(state.message)
        Screen.CannotRead -> TopologyMessage(state.message, AppIcon.Block)
        is Screen.Failed -> TopologyMessage(state.message ?: screen.reason, AppIcon.Error) {
            val label = state.action ?: TopologyTexts.RETRY
            Button(onClick = events::retry, modifier = Modifier.testTag("retryButton").semantics { text = AnnotatedString(label) }) { Text(label) }
        }
        Screen.Loading, Screen.Ready, Screen.Empty, is Screen.VhostUnavailable -> TabbedBody(state, events)
    }
}

/** The screens that have the tab row: banners, tabs, the controls of the current tab, then the table or a message. */
@Composable
private fun TabbedBody(state: TopologyState, events: TopologyEvents) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.connectionBanner?.let { LostConnectionBanner(it, state.action ?: TopologyTexts.RETRY, events::retry, tag = "topologyConnectionBanner") }
        // Only the first reason is drawn (owner decision O1); the list may hold more, and a loop here would stack banners.
        state.banners.firstOrNull()?.let { TopologyReasonBanner(it) }
        TopologyTabs(state.tab, events::setTab)
        when (val screen = state.screen) {
            is Screen.VhostUnavailable -> {
                TopologyControls(state, events, searchAndFilters = false)
                TopologyMessage(state.message ?: TopologyTexts.vhostUnavailable(screen.vhost), AppIcon.Block, Modifier.weight(1f))
            }
            Screen.Empty -> {
                TopologyControls(state, events, searchAndFilters = true)
                TopologyMessage(state.message, AppIcon.Inbox, Modifier.weight(1f))
            }
            Screen.Loading -> {
                TopologyControls(state, events, searchAndFilters = true)
                Box(Modifier.weight(1f)) { TopologyTableFor(state, events, loading = true) }
            }
            else -> {
                TopologyControls(state, events, searchAndFilters = true)
                Box(Modifier.weight(1f)) { TopologyTableFor(state, events, loading = false) }
                TopologyFooter(state, events)
            }
        }
    }
}

/** A centred message with an optional action under it. The text node carries the tag, so a test reads the message itself. */
@Composable
private fun TopologyMessage(
    text: String?,
    icon: AppIcon? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            icon?.let { ConnectionIcon(it, IconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.testTag("topologyMessage"),
                )
            }
            action?.invoke()
        }
    }
}
