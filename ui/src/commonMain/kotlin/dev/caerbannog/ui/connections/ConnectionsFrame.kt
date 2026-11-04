package dev.caerbannog.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.permissionPill

// The frame of docs/ux/Main.dc.html: drawer, app bar, content. Text only; icons come with M1-12b.

/**
 * Left drawer. M1 has no session yet, so the Connection card shows the profile open in the edit panel.
 * Search and the Streams, Topology and Migrations items are not built: their screens come with M2 to M4.
 */
@Composable
internal fun ConnectionsDrawer(state: ConnectionsState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("connectionsDrawer").background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 16.dp)) {
            Text("Caerbannog", style = MaterialTheme.typography.titleLarge)
            Text("behind the rabbit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DrawerConnectionCard(state)
        // The only nav item in M1, shown as the active one.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(modifier = Modifier.padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Connections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 18.dp))
            }
        }
    }
}

@Composable
private fun DrawerConnectionCard(state: ConnectionsState) {
    val selected = state.selectedProfile
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("drawerConnectionCard"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected == null) {
                Text("No connection selected", style = MaterialTheme.typography.titleSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        selected.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PermissionPillView(permissionPill(state.permissionChecks[selected.name]), Modifier.testTag("drawerPermissionPill"))
                }
                Text(selected.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ConnectionsAppBar(onNewConnection: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag("connectionsAppBar").fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Button(onClick = onNewConnection) { Text("New connection") }
    }
}

/** Mockup: the profile list is 340 wide next to the panel (min 480); when both do not fit, they stack. */
@Composable
internal fun ConnectionsContent(
    state: ConnectionsState,
    onSelect: (String) -> Unit,
    editPanel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        if (maxWidth >= 340.dp + 24.dp + 480.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SavedProfilesList(state, onSelect, Modifier.width(340.dp))
                editPanel(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SavedProfilesList(state, onSelect, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                editPanel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
