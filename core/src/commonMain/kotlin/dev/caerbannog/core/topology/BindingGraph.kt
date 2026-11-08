package dev.caerbannog.core.topology

import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.groupDigits

enum class BindingsViewMode { Table, Graph }

enum class GraphNodeKind { Exchange, Queue, Stream }

data class GraphNode(
    val id: String,
    val name: String,
    val kind: GraphNodeKind,
    val detail: String,
    val bindingCount: Int,
) {
    val accessibilityDescription: String
        get() = "${kind.name.lowercase()} $name, $bindingCount binding${if (bindingCount == 1) "" else "s"}"
}

data class GraphEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val routingKey: String,
    val arguments: String,
)

data class PositionedNode(
    val node: GraphNode,
    val layer: Int,
    val orderInLayer: Int,
    val x: Float,
    val y: Float,
)

data class PositionedEdge(
    val edge: GraphEdge,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val labelX: Float = (fromX + toX) / 2f,
    val labelY: Float = (fromY + toY) / 2f,
)

data class BindingGraph(
    val nodes: List<PositionedNode> = emptyList(),
    val edges: List<PositionedEdge> = emptyList(),
    val totalBindings: Int = 0,
    val isTooLarge: Boolean = false,
)

const val MAX_GRAPH_NODES = 60
const val NODE_WIDTH = 200f
const val NODE_HEIGHT = 48f
const val LAYER_SPACING = 140f
const val VERTICAL_SPACING = 20f

