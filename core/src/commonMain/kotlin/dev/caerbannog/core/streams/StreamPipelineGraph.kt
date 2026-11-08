package dev.caerbannog.core.streams

import dev.caerbannog.core.mqtt.StreamInboundRoute

enum class PipelineNodeKind {
    MqttRoute,
    ExchangeRoute,
    StreamPublisher,
    StreamHub,
    StreamConsumer,
}

enum class PipelineNodeHealth {
    Healthy,
    Lagging,
    Standby,
    Neutral,
}

data class PipelineNode(
    val id: String,
    val kind: PipelineNodeKind,
    val title: String,
    val subtitle: String,
    val layer: Int,
    val x: Float,
    val y: Float,
    val health: PipelineNodeHealth = PipelineNodeHealth.Neutral,
    val suggestedMqttTopic: String? = null,
    val seekOffset: Long? = null,
)

data class PipelineEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val label: String,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val labelX: Float = (fromX + toX) / 2f,
    val labelY: Float = (fromY + toY) / 2f,
)

data class StreamPipelineGraph(
    val stream: String,
    val nodes: List<PipelineNode>,
    val edges: List<PipelineEdge>,
    val widthDp: Float,
    val heightDp: Float,
) {
    val hasSourcesOrSinks: Boolean
        get() = nodes.any { it.kind != PipelineNodeKind.StreamHub }
}

const val PIPELINE_NODE_WIDTH = 188f
const val PIPELINE_NODE_HEIGHT = 44f
const val PIPELINE_LAYER_GAP = 116f
const val PIPELINE_VERTICAL_GAP = 12f
private const val PIPELINE_PAD_X = 16f
private const val PIPELINE_PAD_Y = 14f

/**
 * Builds the 3-layer End-to-End Live Data Pipeline Graph for a stream:
 * - Layer 0 (Ingress): Exchange/MQTT bindings (`amq.topic ──[sensors.#]──>`) and Stream Protocol publishers
 * - Layer 1 (Hub): The Stream itself (`stream` + head offset)
 * - Layer 2 (Egress): Stream Consumers with live offset and lag health
 */
