package dev.caerbannog.ui.appframe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionControlTexts
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.NotEncryptedNote
import dev.caerbannog.ui.connections.PermissionPillView
import dev.caerbannog.ui.connections.SmallIconSize

// M2-05b: the app frame and the connection control of docs/ux/Streams.dc.html (closed chip in the app bar, popup under it).

private val OkGreen = Color(0xFF386A20)

private fun ChipIcon.appIcon(): AppIcon = when (this) {
    ChipIcon.Link -> AppIcon.Link
    ChipIcon.LinkOff -> AppIcon.LinkOff
    ChipIcon.Sync -> AppIcon.Sync
    ChipIcon.Error -> AppIcon.Error
}

@Composable
private fun ChipIcon.tint(): Color = when (this) {
    ChipIcon.Link -> OkGreen
    ChipIcon.Error -> MaterialTheme.colorScheme.error
    ChipIcon.Sync, ChipIcon.LinkOff -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The closed chip: state icon, name (ellipsis when long, never squeezed below 96 dp: the pill and the Not encrypted note give way to nothing), optional pill, drop-down arrow. A button, a normal tab stop. */
@Composable
fun ConnectionChip(chip: ConnectionChipState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val border = if (chip.errorBorder) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .testTag("connectionChip")
            .widthIn(max = 520.dp)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(8.dp))
            // clickable already opens on Enter, NumPadEnter and Space, so no key handler of our own.
            .clickable(role = Role.Button, onClick = onClick)
            // The label is the one spoken description: state and permission as words, not colour (icons are decorative).
            .semantics { contentDescription = chip.accessibleLabel }
            .padding(start = 12.dp, end = 4.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionIcon(chip.icon.appIcon(), SmallIconSize, tint = chip.icon.tint())
        Text(chip.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp))
        chip.stateText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        chip.pill?.let { PermissionPillView(it, Modifier.testTag("chipPill")) }
        if (chip.notEncrypted) NotEncryptedNote("chipNotEncrypted", MaterialTheme.typography.labelMedium)
        ConnectionIcon(AppIcon.ArrowDropDown, IconSize)
    }
}

/** The popup content: Connections section, Stream list refresh section, Manage connections. [onDismiss] on Escape. */
@Composable
fun ConnectionPopup(state: ConnectionControlState, events: ConnectionControlEvents, onDismiss: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val entry = remember { FocusRequester() }
    // Focus moves into the popup on open, to the first thing that can take it: an action, else a refresh choice, else Refresh now,
    // else Manage connections. (A focus request on the group itself did not move focus out of the chip.)
    val firstAction = state.rows.firstOrNull { it.action != RowAction.None }?.name
    val firstEnabled = state.refresh.enabled
    LaunchedEffect(Unit) { entry.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.width(500.dp),
    ) {
        Column(
            modifier = Modifier
                .testTag("connectionPopup")
                .semantics { contentDescription = "Connection" }
                // Up and Down walk the focus order (actions, refresh choices, Refresh now, Manage connections); Escape closes.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SectionLabel(ConnectionControlTexts.SECTION_CONNECTIONS)
            val emptyText = state.emptyText
            if (emptyText != null) {
                EmptyConnections(emptyText, events)
            } else {
                state.rows.forEach { ConnectionRow(it, events, if (it.name == firstAction) Modifier.focusRequester(entry) else Modifier) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(ConnectionControlTexts.SECTION_REFRESH)
            RefreshSection(state.refresh, events, if (firstAction == null && firstEnabled) Modifier.focusRequester(entry) else Modifier)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(
                onClick = events::openConnections,
                modifier = (if (firstAction == null && !firstEnabled) Modifier.focusRequester(entry) else Modifier).padding(horizontal = 8.dp).testTag("manageConnections"),
            ) {
                Text(ConnectionControlTexts.MANAGE_CONNECTIONS)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyConnections(text: String, events: ConnectionControlEvents) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = events::openConnections, modifier = Modifier.testTag("newConnectionAction")) {
            Text(ConnectionControlTexts.NEW_CONNECTION)
        }
    }
}

@Composable
private fun ConnectionRow(row: ConnectionRowState, events: ConnectionControlEvents, actionModifier: Modifier) {
    val failed = row.icon == ChipIcon.Error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("popupRow:${row.name}")
            .background(if (row.current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .semantics(mergeDescendants = true) { selected = row.current }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(row.icon.appIcon(), SmallIconSize, tint = row.icon.tint())
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.stateText,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("popupNote:${row.name}"))
            }
            if (row.notEncrypted) NotEncryptedNote("popupNotEncrypted:${row.name}", MaterialTheme.typography.labelSmall)
        }
        val action: Pair<String, () -> Unit>? = when (row.action) {
            RowAction.Connect -> ConnectionControlTexts.CONNECT to { events.connect(row.name) }
            RowAction.Disconnect -> ConnectionControlTexts.DISCONNECT to { events.disconnect(row.name) }
            RowAction.Retry -> ConnectionControlTexts.RETRY to { events.retry(row.name) }
            RowAction.None -> null
        }
        // The popup stays open after an action, so the new state is visible (brief, Keyboard and access).
        action?.let { (label, run) -> TextButton(onClick = run, modifier = actionModifier.testTag("popupAction:${row.name}")) { Text(label) } }
    }
}

@Composable
private fun RefreshSection(refresh: RefreshSectionState, events: ConnectionControlEvents, firstChoiceModifier: Modifier) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ConnectionControlTexts.REFRESH_EVERY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
            )
            RefreshInterval.entries.forEach { interval ->
                val label = interval.label()
                FilterChip(
                    selected = refresh.selected == interval,
                    onClick = { events.setRefreshInterval(interval) },
                    enabled = refresh.enabled,
                    label = { Text(label, maxLines = 1, softWrap = false) },
                    leadingIcon = if (refresh.selected == interval) {
                        { ConnectionIcon(AppIcon.Check, SmallIconSize) }
                    } else null,
                    modifier = (if (interval == RefreshInterval.entries.first()) firstChoiceModifier else Modifier).testTag("refreshChoice:$label"),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                refresh.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("refreshNote"),
            )
            TextButton(onClick = events::refreshNow, enabled = refresh.enabled, modifier = Modifier.testTag("refreshNow")) {
                ConnectionIcon(AppIcon.Refresh, SmallIconSize)
                Spacer(Modifier.width(4.dp))
                Text(ConnectionControlTexts.REFRESH_NOW)
            }
        }
    }
}