fun buildBindingGraph(
    exchanges: List<ExchangeRow>,
    queues: List<QueueRow>,
    bindings: List<BindingRow>,
    showDefaultExchange: Boolean = false,
    streams: List<StreamSummary> = emptyList(),
): BindingGraph {
    val filteredBindings = bindings.filter { showDefaultExchange || it.source.isNotEmpty() }
    val totalBindings = filteredBindings.size

    // Collect all referenced sources and destinations
    val activeExchangeNames = mutableSetOf<String>()
    val activeQueueNames = mutableSetOf<String>()

    filteredBindings.forEach { b ->
        activeExchangeNames.add(b.source)
        when (b.destinationType) {
            BindingDestinationType.EXCHANGE -> activeExchangeNames.add(b.destination)
            BindingDestinationType.QUEUE -> activeQueueNames.add(b.destination)
        }
    }

    val totalNodesCount = activeExchangeNames.size + activeQueueNames.size
    if (totalNodesCount > MAX_GRAPH_NODES) {
        return BindingGraph(
            nodes = emptyList(),
            edges = emptyList(),
            totalBindings = totalBindings,
            isTooLarge = true,
        )
    }

    if (totalNodesCount == 0) {
        return BindingGraph(
            nodes = emptyList(),
            edges = emptyList(),
            totalBindings = 0,
            isTooLarge = false,
        )
    }

    val exchangeMap = exchanges.associateBy { it.name }
    val queueMap = queues.associateBy { it.name }
    val streamMap = streams.associateBy { it.name }

    // Count bindings per node
    val bindingCounts = mutableMapOf<String, Int>()
    filteredBindings.forEach { b ->
        val srcId = "e:${b.source}"
        val dstId = if (b.destinationType == BindingDestinationType.EXCHANGE) "e:${b.destination}" else "q:${b.destination}"
        bindingCounts[srcId] = (bindingCounts[srcId] ?: 0) + 1
        bindingCounts[dstId] = (bindingCounts[dstId] ?: 0) + 1
    }

    // Build raw nodes
    val rawNodes = mutableListOf<GraphNode>()
    activeExchangeNames.forEach { name ->
        val ex = exchangeMap[name]
        val displayName = if (name.isEmpty()) TopologyTexts.DEFAULT_EXCHANGE else name
        val type = ex?.type ?: "direct"
        val id = "e:$name"
        rawNodes.add(
            GraphNode(
                id = id,
                name = displayName,
                kind = GraphNodeKind.Exchange,
                detail = "($type)",
                bindingCount = bindingCounts[id] ?: 0,
            )
        )
    }

    activeQueueNames.forEach { name ->
        val q = queueMap[name]
        val s = streamMap[name]
        val kind = if (s != null) GraphNodeKind.Stream else GraphNodeKind.Queue
        val detail = when {
            s != null -> s.messages?.let { "(stream · ${groupDigits(it)} msgs)" } ?: "(stream)"
            q != null -> q.readyMessages?.let { "(${groupDigits(it)} ready)" } ?: "(${TopologyTexts.NOT_AVAILABLE})"
            else -> ""
        }
        val id = "q:$name"
        rawNodes.add(
            GraphNode(
                id = id,
                name = name,
                kind = kind,
                detail = detail,
                bindingCount = bindingCounts[id] ?: 0,
            )
        )
    }

    // Compute layers (DAG topological layering)
    // 1. Exchange-to-exchange edges
    val exchangeAdj = mutableMapOf<String, MutableList<String>>()
    val exchangeInDegree = mutableMapOf<String, Int>()
    activeExchangeNames.forEach {
        exchangeAdj[it] = mutableListOf()
        exchangeInDegree[it] = 0
    }

    filteredBindings.filter { it.destinationType == BindingDestinationType.EXCHANGE }.forEach { b ->
        if (activeExchangeNames.contains(b.source) && activeExchangeNames.contains(b.destination)) {
            exchangeAdj[b.source]?.add(b.destination)
            exchangeInDegree[b.destination] = (exchangeInDegree[b.destination] ?: 0) + 1
        }
    }

    val exchangeLayers = mutableMapOf<String, Int>()
    // Start with exchanges having in-degree 0
    val roots = activeExchangeNames.filter { (exchangeInDegree[it] ?: 0) == 0 }
    val queue = ArrayDeque<Pair<String, Int>>()
    roots.forEach { root ->
        exchangeLayers[root] = 0
        queue.add(root to 0)
    }
    // Any cycle fallback: if no roots, start with all
    if (roots.isEmpty()) {
        activeExchangeNames.forEach {
            exchangeLayers[it] = 0
            queue.add(it to 0)
        }
    }

    val visited = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        val (curr, layer) = queue.removeFirst()
        visited.add(curr)
        exchangeAdj[curr]?.forEach { next ->
            val nextLayer = maxOf(exchangeLayers[next] ?: 0, layer + 1)
            exchangeLayers[next] = nextLayer
            if (!visited.contains(next) && nextLayer < MAX_GRAPH_NODES) {
                queue.add(next to nextLayer)
            }
        }
    }

    // Ensure all active exchanges have a layer
    activeExchangeNames.forEach {
        if (!exchangeLayers.containsKey(it)) exchangeLayers[it] = 0
    }

    // 2. Queue layers: each queue layer = max(incoming exchange layers) + 1
    val queueLayers = mutableMapOf<String, Int>()
    filteredBindings.filter { it.destinationType == BindingDestinationType.QUEUE }.forEach { b ->
        val srcLayer = exchangeLayers[b.source] ?: 0
        val currentMax = queueLayers[b.destination] ?: 0
        queueLayers[b.destination] = maxOf(currentMax, srcLayer + 1)
    }
    activeQueueNames.forEach {
        if (!queueLayers.containsKey(it)) queueLayers[it] = 1
    }

    // Assign layer to each node
    val nodeWithLayer = rawNodes.map { node ->
        val layer = if (node.kind == GraphNodeKind.Exchange) {
            val exName = if (node.name == TopologyTexts.DEFAULT_EXCHANGE) "" else node.name
            exchangeLayers[exName] ?: 0
        } else {
            queueLayers[node.name] ?: 1
        }
        node to layer
    }

    // Group by layer and sort alphabetically by name for deterministic/stable positioning
    val byName = compareBy<Pair<GraphNode, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.first.name }
    val groupedByLayer = nodeWithLayer.groupBy { it.second }.toSortedMap()

    val positionedNodes = mutableListOf<PositionedNode>()
    groupedByLayer.forEach { (layer, nodesInLayer) ->
        val sorted = nodesInLayer.sortedWith(byName)
        sorted.forEachIndexed { index, (node, _) ->
            val x = 24f + layer * (NODE_WIDTH + LAYER_SPACING)
            val y = 24f + index * (NODE_HEIGHT + VERTICAL_SPACING)
            positionedNodes.add(
                PositionedNode(
                    node = node,
                    layer = layer,
                    orderInLayer = index,
                    x = x,
                    y = y,
                )
            )
        }
    }

    val nodeMapById = positionedNodes.associateBy { it.node.id }

    // Position edges
    val initialEdges = mutableListOf<PositionedEdge>()
    filteredBindings.forEachIndexed { i, b ->
        val srcId = "e:${b.source}"
        val dstId = if (b.destinationType == BindingDestinationType.EXCHANGE) "e:${b.destination}" else "q:${b.destination}"
        val fromNode = nodeMapById[srcId]
        val toNode = nodeMapById[dstId]
        if (fromNode != null && toNode != null) {
            val fromX = fromNode.x + NODE_WIDTH
            val fromY = fromNode.y + NODE_HEIGHT / 2f
            val toX = toNode.x
            val toY = toNode.y + NODE_HEIGHT / 2f
            initialEdges.add(
                PositionedEdge(
                    edge = GraphEdge(
                        id = "edge-$i",
                        sourceId = srcId,
                        targetId = dstId,
                        routingKey = b.routingKey,
                        arguments = if (b.arguments.isEmpty()) "{}" else b.arguments.toString(),
                    ),
                    fromX = fromX,
                    fromY = fromY,
                    toX = toX,
                    toY = toY,
                )
            )
        }
    }

    val positionedEdges = resolveEdgeLabelPositions(initialEdges)

    return BindingGraph(
        nodes = positionedNodes,
        edges = positionedEdges,
        totalBindings = totalBindings,
        isTooLarge = false,
    )
}