fun buildStreamPipelineGraph(
    stream: String,
    lastOffset: String,
    inboundRoutes: List<StreamInboundRoute> = emptyList(),
    publishers: List<PublisherRowState> = emptyList(),
    consumers: List<ConsumerRowState> = emptyList(),
): StreamPipelineGraph {
    data class RawNode(
        val id: String,
        val kind: PipelineNodeKind,
        val title: String,
        val subtitle: String,
        val health: PipelineNodeHealth = PipelineNodeHealth.Neutral,
        val suggestedMqttTopic: String? = null,
        val seekOffset: Long? = null,
        val edgeLabel: String = "",
    )

    val ingressRaw = buildList {
        inboundRoutes.forEach { route ->
            val keyDisplay = route.routingKey.ifEmpty { "*" }
            if (route.isMqttTopicExchange) {
                add(
                    RawNode(
                        id = "route:${route.exchange}:${route.routingKey}",
                        kind = PipelineNodeKind.MqttRoute,
                        title = route.exchange,
                        subtitle = "MQTT: ${route.mqttTopicFilter ?: keyDisplay}",
                        health = PipelineNodeHealth.Healthy,
                        suggestedMqttTopic = route.suggestedPublishTopic,
                        edgeLabel = keyDisplay,
                    )
                )
            } else {
                add(
                    RawNode(
                        id = "route:${route.exchange}:${route.routingKey}",
                        kind = PipelineNodeKind.ExchangeRoute,
                        title = route.exchange,
                        subtitle = "key: $keyDisplay",
                        health = PipelineNodeHealth.Neutral,
                        edgeLabel = keyDisplay,
                    )
                )
            }
        }
        publishers.forEach { pub ->
            add(
                RawNode(
                    id = "pub:${pub.key}",
                    kind = PipelineNodeKind.StreamPublisher,
                    title = pub.reference,
                    subtitle = "${pub.published} published",
                    health = if (pub.settling) PipelineNodeHealth.Neutral else PipelineNodeHealth.Healthy,
                    edgeLabel = "stream",
                )
            )
        }
    }

    val egressRaw = consumers.map { cons ->
        val health = when (cons.status) {
            ConsumerStatus.CaughtUp -> PipelineNodeHealth.Healthy
            is ConsumerStatus.Behind -> PipelineNodeHealth.Lagging
            ConsumerStatus.NotActive -> PipelineNodeHealth.Standby
            ConsumerStatus.Updating -> PipelineNodeHealth.Neutral
        }
        val subtitle = if (cons.browseFromOffset != null) {
            "@${cons.offset} · lag ${cons.behind}"
        } else {
            cons.offset
        }
        RawNode(
            id = "cons:${cons.key}",
            kind = PipelineNodeKind.StreamConsumer,
            title = cons.name,
            subtitle = subtitle,
            health = health,
            seekOffset = cons.browseFromOffset,
        )
    }

    val maxColumnCount = maxOf(1, ingressRaw.size, egressRaw.size)
    val totalHeight = PIPELINE_PAD_Y * 2f +
        maxColumnCount * PIPELINE_NODE_HEIGHT +
        (maxColumnCount - 1).coerceAtLeast(0) * PIPELINE_VERTICAL_GAP

    fun columnStartY(count: Int): Float {
        if (count <= 0) return (totalHeight - PIPELINE_NODE_HEIGHT) / 2f
        val colH = count * PIPELINE_NODE_HEIGHT + (count - 1) * PIPELINE_VERTICAL_GAP
        return (totalHeight - colH) / 2f
    }

    val x0 = PIPELINE_PAD_X
    val x1 = PIPELINE_PAD_X + PIPELINE_NODE_WIDTH + PIPELINE_LAYER_GAP
    val x2 = PIPELINE_PAD_X + 2f * (PIPELINE_NODE_WIDTH + PIPELINE_LAYER_GAP)

    val nodes = mutableListOf<PipelineNode>()
    val edges = mutableListOf<PipelineEdge>()

    val y0Start = columnStartY(ingressRaw.size)
    ingressRaw.forEachIndexed { idx, raw ->
        val y = y0Start + idx * (PIPELINE_NODE_HEIGHT + PIPELINE_VERTICAL_GAP)
        nodes.add(
            PipelineNode(
                id = raw.id,
                kind = raw.kind,
                title = raw.title,
                subtitle = raw.subtitle,
                layer = 0,
                x = x0,
                y = y,
                health = raw.health,
                suggestedMqttTopic = raw.suggestedMqttTopic,
            )
        )
    }

    val hubY = columnStartY(1)
    val hubSubtitle = if (lastOffset.isNotBlank() && lastOffset != StreamListTexts.UNAVAILABLE) {
        "head @$lastOffset"
    } else {
        "stream"
    }
    val hubNode = PipelineNode(
        id = "stream:$stream",
        kind = PipelineNodeKind.StreamHub,
        title = stream,
        subtitle = hubSubtitle,
        layer = 1,
        x = x1,
        y = hubY,
        health = PipelineNodeHealth.Healthy,
    )
    nodes.add(hubNode)

    val y2Start = columnStartY(egressRaw.size)
    egressRaw.forEachIndexed { idx, raw ->
        val y = y2Start + idx * (PIPELINE_NODE_HEIGHT + PIPELINE_VERTICAL_GAP)
        nodes.add(
            PipelineNode(
                id = raw.id,
                kind = raw.kind,
                title = raw.title,
                subtitle = raw.subtitle,
                layer = 2,
                x = x2,
                y = y,
                health = raw.health,
                seekOffset = raw.seekOffset,
            )
        )
    }

    // Build edges: Layer 0 -> Hub
    ingressRaw.forEachIndexed { idx, raw ->
        val srcNode = nodes.first { it.id == raw.id }
        val fromX = srcNode.x + PIPELINE_NODE_WIDTH
        val fromY = srcNode.y + PIPELINE_NODE_HEIGHT / 2f
        val toX = hubNode.x
        val toY = hubNode.y + PIPELINE_NODE_HEIGHT / 2f
        edges.add(
            PipelineEdge(
                id = "in-$idx",
                sourceId = srcNode.id,
                targetId = hubNode.id,
                label = raw.edgeLabel,
                fromX = fromX,
                fromY = fromY,
                toX = toX,
                toY = toY,
            )
        )
    }

    // Build edges: Hub -> Layer 2
    egressRaw.forEachIndexed { idx, raw ->
        val dstNode = nodes.first { it.id == raw.id }
        val fromX = hubNode.x + PIPELINE_NODE_WIDTH
        val fromY = hubNode.y + PIPELINE_NODE_HEIGHT / 2f
        val toX = dstNode.x
        val toY = dstNode.y + PIPELINE_NODE_HEIGHT / 2f
        edges.add(
            PipelineEdge(
                id = "out-$idx",
                sourceId = hubNode.id,
                targetId = dstNode.id,
                label = "",
                fromX = fromX,
                fromY = fromY,
                toX = toX,
                toY = toY,
            )
        )
    }

    val totalWidth = x2 + PIPELINE_NODE_WIDTH + PIPELINE_PAD_X
    return StreamPipelineGraph(
        stream = stream,
        nodes = nodes,
        edges = edges,
        widthDp = totalWidth,
        heightDp = totalHeight,
    )
}
