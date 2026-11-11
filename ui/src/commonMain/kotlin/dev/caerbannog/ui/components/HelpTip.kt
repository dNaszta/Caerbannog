package dev.caerbannog.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.caerbannog.ui.appframe.ReasonTooltip
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * A small info icon next to a term (M8-14): [text] is the hover tooltip, the icon's accessible description, and, after a click or Enter on the focused
 * icon, a visible line under it (so a keyboard or touch user is not hover-bound). A second click hides the line. Tags: `help-tip-<id>` (the icon) and
 * `help-tip-<id>-line` (the visible line). It takes plain strings: the texts live in core.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HelpTip(id: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        TooltipArea(tooltip = { ReasonTooltip(text) }) {
            Box(
                Modifier.size(28.dp).testTag("help-tip-$id")
                    .semantics { contentDescription = text }
                    .clickable(role = Role.Button) { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 420.dp).testTag("help-tip-$id-line"))
        }
    }
}
