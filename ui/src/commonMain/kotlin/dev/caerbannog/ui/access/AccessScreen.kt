package dev.caerbannog.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.access.AccessScreen as Screen
import dev.caerbannog.core.access.AccessState
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.VhostRowView
import dev.caerbannog.core.access.VhostsStatus
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.core.streams.UpdatedAge
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.components.CenteredMessage
import dev.caerbannog.ui.components.LostConnectionBanner
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The read-only Access screen, Who am I tab (`docs/ux/Access.dc.html`, frames "Who am I: ..."), drawn inside the app frame. It draws the strings of
 * [state] and decides none. [nowMillis] is the clock for "Updated N s ago" and [updatedAtMillis] the time of the last good read. The screen reports
 * [AccessEvents.setShown] on entering and leaving the composition (D3). Only the Who am I tab exists: the other tabs appear as their slices land.
 * No control here changes anything: [AccessEvents] has no such method.
 *
 * Stable test tags: accessScreen, accessTabWhoAmI, accessReadOnlyNote, accessMessage, accessLockedDetail, accessFailedDetail, accessRetry,
 * accessLoading, accessLoadingRow, accessContent (dimmed while stale), accessUser, accessTokenNote, accessTags, accessTag:<tag>, accessVhostsTable,
 * accessVhostRow:<name>, accessVhostNote:<name>, accessVhostsLoading, accessVhostsEmpty, accessVhostsError, accessVhostsReason, accessVhostsRetry,
 * accessConnectionBanner, accessRefusalBanner, accessNotEncrypted, updatedAge, refreshIcon.
 */
@Composable
fun AccessScreen(
    state: AccessState,
    updatedAtMillis: Long?,
    nowMillis: Long,
    events: AccessEvents,
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
        title = AppDestination.Access.name,
        control = control,
        events = controlEvents,
        onNavigate = onNavigate,
        actions = { AccessActions(state, updatedAtMillis, nowMillis, events) },
    ) {
        AccessBody(state, events)
    }
}

@Composable
private fun RowScope.AccessActions(state: AccessState, updatedAtMillis: Long?, nowMillis: Long, events: AccessEvents) {
    UpdatedAge.text(nowMillis, updatedAtMillis)?.let { age ->
        Text(age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("updatedAge"))
    }
    // Refresh is useful only while data is on screen and no banner offers its own Retry; a double press is harmless (D3).
    val canRefresh = state.screen is Screen.Loaded && state.connectionBanner == null && state.refusalBanner == null && state.readErrorBanner == null
    IconButton(
        onClick = events::refreshNow,
        enabled = canRefresh,
        modifier = Modifier.testTag("refreshIcon").semantics { contentDescription = StreamClientsTexts.REFRESH },
    ) {
        ConnectionIcon(AppIcon.Refresh, IconSize)
    }
}

@Composable
private fun AccessBody(state: AccessState, events: AccessEvents) {
    when (val screen = state.screen) {
        Screen.NotConnected, Screen.Connecting -> Message(state.message)
        Screen.Locked -> Message(state.message, AppIcon.Block) {
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessLockedDetail")) }
        }
        is Screen.Failed -> Message(state.message ?: screen.reason, AppIcon.Error) {
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessFailedDetail")) }
            state.action?.let { RetryButton(it, events, "accessRetry") }
        }
        Screen.Loading, Screen.Loaded -> TabbedBody(state, events)
    }
}

@Composable
private fun Message(text: String?, icon: AppIcon? = null, action: (@Composable () -> Unit)? = null) {
    CenteredMessage(text = text, icon = icon, tag = "accessMessage", action = action)
}

@Composable
private fun RetryButton(label: String, events: AccessEvents, tag: String) {
    Button(onClick = events::retry, modifier = Modifier.testTag(tag).semantics { text = AnnotatedString(label) }) { Text(label) }
}