private const val LABEL_COLLISION_DX = 60f
private const val LABEL_COLLISION_DY = 18f

/**
 * Evaluates X on the horizontal-tangent cubic bezier curve from [fromX] to [toX] at parameter [t] in `[0, 1]`.
 * Control points are `P1x = P2x = (fromX + toX) / 2`.
 */
private fun bezierX(fromX: Float, toX: Float, t: Float): Float {
    val u = 1f - t
    val midX = (fromX + toX) / 2f
    return u * u * u * fromX + 3f * u * t * midX + t * t * t * toX
}

/**
 * Evaluates Y on the horizontal-tangent cubic bezier curve from [fromY] to [toY] at parameter [t] in `[0, 1]`.
 * Control points are `P1y = fromY` and `P2y = toY`.
 */
private fun bezierY(fromY: Float, toY: Float, t: Float): Float {
    val u = 1f - t
    return (u * u * u + 3f * u * u * t) * fromY + (3f * u * t * t + t * t * t) * toY
}

/**
 * Staggers routing-key label anchors along their cubic bezier curves whenever two or more labeled edges
 * would collide near their midpoints (such as crossing edges between the same layers).
 */
private fun resolveEdgeLabelPositions(edges: List<PositionedEdge>): List<PositionedEdge> {
    val labeledIndices = edges.indices.filter { edges[it].edge.routingKey.isNotEmpty() }
    if (labeledIndices.size <= 1) return edges

    val result = edges.toMutableList()
    val visited = BooleanArray(edges.size)

    for (idx in labeledIndices) {
        if (visited[idx]) continue
        // Build a connected component of labeled edges whose default midpoint anchors collide
        val cluster = mutableListOf<Int>()
        val queue = ArrayDeque<Int>()
        visited[idx] = true
        queue.add(idx)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            cluster.add(curr)
            val eCurr = edges[curr]
            for (other in labeledIndices) {
                if (!visited[other]) {
                    val eOther = edges[other]
                    if (kotlin.math.abs(eCurr.labelX - eOther.labelX) < LABEL_COLLISION_DX &&
                        kotlin.math.abs(eCurr.labelY - eOther.labelY) < LABEL_COLLISION_DY
                    ) {
                        visited[other] = true
                        queue.add(other)
                    }
                }
            }
        }

        if (cluster.size > 1) {
            val count = cluster.size
            cluster.forEachIndexed { k, edgeIdx ->
                val e = edges[edgeIdx]
                val t = 0.25f + (0.50f * k) / (count - 1).toFloat()
                val newX = bezierX(e.fromX, e.toX, t)
                val newY = if (e.fromY == e.toY) {
                    // Horizontal parallel edges: stagger t along X and nudge Y slightly if identical
                    e.fromY + (k - (count - 1) / 2f) * 18f
                } else {
                    bezierY(e.fromY, e.toY, t)
                }
                result[edgeIdx] = e.copy(labelX = newX, labelY = newY)
            }
        }
    }

    return result
}
