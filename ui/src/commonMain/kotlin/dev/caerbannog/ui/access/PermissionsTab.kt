package dev.caerbannog.ui.access

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.access.DeclarationView
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.PermissionsScreen
import dev.caerbannog.core.access.PermissionsState
import dev.caerbannog.core.access.PermissionsTexts
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserOption
import dev.caerbannog.ui.components.SearchField
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/** True when the screen shows something read from the broker (a table, the empty frame or the token text). */
internal val PermissionsScreen.hasData: Boolean
    get() = this is PermissionsScreen.Loaded || this is PermissionsScreen.Empty || this is PermissionsScreen.TokenUser

/**
 * The Permissions tab (`docs/ux/Access.dc.html`, frames `perm-*`): it draws the strings of [state] and decides none. The preview block of the mockup is M10-04.
 * No control here changes anything: [PermissionsEvents] has no such method.
 *
 * Stable test tags: accessPermissions, accessPermissionsMessage, accessPermissionsDetail, accessPermissionsLoading, accessPermissionsHeading,
 * accessPermissionsReadLabel, accessPermissionsDeclared, accessPermissionsRetry, accessUserChooser, accessUserCurrent, accessUserFilter, accessUserCount,
 * accessUserOption:<key>, accessVhostFilter, accessPermissionsTable, accessPermissionRow:<vhost>, accessCell:<vhost>:<kind>, accessTopicTable,
 * accessTopicRow:<vhost>:<exchange>, accessTopicCell:<vhost>:<exchange>:<kind>, plus the banner tags of the Who am I tab.
 */
@Composable
internal fun PermissionsPage(state: PermissionsState, events: PermissionsEvents, tab: AccessTab, onTab: (AccessTab) -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag("accessScreen").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccessBanners(state.connectionBanner, state.refusalBanner, state.readErrorBanner, state.notEncrypted, state.action, events::retry)
        TabRow(tab, onTab)
        Column(Modifier.testTag("accessContent").alpha(if (state.stale) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.testTag("accessPermissions"), verticalArrangement = Arrangement.spacedBy(16.dp)) { PermissionsBody(state, events) }
        }
    }
}

@Composable
private fun PermissionsBody(state: PermissionsState, events: PermissionsEvents) {
    if (state.canChooseUser) UserChooser(state, events)
    when (state.screen) {
        PermissionsScreen.NotConnected, PermissionsScreen.Connecting -> MessageText(state.message)
        PermissionsScreen.Loading -> Column(Modifier.testTag("accessPermissionsLoading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            repeat(2) { Text("loading...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        PermissionsScreen.Locked -> {
            Notice(AppIcon.Lock, state)
            state.declaration?.let { Declaration(it) }
        }
        PermissionsScreen.TokenUser, PermissionsScreen.Empty -> Notice(AppIcon.Info, state)
        is PermissionsScreen.Failed -> {
            Notice(AppIcon.Error, state)
            state.action?.let { label ->
                Button(onClick = events::retry, modifier = Modifier.testTag("accessPermissionsRetry").semantics { text = AnnotatedString(label) }) { Text(label) }
            }
        }
        PermissionsScreen.Loaded -> Tables(state, events)
    }
}

@Composable
private fun MessageText(message: String?) {
    message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("accessPermissionsMessage")) }
}

@Composable
private fun Notice(icon: AppIcon, state: PermissionsState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionIcon(icon, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("accessPermissionsMessage")) }
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessPermissionsDetail")) }
        }
    }
}

/** The profile's own claim. Its label is warning coloured and its words differ from "Read from the broker", so it is never taken for a broker answer. */
@Composable
private fun Declaration(declaration: DeclarationView) {
    val spoken = listOf(
        PermissionsTexts.DECLARED_HEADING, declaration.label, "${PermissionsTexts.CONFIGURE_COLUMN} ${declaration.configure}", "${PermissionsTexts.WRITE_COLUMN} ${declaration.write}",
        "${PermissionsTexts.READ_COLUMN} ${declaration.read}", declaration.note,
    ).joinToString(". ")
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp)).padding(16.dp)
            .testTag("accessPermissionsDeclared").semantics { text = AnnotatedString(spoken) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PermissionsTexts.DECLARED_HEADING, style = MaterialTheme.typography.titleSmall)
            Pill(declaration.label, AppIcon.Edit, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, null)
        }
        KeyValue(PermissionsTexts.CONFIGURE_COLUMN, declaration.configure)
        KeyValue(PermissionsTexts.WRITE_COLUMN, declaration.write)
        KeyValue(PermissionsTexts.READ_COLUMN, declaration.read)
        Text(declaration.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Pill(label: String, icon: AppIcon, container: Color, content: Color, tag: String?) {
    Surface(color = container, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionIcon(icon, SmallIconSize, tint = content)
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        }
    }
}

