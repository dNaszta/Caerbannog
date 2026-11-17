package dev.caerbannog.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.HorizontalScrollbar
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.PlanGraph
import dev.caerbannog.core.planner.PlanMark
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.topology.BindingGraph
import dev.caerbannog.core.topology.GraphNodeKind
import dev.caerbannog.core.topology.NODE_HEIGHT
import dev.caerbannog.core.topology.NODE_WIDTH
import dev.caerbannog.core.topology.PositionedNode
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The drawing of a [BindingGraph]: the scroll container, the edges with their routing key pills and the node cards. Shared by the Bindings graph
 * of the Topology screen and the planner's review graph. The scroll sits here, on the fixed-size inner box, because that is where the content
 * truly overflows.
 */
@Composable
internal fun BindingGraphCanvas(
    graph: BindingGraph,
    onSelectQueue: ((String) -> Unit)? = null,
    onOpenStream: ((String) -> Unit)? = null,
    /** The planner's review: marks the new parts, names the nodes, and draws nothing that can be clicked. Null: the Bindings graph as it is. */
    plan: PlanGraph? = null,
    size: Modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 800.dp),
    containerTag: String = "bindingsGraphContainer",
    /** Scrollbars for mouse users, drawn only on overflow. Off for the Topology screen, whose canvas stays as it was. */
    scrollbars: Boolean = false,
) {
    // The review graph closes an empty first column (a plan of queues only has no exchange to its left), so a small plan fits its card.
    val shift = if (plan == null) 0f else (graph.nodes.minOfOrNull { it.x } ?: 24f) - 24f
    val nodes = if (shift == 0f) graph.nodes else graph.nodes.map { it.copy(x = it.x - shift) }
    val edges = if (shift == 0f) graph.edges else graph.edges.map { it.copy(fromX = it.fromX - shift, toX = it.toX - shift, labelX = it.labelX - shift) }
    val links = plan?.let { p -> responseLinkShapes(p, nodes) }.orEmpty()
    // A link of a pair in one column is drawn on the right of it, with its label: leave room for both.
    val linkRoom = if (links.isEmpty()) 0f else (links.maxOf { it.labelX } + LINK_LABEL_WIDTH + 12f)
    val maxX = maxOf((nodes.maxOfOrNull { it.x } ?: 0f) + NODE_WIDTH + 48f, linkRoom)
    val maxY = (nodes.maxOfOrNull { it.y } ?: 0f) + NODE_HEIGHT + 48f
    // The Bindings graph keeps its minimum size; the review graph is as big as its content, so a small plan fits its card and has no bar.
    val graphWidth = maxOf(if (plan == null) 640f else 0f, maxX).dp
    val graphHeight = maxOf(if (plan == null) 400f else 0f, maxY).dp

    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val drawing: @Composable () -> Unit = {
        Box(Modifier.size(graphWidth, graphHeight)) {
            // Edges canvas
            val edgeColor = MaterialTheme.colorScheme.outline
            val newEdgeColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidthPx = 1.5.dp.toPx()
                edges.forEach { edge ->
                    val isNew = plan != null && edge.edge.id in plan.newEdgeIds
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
                    drawPath(
                        path,
                        color = if (isNew) newEdgeColor else edgeColor,
                        style = Stroke(width = if (isNew) strokeWidthPx * 2 else strokeWidthPx),
                    )
                }
            }

            // Response stream links: dotted, no arrow, never a routing line
            val linkColor = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxSize()) {
                val width = 1.5.dp.toPx()
                links.forEach { link ->
                    val path = Path().apply {
                        link.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x.dp.toPx(), y.dp.toPx()) else lineTo(x.dp.toPx(), y.dp.toPx()) }
                    }
                    drawPath(
                        path,
                        color = linkColor,
                        style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 2.5f))),
                    )
                }
            }
            links.forEach { link ->
                Box(
                    Modifier
                        .offset(x = link.labelX.dp, y = link.labelY.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, -placeable.height / 2) }
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("planGraphLink:${link.sourceId}:${link.targetId}"),
                ) {
                    Text(
                        PlannerTexts.RESPONSE_LINK_LABEL,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // An MQTT route says so under its key pill: the topic filter as the user typed it, and the words that name the kind.
            if (plan != null) {
                edges.filter { it.edge.id in plan.mqttRouteEdgeIds }.forEach { edge ->
                    Column(
                        Modifier
                            .offset(x = edge.labelX.dp, y = (edge.labelY + MQTT_LABEL_OFFSET).dp)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(-placeable.width / 2, 0) }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            plan.edgeLabels[edge.edge.id].orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.testTag("planGraphEdgeLabel:${edge.edge.id}"),
                        )
                        Text(
                            PlannerTexts.MQTT_ROUTE_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("planGraphEdgeTag:${edge.edge.id}"),
                        )
                    }
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

            // "new" pills of the plan's own bindings, below the routing key pill (or on the line when it has no key)
            if (plan != null) {
                edges.filter { it.edge.id in plan.newEdgeIds }.forEach { edge ->
                    NewPill(
                        Modifier
                            .offset(x = edge.labelX.dp, y = (edge.labelY + if (edge.edge.routingKey.isEmpty()) 0f else 18f).dp)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    placeable.place(-placeable.width / 2, -placeable.height / 2)
                                }
                            }
                            .testTag("planGraphNew:${edge.edge.id}"),
                    )
                }
            }

            // Nodes
            nodes.forEach { pNode ->
                GraphNodeCard(
                    plan = plan,
                    pNode = pNode,
                    onSelectQueue = onSelectQueue,
                    onOpenStream = onOpenStream,
                )
            }
        }
    }
    val frame = size
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    if (!scrollbars) {
        Box(frame.horizontalScroll(horizontal).verticalScroll(vertical).testTag(containerTag)) { drawing() }
    } else {
        Box(frame) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical).testTag(containerTag)) { drawing() }
            // Drawn only when the content overflows, so a graph that fits has none. One tagged box holds both bars.
            val overflows = horizontal.maxValue > 0 || vertical.maxValue > 0
            if (overflows) {
                Box(Modifier.matchParentSize().testTag("planner:graphScrollbar")) {
                    if (horizontal.maxValue > 0) {
                        HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp))
                    }
                    if (vertical.maxValue > 0) {
                        VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(bottom = 12.dp))
                    }
                }
            }
        }
    }
}

