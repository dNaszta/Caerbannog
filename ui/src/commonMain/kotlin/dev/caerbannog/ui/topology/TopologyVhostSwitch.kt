package dev.caerbannog.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

// Below the switch, left edges aligned, kept inside the window.
private object BelowAnchorStart : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, anchorBounds.bottom)
    }
}

/**
 * The virtual host switch (T1: every virtual host the user may see, one at a time). A button that opens a menu in a popup; the keys are ours
 * (Down and Up move, Enter or Space choose, Escape closes and gives the focus back), as for the connection popup. The menu closes when the
 * state changes the virtual host. Choosing the current one only closes the menu.
 */
@Composable
internal fun VhostSwitch(current: String, vhosts: List<String>, homeVhost: String? = null, onSelect: (String) -> Unit) {
    var open by remember(current) { mutableStateOf(false) }
    val switchFocus = remember { FocusRequester() }
    val label = "Virtual host $current"
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .testTag("vhostSwitch")
                .semantics { text = AnnotatedString(label); contentDescription = "$label, change" }
                .focusRequester(switchFocus)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionIcon(AppIcon.Dns, SmallIconSize)
            Text("Virtual host ", style = MaterialTheme.typography.labelLarge)
            Text(current, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            ConnectionIcon(AppIcon.ArrowDropDown, IconSize)
        }
        if (open) {
            val close = { open = false; switchFocus.requestFocus() }
            Popup(
                popupPositionProvider = BelowAnchorStart,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = false),
            ) {
                VhostMenu(
                    current = current,
                    vhosts = vhosts,
                    homeVhost = homeVhost,
                    choose = { name -> if (name != current) onSelect(name); close() },
                    dismiss = { close() },
                )
            }
        }
    }
}

@Composable
private fun VhostMenu(
    current: String,
    vhosts: List<String>,
    homeVhost: String?,
    choose: (String) -> Unit,
    dismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val entry = remember { FocusRequester() }
    // Focus moves to the selected item on open, so Enter keeps the choice and Down walks from there.
    val entryName = if (current in vhosts) current else vhosts.firstOrNull()
    LaunchedEffect(Unit) { if (entryName != null) entry.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) {
        Column(
            Modifier
                .testTag("vhostMenu")
                .widthIn(min = 240.dp, max = 400.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                        Key.Escape -> { dismiss(); true }
                        else -> false
                    }
                }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(
                "Virtual hosts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            vhosts.forEach { name ->
                val on = name == current
                val isHome = name == homeVhost
                val desc = if (isHome) "$name, ${TopologyTexts.HOME_VHOST_CAPTION}" else name
                val itemText = if (isHome) "$name ${TopologyTexts.HOME_VHOST_CAPTION}" else name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(if (name == entryName) Modifier.focusRequester(entry) else Modifier)
                        .testTag("vhostItem:$name")
                        .semantics {
                            selected = on
                            text = AnnotatedString(itemText)
                            contentDescription = desc
                        }
                        .clickable(role = Role.Button) { choose(name) }
                        .padding(horizontal = 16.dp, vertical = if (isHome) 6.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.widthIn(min = 18.dp)) { if (on) ConnectionIcon(AppIcon.Check, SmallIconSize) }
                    if (isHome) {
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                            Text(
                                TopologyTexts.HOME_VHOST_CAPTION,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}