private fun RefreshInterval.label(): String = if (this == RefreshInterval.Off) "Off" else "$seconds s"

// Below the chip, right edges aligned, kept inside the window.
private object BelowAnchorEnd : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, anchorBounds.bottom)
    }
}

/**
 * The chip with its popup and the open/closed state (Enter, Space or a click opens; Escape closes and returns focus to the chip).
 * Why a `Popup` and not `DropdownMenu`: the popup holds chips and a text row, which a menu does not, and the keys are ours.
 */
@Composable
fun ConnectionControl(state: ConnectionControlState, events: ConnectionControlEvents, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }
    Box(modifier) {
        ConnectionChip(
            chip = state.chip,
            onClick = { open = !open },
            modifier = Modifier.focusRequester(chipFocus),
        )
        if (open) {
            val close = { open = false; chipFocus.requestFocus() }
            Popup(
                popupPositionProvider = BelowAnchorEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = false),
            ) {
                ConnectionPopup(state, events, onDismiss = { close() })
            }
        }
    }
}

/**
 * The frame of every screen, including Connections: drawer (brand, search, the four navigation items) and app bar (title, the
 * screen's own [actions], then the fixed [ConnectionControl] last), with the screen's [content] below the bar.
 * The search field is drawn, not wired (M2-08 and later); the items report [onNavigate].
 */
@Composable
fun AppFrame(
    title: String,
    control: ConnectionControlState,
    events: ConnectionControlEvents,
    modifier: Modifier = Modifier,
    /** The drawer items report the destination the user chose; the app decides what to show (Topology and Migrations have no screen yet). */
    onNavigate: (AppDestination) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /** The drawer item drawn as selected, by [AppDestination] name. Defaults to [title]; a detail screen (the stream screen) passes its parent. */
    activeItem: String = title,
    /** Shown before the title (the back arrow of a detail screen). */
    leading: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    AppFrameLayout(title, modifier, onNavigate, actions, chip = { ConnectionControl(control, events) }, activeItem = activeItem, leading = leading, content = content)
}

/** The frame with the chip slot open: [chip] null draws no chip (a caller that has no connection control yet). */
@Composable
internal fun AppFrameLayout(
    title: String,
    modifier: Modifier = Modifier,
    onNavigate: (AppDestination) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    chip: (@Composable () -> Unit)?,
    activeItem: String = title,
    leading: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            AppDrawer(activeItem = activeItem, onNavigate = onNavigate, modifier = Modifier.width(280.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                AppBar(title, leading, actions, chip)
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun AppBar(title: String, leading: @Composable RowScope.() -> Unit, actions: @Composable RowScope.() -> Unit, chip: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier.testTag("appBar").fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading()
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
        chip?.invoke()
    }
}

/** The drawer items. The app decides what to do with each. [label] is the text on the drawer item and in the app bar. */
enum class AppDestination(val label: String) {
    Connections("Connections"),
    Streams("Streams"),
    Topology("Topology"),
    Migrations("Migrations"),

    /** M8-06a: the file source and report of the migration from RabbitMQ 3.x; the name is used for tags and selection. */
    MigrateFromThree("Migrate from 3.x"),
}

private val navItems: List<Pair<AppDestination, AppIcon>> = listOf(
    AppDestination.Connections to AppIcon.Power,
    AppDestination.Streams to AppIcon.Stream,
    AppDestination.Topology to AppIcon.Hub,
    AppDestination.Migrations to AppIcon.AccountTree,
    AppDestination.MigrateFromThree to AppIcon.DriveFileMove,
)

@Composable
private fun AppDrawer(activeItem: String, onNavigate: (AppDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("appDrawer").background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(AppIcon.CrueltyFree, IconSize, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("Caerbannog", style = MaterialTheme.typography.titleLarge)
                Text("behind the rabbit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(bottom = 0.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Search, SmallIconSize)
                Text("Search streams, queues, exchanges", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.padding(4.dp))
        navItems.forEach { (destination, icon) -> NavItem(destination, icon, selected = destination.name == activeItem, onClick = { onNavigate(destination) }) }
    }
}

@Composable
private fun NavItem(destination: AppDestination, icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(28.dp),
        // The whole item is the target: focusable, Enter and Space activate it, `selected` is spoken.
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("navItem:${destination.name}")
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(icon, IconSize)
            Text(destination.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