@Composable
private fun TabbedBody(state: AccessState, events: AccessEvents) {
    Column(
        Modifier.fillMaxSize().testTag("accessScreen").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.connectionBanner?.let { LostConnectionBanner("$it. ${AccessTexts.CONNECTION_LOST_DETAIL}", state.action ?: AccessTexts.RETRY, events::retry, tag = "accessConnectionBanner") }
        state.refusalBanner?.let { LostConnectionBanner("$it ${AccessTexts.CONNECTION_LOST_DETAIL}", state.action ?: AccessTexts.RETRY, events::retry, tag = "accessRefusalBanner") }
        state.readErrorBanner?.let { LostConnectionBanner("$it ${AccessTexts.CONNECTION_LOST_DETAIL}", state.action ?: AccessTexts.RETRY, events::retry, tag = "accessReadErrorBanner") }
        state.notEncrypted?.let { NotEncryptedBanner(it) }
        TabRow()
        state.readOnlyNote?.let { ReadOnlyNote(it) }
        val dim = if (state.stale) 0.6f else 1f
        Column(Modifier.testTag("accessContent").alpha(dim), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.screen is Screen.Loading) LoadingRows(state.message) else WhoAmIContent(state, events)
        }
    }
}

@Composable
private fun TabRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            WHO_AM_I_TAB,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("accessTabWhoAmI").padding(vertical = 8.dp),
        )
    }
    HorizontalDivider()
}

@Composable
private fun NotEncryptedBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("accessNotEncrypted").semantics { this.text = AnnotatedString("$text. ${AccessTexts.NOT_ENCRYPTED_DETAIL}") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(AppIcon.LockOpen, SmallIconSize, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("$text.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(AccessTexts.NOT_ENCRYPTED_DETAIL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun ReadOnlyNote(text: String) {
    Row(Modifier.testTag("accessReadOnlyNote"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionIcon(AppIcon.Visibility, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingRows(message: String?) {
    Column(Modifier.testTag("accessLoading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("accessLoadingRow")) }
        repeat(2) { Text("loading...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessLoadingRow")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoAmIContent(state: AccessState, events: AccessEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(USER_LABEL)
            Text(state.user.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("accessUser"))
            if (state.tokenUser) {
                Text(AccessTexts.TOKEN_USER_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessTokenNote"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(TAGS_LABEL)
            FlowRow(Modifier.testTag("accessTags"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(tag) }, modifier = Modifier.testTag("accessTag:$tag")) }
            }
        }
    }
    Text(VHOSTS_HEADING, style = MaterialTheme.typography.titleSmall)
    when (state.vhostsStatus) {
        VhostsStatus.Listed -> VhostTable(state.vhosts)
        VhostsStatus.Empty -> Text(state.vhostsMessage ?: AccessTexts.NO_VHOSTS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("accessVhostsEmpty"))
        VhostsStatus.Unreadable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.vhostsMessage ?: AccessTexts.VHOSTS_ERROR, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("accessVhostsError"))
            state.vhostsReason?.let { Text(AccessTexts.brokerText(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("accessVhostsReason")) }
            // The banners carry their own Retry; a list error alone has this one.
            if (state.connectionBanner == null && state.refusalBanner == null && state.readErrorBanner == null) {
                state.action?.let { TextButton(onClick = events::retry, modifier = Modifier.testTag("accessVhostsRetry")) { Text(it) } }
            }
        }
        VhostsStatus.Loading, VhostsStatus.None -> Text("loading...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("accessVhostsLoading"))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LABEL_WIDTH))
}

@Composable
private fun VhostTable(rows: List<VhostRowView>) {
    Column(Modifier.fillMaxWidth().testTag("accessVhostsTable")) {
        Row(Modifier.padding(vertical = 8.dp)) {
            Text(VHOST_COLUMN, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(NOTE_COLUMN, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        rows.forEachIndexed { index, row ->
            // Names hidden by the guard all read the same, so they are told apart by position (M10-02b).
            val key = if (row.name == SecretGuard.HIDDEN) "${row.name}:$index" else row.name
            Row(Modifier.padding(vertical = 8.dp).testTag("accessVhostRow:$key")) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(row.note.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("accessVhostNote:$key"))
            }
            HorizontalDivider()
        }
    }
}

// Column and heading words of the Who am I tab, drawn in docs/ux/Access.dc.html; they label fixed fields and carry no state.
private const val WHO_AM_I_TAB = "Who am I"
private const val USER_LABEL = "User"
private const val TAGS_LABEL = "Management tags"
private const val VHOSTS_HEADING = "Virtual hosts the broker lists for this user"
private const val VHOST_COLUMN = "Virtual host"
private const val NOTE_COLUMN = "Note"
private val LABEL_WIDTH = 160.dp
