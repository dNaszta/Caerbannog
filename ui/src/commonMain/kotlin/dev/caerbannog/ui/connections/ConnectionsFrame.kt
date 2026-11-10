package dev.caerbannog.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

// The content area of docs/ux/Main.dc.html (the drawer and the app bar are the AppFrame's, M2-05b).

/** Mockup: the profile list is 340 wide next to the panel (min 480); when both do not fit, they stack. */
@Composable
internal fun ConnectionsContent(
    state: ConnectionsState,
    onSelect: (String) -> Unit,
    editPanel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    /** The profile whose connection is up now, or null; the 3.x pill of a card shows only for it (F8). */
    connectedName: String? = null,
) {
    BoxWithConstraints(modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        if (maxWidth >= 340.dp + 24.dp + 480.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SavedProfilesList(state, onSelect, connectedName, Modifier.width(340.dp))
                editPanel(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SavedProfilesList(state, onSelect, connectedName, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                editPanel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
