package dev.caerbannog.core.topology

enum class BindingsViewMode { Table, Graph }

enum class GraphNodeKind { Exchange, Queue }

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
        val ready = q?.readyMessages ?: 0
        val id = "q:$name"
        rawNodes.add(
            GraphNode(
                id = id,
                name = name,
                kind = GraphNodeKind.Queue,
                detail = "($ready ready)",
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
    val positionedEdges = mutableListOf<PositionedEdge>()
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
            positionedEdges.add(
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

    return BindingGraph(
        nodes = positionedNodes,
        edges = positionedEdges,
        totalBindings = totalBindings,
        isTooLarge = false,
    )
}
