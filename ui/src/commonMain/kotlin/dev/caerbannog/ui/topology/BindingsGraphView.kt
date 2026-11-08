package dev.caerbannog.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.GraphNodeKind
import dev.caerbannog.core.topology.NODE_HEIGHT
import dev.caerbannog.core.topology.NODE_WIDTH
import dev.caerbannog.core.topology.PositionedNode
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.components.CenteredMessage
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

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

    val nodes = graph.nodes
    val edges = graph.edges
    val maxX = (nodes.maxOfOrNull { it.x } ?: 0f) + NODE_WIDTH + 48f
    val maxY = (nodes.maxOfOrNull { it.y } ?: 0f) + NODE_HEIGHT + 48f
    val graphWidth = maxOf(640f, maxX).dp
    val graphHeight = maxOf(400f, maxY).dp

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 400.dp, max = 800.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState())
            .testTag("bindingsGraphContainer"),
    ) {
        Box(Modifier.size(graphWidth, graphHeight)) {
            // Edges canvas
            val edgeColor = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidthPx = 1.5.dp.toPx()
                edges.forEach { edge ->
                    val fromX = edge.fromX.dp.toPx()
                    val fromY = edge.fromY.dp.toPx()
                    val toX = edge.toX.dp.toPx()
                    val toY = edge.toY.dp.toPx()
                    val dx = (toX - fromX) / 2f
                    val path = Path().apply {
                        moveTo(fromX, fromY)
                        cubicTo(
                            fromX + dx, fromY,
                            toX - dx, toY,
                            toX, toY,
                        )
                    }
                    drawPath(path, color = edgeColor, style = Stroke(width = strokeWidthPx))
                }
            }

            // Routing key pills
            edges.forEach { edge ->
                if (edge.edge.routingKey.isNotEmpty()) {
                    Box(
                        Modifier
                            .offset(x = edge.labelX.dp, y = edge.labelY.dp)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    placeable.place(-placeable.width / 2, -placeable.height / 2)
                                }
                            }
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("edgePill:${edge.edge.routingKey}"),
                    ) {
                        Text(
                            edge.edge.routingKey,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Nodes
            nodes.forEach { pNode ->
                GraphNodeCard(
                    pNode = pNode,
                    onSelectQueue = onSelectQueue,
                    onOpenStream = onOpenStream,
                )
            }
        }
    }
}

@Composable
private fun GraphNodeCard(
    pNode: PositionedNode,
    onSelectQueue: ((String) -> Unit)? = null,
    onOpenStream: ((String) -> Unit)? = null,
) {
    val node = pNode.node
    val icon = when (node.kind) {
        GraphNodeKind.Exchange -> AppIcon.SwapHoriz
        GraphNodeKind.Stream -> AppIcon.Stream
        GraphNodeKind.Queue -> AppIcon.Inbox
    }
    val clickHandler: (() -> Unit)? = when (node.kind) {
        GraphNodeKind.Stream -> onOpenStream?.let { cb -> { cb(node.name) } }
        GraphNodeKind.Queue -> onSelectQueue?.let { cb -> { cb(node.name) } }
        GraphNodeKind.Exchange -> null
    }
    val borderColor = if (node.kind == GraphNodeKind.Stream) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier
            .offset(x = pNode.x.dp, y = pNode.y.dp)
            .width(NODE_WIDTH.dp)
            .height(NODE_HEIGHT.dp)
            .testTag("graphNode:${node.name}")
            .then(
                if (clickHandler != null) {
                    Modifier.clickable(role = Role.Button, onClick = clickHandler)
                } else {
                    Modifier
                }
            )
            .focusable()
            .semantics {
                contentDescription = node.accessibilityDescription
                role = Role.Button
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (node.kind == GraphNodeKind.Stream) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionIcon(icon, SmallIconSize, tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.detail.isNotEmpty()) {
                    Text(
                        node.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
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