/** A response stream link ready to draw: the polyline, and where its label starts (left edge) and sits (vertical middle). */
private class LinkShape(val sourceId: String, val targetId: String, val points: List<Pair<Float, Float>>, val labelX: Float, val labelY: Float)

/** Below the key pill (18 dp tall) and the "new" pill under it. */
private const val MQTT_LABEL_OFFSET = 34f
private const val LINK_LABEL_WIDTH = 96f
private const val LINK_BRACKET = 14f

/**
 * Links whose two nodes are both drawn. Two nodes of one column are joined by a bracket on the right of the column, so the line never crosses
 * a node between them; nodes of two columns by a straight line from the right side of the left one to the left side of the other.
 */
private fun responseLinkShapes(plan: PlanGraph, nodes: List<PositionedNode>): List<LinkShape> = plan.links.mapNotNull { link ->
    val from = nodes.firstOrNull { it.node.id == link.sourceId } ?: return@mapNotNull null
    val to = nodes.firstOrNull { it.node.id == link.targetId } ?: return@mapNotNull null
    val fromY = from.y + NODE_HEIGHT / 2f
    val toY = to.y + NODE_HEIGHT / 2f
    if (from.x == to.x) {
        val right = from.x + NODE_WIDTH
        LinkShape(
            link.sourceId, link.targetId,
            listOf(right to fromY, right + LINK_BRACKET to fromY, right + LINK_BRACKET to toY, right to toY),
            labelX = right + LINK_BRACKET + 4f,
            labelY = (fromY + toY) / 2f,
        )
    } else {
        val (left, right) = if (from.x < to.x) from to to else to to from
        val startX = left.x + NODE_WIDTH
        val startY = left.y + NODE_HEIGHT / 2f
        val endY = right.y + NODE_HEIGHT / 2f
        LinkShape(
            link.sourceId, link.targetId,
            listOf(startX to startY, right.x to endY),
            labelX = (startX + right.x) / 2f - LINK_LABEL_WIDTH / 2f,
            labelY = (startY + endY) / 2f,
        )
    }
}

