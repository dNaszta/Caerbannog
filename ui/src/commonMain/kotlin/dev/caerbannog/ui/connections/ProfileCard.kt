package dev.caerbannog.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.cardPill

@Composable
internal fun SavedProfilesList(
    state: ConnectionsState,
    onSelect: (String) -> Unit,
    connectedName: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Saved profiles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))
        if (state.profiles.isEmpty()) {
            Text("No saved connections yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(state.profiles, key = { it.name }) { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = profile.name == state.selectedProfileName,
                        status = state.permissionChecks[profile.name],
                        connected = profile.name == connectedName,
                        onClick = { onSelect(profile.name) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ConnectionProfile, selected: Boolean, status: PermissionCheckStatus?, connected: Boolean, onClick: () -> Unit) {
    // The whole card is the click target (as in the mockup) and keyboard focusable; the pill is a child of it.
    // Mockup .pcard: surface + 1dp outline-variant border; selected: secondary-container, no border.
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("profileCard:${profile.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The pill is measured first at its full width; the name takes what is left and ellipsizes.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                cardPill(status, connected)?.let { PermissionPillView(it, Modifier.testTag("permissionPill:${profile.name}")) }
            }
            Text(profile.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // No "Connected" status in this task's scope: there is no notion yet of an active broker
            // session (that is M2's stream-list territory) - only whether this profile is the one
            // currently open in the edit panel.
            Text(
                if (selected) "Editing" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tone to colour in one place. OK green is not in the M3 scheme, so it uses the mockup's `ok-container` values. */
internal object PermissionColors {
    private val okContainer = Color(0xFFD8EBD0)
    private val onOkContainer = Color(0xFF173A0F)
    // Amber, from the mockup's --warn-container: a declaration is a claim, not a verified fact and not an error.
    private val warnContainer = Color(0xFFFFE2B0)
    private val onWarnContainer = Color(0xFF3B2700)

    @Composable
    fun container(tone: PillTone): Color = when (tone) {
        PillTone.OK -> okContainer
        PillTone.WARNING -> warnContainer
        PillTone.LOCKED -> MaterialTheme.colorScheme.errorContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    @Composable
    fun content(tone: PillTone): Color = when (tone) {
        PillTone.OK -> onOkContainer
        PillTone.WARNING -> onWarnContainer
        PillTone.LOCKED -> MaterialTheme.colorScheme.onErrorContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun PermissionPillView(pill: PermissionPill, modifier: Modifier = Modifier) {
    val (text, tone) = pill
    Surface(color = PermissionColors.container(tone), contentColor = PermissionColors.content(tone), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tone.icon()?.let { ConnectionIcon(it, SmallIconSize) }
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

