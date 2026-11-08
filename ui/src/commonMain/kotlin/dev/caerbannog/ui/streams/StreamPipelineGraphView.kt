package dev.caerbannog.ui.streams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.PIPELINE_NODE_HEIGHT
import dev.caerbannog.core.streams.PIPELINE_NODE_WIDTH
import dev.caerbannog.core.streams.PipelineNode
import dev.caerbannog.core.streams.PipelineNodeHealth
import dev.caerbannog.core.streams.PipelineNodeKind
import dev.caerbannog.core.streams.StreamPipelineGraph
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

private val HealthyGreen = Color(0xFF386A20)
private val LaggingAmber = Color(0xFFB36B00)

/**
 * Interactive 3-layer End-to-End Live Data Pipeline Graph for a stream:
 * Ingress (MQTT `amq.topic` routes, Exchange bindings, Stream Publishers) -> Stream Hub -> Stream Consumers.
 */
@Composable
fun StreamPipelineGraphCard(
    graph: StreamPipelineGraph,
    modifier: Modifier = Modifier,
    onOpenPublishMqttDialog: ((suggestedTopic: String?) -> Unit)? = null,
    onSeekToOffset: ((Long) -> Unit)? = null,
) {
    DisableSelection {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier
                .fillMaxWidth()
                .testTag("streamPipelineGraph"),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 220.dp)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.size(graph.widthDp.dp, graph.heightDp.dp)) {
                    val edgeColor = MaterialTheme.colorScheme.outline
                    Canvas(Modifier.fillMaxSize()) {
                        val strokeWidthPx = 1.5.dp.toPx()
                        graph.edges.forEach { edge ->
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

                    graph.edges.forEach { edge ->
                        if (edge.label.isNotEmpty()) {
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
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag("pipelineEdgePill:${edge.label}"),
                            ) {
                                Text(
                                    text = edge.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    graph.nodes.forEach { node ->
                        PipelineNodeCard(
                            node = node,
                            onOpenPublishMqttDialog = onOpenPublishMqttDialog,
                            onSeekToOffset = onSeekToOffset,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineNodeCard(
    node: PipelineNode,
    onOpenPublishMqttDialog: ((suggestedTopic: String?) -> Unit)?,
    onSeekToOffset: ((Long) -> Unit)?,
) {
    val icon = when (node.kind) {
        PipelineNodeKind.MqttRoute -> AppIcon.Hub
        PipelineNodeKind.ExchangeRoute -> AppIcon.SwapHoriz
        PipelineNodeKind.StreamPublisher -> AppIcon.Send
        PipelineNodeKind.StreamHub -> AppIcon.Stream
        PipelineNodeKind.StreamConsumer -> AppIcon.Inbox
    }
    val borderColor = when {
        node.kind == PipelineNodeKind.StreamHub -> MaterialTheme.colorScheme.primary
        node.kind == PipelineNodeKind.MqttRoute -> MaterialTheme.colorScheme.secondary
        node.health == PipelineNodeHealth.Healthy -> HealthyGreen
        node.health == PipelineNodeHealth.Lagging -> LaggingAmber
        else -> MaterialTheme.colorScheme.outline
    }
    val clickAction: (() -> Unit)? = when (node.kind) {
        PipelineNodeKind.MqttRoute -> onOpenPublishMqttDialog?.let { cb -> { cb(node.suggestedMqttTopic) } }
        PipelineNodeKind.StreamConsumer -> {
            val offset = node.seekOffset
            if (offset != null && onSeekToOffset != null) {
                { onSeekToOffset(offset) }
            } else {
                null
            }
        }
        else -> null
    }

    Card(
        modifier = Modifier
            .offset(x = node.x.dp, y = node.y.dp)
            .width(PIPELINE_NODE_WIDTH.dp)
            .height(PIPELINE_NODE_HEIGHT.dp)
            .testTag("pipelineNode:${node.id}")
            .then(
                if (clickAction != null) {
                    Modifier.clickable(role = Role.Button, onClick = clickAction)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (node.kind == PipelineNodeKind.StreamHub) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(if (node.kind == PipelineNodeKind.StreamHub) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConnectionIcon(icon, SmallIconSize, tint = borderColor)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = node.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