@Composable
private fun UserChooser(state: PermissionsState, events: PermissionsEvents) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp)).testTag("accessUserChooser")
                .clickable(role = Role.Button) { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionIcon(AppIcon.Person, SmallIconSize)
            Text(PermissionsTexts.USER_LABEL, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.chosenUser.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("accessUserCurrent"))
            ConnectionIcon(AppIcon.ArrowDropDown, SmallIconSize)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(state.userFilter, PermissionsTexts.USER_FILTER_HINT, "accessUserFilter", events::setUserFilter, minWidth = 240.dp, maxWidth = 320.dp)
                state.userCountText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessUserCount")) }
            }
            state.users.forEach { option -> UserItem(option) { events.chooseUser(option.key); open = false } }
        }
    }
}

// A hidden name is disabled: the real name was dropped before it got here and cannot be requested.
@Composable
private fun UserItem(option: UserOption, onChoose: () -> Unit) {
    DropdownMenuItem(
        text = { Text(option.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) },
        onClick = onChoose,
        enabled = !option.hidden,
        modifier = Modifier.testTag("accessUserOption:${option.key}"),
    )
}

@Composable
private fun Tables(state: PermissionsState, events: PermissionsEvents) {
    SearchField(state.vhostFilter, PermissionsTexts.VHOST_FILTER_HINT, "accessVhostFilter", events::setVhostFilter)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        state.heading?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("accessPermissionsHeading")) }
        state.readLabel?.let { Pill(it, AppIcon.Dns, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "accessPermissionsReadLabel") }
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PatternTable(state.rows)
            if (state.topicRows.isNotEmpty()) TopicTable(state.topicRows)
        }
    }
    Text(PermissionsTexts.PATTERN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PatternTable(rows: List<PermissionRow>) {
    Column(Modifier.fillMaxWidth().testTag("accessPermissionsTable")) {
        HeaderRow(PermissionsTexts.VHOST_COLUMN, PermissionsTexts.CONFIGURE_COLUMN, PermissionsTexts.WRITE_COLUMN, PermissionsTexts.READ_COLUMN)
        rows.forEachIndexed { index, row ->
            val key = vhostKey(row.vhost, index)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("accessPermissionRow:$key"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.vhost, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                PatternText(row.configure, "accessCell:$key:configure")
                PatternText(row.write, "accessCell:$key:write")
                PatternText(row.read, "accessCell:$key:read")
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TopicTable(rows: List<TopicPermissionRow>) {
    Column(Modifier.fillMaxWidth().testTag("accessTopicTable")) {
        Text(PermissionsTexts.TOPIC_HEADING, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        HeaderRow(PermissionsTexts.VHOST_COLUMN, PermissionsTexts.EXCHANGE_COLUMN, PermissionsTexts.WRITE_COLUMN, PermissionsTexts.READ_COLUMN)
        rows.forEachIndexed { index, row ->
            val key = "${vhostKey(row.vhost, index)}:${row.exchange}"
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("accessTopicRow:$key"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.vhost, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(row.exchange, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                PatternText(row.write, "accessTopicCell:$key:write")
                PatternText(row.read, "accessTopicCell:$key:read")
            }
            HorizontalDivider()
        }
    }
}

// Names the guard hid all read the same, so they are told apart by position (M10-02b); every other name is its own key.
private fun vhostKey(vhost: String, index: Int): String = if (vhost == SecretGuard.HIDDEN) "$vhost:$index" else vhost

@Composable
private fun HeaderRow(vararg labels: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)) }
    }
    HorizontalDivider()
}

/** The text exactly as the model says it (what is shown is what is copied): the pattern, the hidden marker or "no permission". */
@Composable
private fun RowScope.PatternText(cell: PatternCell, tag: String) {
    val none = cell.reading is PatternReading.NoPermission && !cell.hidden
    Text(
        PermissionsTexts.shown(cell),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (none) FontFamily.Default else FontFamily.Monospace,
        color = if (none) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).testTag(tag),
    )
}