@Composable
private fun GraphNodeCard(
    plan: PlanGraph?,
    pNode: PositionedNode,
    onSelectQueue: ((String) -> Unit)? = null,
    onOpenStream: ((String) -> Unit)? = null,
) {
    val node = pNode.node
    val icon = when (node.kind) {
        GraphNodeKind.Exchange -> AppIcon.SwapHoriz
        GraphNodeKind.Stream -> AppIcon.Stream
        GraphNodeKind.MqttClient -> AppIcon.Hub
        GraphNodeKind.Queue -> AppIcon.Inbox
    }
    val clickHandler: (() -> Unit)? = when (node.kind) {
        GraphNodeKind.Stream -> onOpenStream?.let { cb -> { cb(node.name) } }
        GraphNodeKind.Queue -> onSelectQueue?.let { cb -> { cb(node.name) } }
        GraphNodeKind.Exchange, GraphNodeKind.MqttClient -> null
    }
    val borderColor = when {
        node.memberOffline -> MaterialTheme.colorScheme.error
        node.kind == GraphNodeKind.Stream -> MaterialTheme.colorScheme.primary
        node.kind == GraphNodeKind.MqttClient -> MaterialTheme.colorScheme.secondary
        node.unackedCount > 0L -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val mark = plan?.markOf(node.id)
    val borderWidth = if (mark == PlanMark.New) {
        3.dp
    } else if (node.memberOffline || node.kind == GraphNodeKind.Stream || node.kind == GraphNodeKind.MqttClient || node.unackedCount > 0L) {
        1.5.dp
    } else {
        1.dp
    }
    val outline = if (mark == PlanMark.New) MaterialTheme.colorScheme.primary else borderColor
    val cardShape = RoundedCornerShape(8.dp)
    Card(
        modifier = Modifier
            .offset(x = pNode.x.dp, y = pNode.y.dp)
            .width(NODE_WIDTH.dp)
            .height(NODE_HEIGHT.dp)
            .testTag(if (plan != null) "planGraphNode:${node.id}" else "graphNode:${node.name}")
            .then(
                if (clickHandler != null) {
                    Modifier.clickable(role = Role.Button, onClick = clickHandler)
                } else {
                    Modifier
                }
            )
            .focusable()
            .semantics {
                if (plan != null) {
                    // A review node can be focused and read, never pressed: no role of a button and no click.
                    contentDescription = plan.accessibleName(node)
                } else {
                    contentDescription = node.accessibilityDescription
                    role = Role.Button
                }
            }
            .then(if (mark == PlanMark.NotInPlan) Modifier.dottedOutline(outline) else Modifier),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (mark == PlanMark.NotInPlan) null else BorderStroke(borderWidth, outline),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionIcon(
                icon = icon,
                size = SmallIconSize,
                tint = if (node.memberOffline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
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
                if (mark == PlanMark.NotInPlan) {
                    Text(
                        PlannerTexts.NOT_IN_THIS_PLAN,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.testTag("planGraphNotInPlan:${node.id}"),
                    )
                } else if (node.detail.isNotEmpty()) {
                    Text(
                        node.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (mark == PlanMark.New) NewPill(Modifier.testTag("planGraphNew:${node.id}"))
            if (node.memberOffline && node.kind == GraphNodeKind.Queue) {
                Box(Modifier.testTag("graphNodeOffline:${node.name}")) {
                    ConnectionIcon(AppIcon.Warning, SmallIconSize, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** The word "new" on a part this plan adds. */
@Composable
private fun NewPill(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            PlannerTexts.NEW_MARK,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/** A dotted outline for an item that is nowhere (the words "not in this plan" say it too, so the outline is never the only cue). */
private fun Modifier.dottedOutline(color: Color): Modifier = drawWithContent {
    drawContent()
    val width = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 2.5f))),
    )
}
