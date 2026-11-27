package dev.caerbannog.ui.access

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.caerbannog.core.access.MatchAction
import dev.caerbannog.core.access.MatchPreviewState
import dev.caerbannog.core.access.MatchPreviewTexts
import dev.caerbannog.core.access.MatchVerdictView
import dev.caerbannog.core.access.VerdictIcon
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The match preview form under the topic table (M10-04 part B; mockup `docs/ux/Access.dc.html`, frames `perm-admin`, `perm-verdicts`, `layout-narrow-perm`).
 * It draws the strings of [state] and decides none. When [MatchPreviewState.checkEnabled] is false (locked, token user) the fields are dimmed and Check is
 * disabled, and the derived verdict is still drawn at full strength. The fields wrap onto the next line in a narrow window or at a large font scale.
 *
 * Stable test tags: accessMatch, accessMatchDimmed, accessMatchHeading, accessMatchAction:<publish|read|configure>, accessMatchVhost,
 * accessMatchVhostOption:<vhost>, accessMatchNameLabel, accessMatchName, accessMatchRoutingKey, accessMatchCheck, accessMatchVerdict,
 * accessMatchVerdictIcon:<icon>, accessMatchVerdictTitle, accessMatchVerdictSentence, accessMatchSource, accessMatchNote.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchPreviewForm(state: MatchPreviewState, events: MatchPreviewEvents) {
    Column(Modifier.fillMaxWidth().testTag("accessMatch"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(MatchPreviewTexts.HEADING, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("accessMatchHeading"))
        // A zero-size marker: the dimming is an alpha, which a semantics tree cannot show, so tests look for this instead.
        if (!state.checkEnabled) Box(Modifier.size(0.dp).testTag("accessMatchDimmed"))
        Column(Modifier.alpha(if (state.checkEnabled) 1f else DIMMED), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChoice(MatchAction.Publish, "publish", MatchPreviewTexts.ACTION_PUBLISH, state, events)
                ActionChoice(MatchAction.Read, "read", MatchPreviewTexts.ACTION_READ, state, events)
                ActionChoice(MatchAction.Configure, "configure", MatchPreviewTexts.ACTION_CONFIGURE, state, events)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                VhostChoice(state, events)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel(state.nameLabel, "accessMatchNameLabel")
                    OutlinedTextField(state.name, events::setName, singleLine = true, modifier = Modifier.testTag("accessMatchName"))
                }
                if (state.showRoutingKey) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FieldLabel(MatchPreviewTexts.ROUTING_KEY_LABEL, null)
                        OutlinedTextField(state.routingKey, events::setRoutingKey, singleLine = true, modifier = Modifier.testTag("accessMatchRoutingKey"))
                    }
                }
            }
        }
        Button(
            onClick = events::check,
            enabled = state.checkEnabled,
            modifier = Modifier.testTag("accessMatchCheck").semantics { text = AnnotatedString(MatchPreviewTexts.CHECK) },
        ) { Text(MatchPreviewTexts.CHECK) }
        state.verdict?.let { Verdict(it) }
    }
}

@Composable
private fun FieldLabel(label: String, tag: String?) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

@Composable
private fun ActionChoice(action: MatchAction, key: String, label: String, state: MatchPreviewState, events: MatchPreviewEvents) {
    FilterChip(
        selected = state.action == action,
        onClick = { events.chooseAction(action) },
        label = { Text(label) },
        modifier = Modifier.testTag("accessMatchAction:$key").semantics { text = AnnotatedString(label) },
    )
}

@Composable
private fun VhostChoice(state: MatchPreviewState, events: MatchPreviewEvents) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(MatchPreviewTexts.VHOST_LABEL, null)
        Box {
            Row(
                Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                    .testTag("accessMatchVhost").semantics { text = AnnotatedString(state.vhost.orEmpty()) }
                    .clickable(enabled = state.checkEnabled && state.vhosts.isNotEmpty(), role = Role.Button) { open = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.vhost.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                ConnectionIcon(AppIcon.ArrowDropDown, SmallIconSize)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.vhosts.forEach { vhost ->
                    DropdownMenuItem(
                        text = { Text(vhost, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) },
                        onClick = { events.chooseVhost(vhost); open = false },
                        modifier = Modifier.testTag("accessMatchVhostOption:$vhost"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Verdict(verdict: MatchVerdictView) {
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp)).padding(16.dp).testTag("accessMatchVerdict"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.testTag("accessMatchVerdictIcon:${verdict.icon.name}")) { ConnectionIcon(iconOf(verdict.icon), SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(verdict.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("accessMatchVerdictTitle"))
            Text(verdict.sentence, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("accessMatchVerdictSentence"))
            verdict.source?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessMatchSource"))
            }
            Text(verdict.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accessMatchNote"))
        }
    }
}

private fun iconOf(icon: VerdictIcon): AppIcon = when (icon) {
    VerdictIcon.Match -> AppIcon.CheckCircle
    VerdictIcon.NoMatch -> AppIcon.Block
    VerdictIcon.Unreadable -> AppIcon.Help
    VerdictIcon.NotReadable -> AppIcon.Lock
}

/** The opacity the mockup gives a disabled form (`perm-locked`, `perm-token`). */
private const val DIMMED: Float = 0.38f
