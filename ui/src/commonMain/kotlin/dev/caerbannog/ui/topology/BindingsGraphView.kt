package dev.caerbannog.ui.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.components.CenteredMessage
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize

/**
 * Bindings graph view: renders the exchange-to-queue and exchange-to-exchange topology as a directed
 * graph layered from left to right. When the neighbourhood exceeds [MAX_GRAPH_NODES], an overflow
 * notice is displayed asking to narrow the search or focus on an exchange.
 */
@Composable
internal fun BindingsGraphView(
    state: TopologyState,
    loading: Boolean,
    onSelectQueue: ((String) -> Unit)? = null,
    onOpenStream: ((String) -> Unit)? = null,
) {
    val overflowMsg = state.graphTooLargeMessage
    if (overflowMsg != null) {
        GraphTooLargeNotice(overflowMsg)
        return
    }

    if (loading) {
        Box(Modifier.fillMaxWidth().height(360.dp))
        return
    }

    val graph = state.graph
    if (graph == null || graph.nodes.isEmpty()) {
        CenteredMessage(
            text = TopologyTexts.NO_BINDINGS,
            icon = AppIcon.Inbox,
            tag = "bindingsGraphEmpty",
        )
        return
    }

    BindingGraphCanvas(
        graph = graph,
        onSelectQueue = onSelectQueue,
        onOpenStream = onOpenStream,
    )
}

@Composable
private fun GraphTooLargeNotice(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(24.dp)
            .testTag("bindingsGraphTooLarge"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionIcon(AppIcon.Warning, IconSize, tint = MaterialTheme.colorScheme.error)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("graphTooLargeMessage"),
            )
        }
    }
}
