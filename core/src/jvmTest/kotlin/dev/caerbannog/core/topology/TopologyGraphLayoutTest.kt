package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.mqtt.StreamInboundRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologyGraphLayoutTest {

    private fun exchange(name: String, type: String = "direct") =
        ExchangeRow(name = name, vhost = "/", type = type, durable = true, autoDelete = false, internal = false, arguments = emptyMap(), superStream = false)

    private fun queue(name: String, ready: Long = 0) =
        QueueRow(name = name, vhost = "/", type = "quorum", readyMessages = ready, unackedMessages = 0, consumers = 0, leader = null, members = emptyList(), online = emptyList(), policy = null, state = "running")

    private fun binding(source: String, destination: String, type: BindingDestinationType = BindingDestinationType.QUEUE, key: String = "") =
        BindingRow(source = source, vhost = "/", destination = destination, destinationType = type, routingKey = key, arguments = emptyMap())

    @Test
    fun `graph organizes root exchanges, intermediary exchanges, and queues into layers`() {
        val exchanges = listOf(exchange("events", "topic"), exchange("audit-fanout", "fanout"))
        val queues = listOf(queue("orders"), queue("audit-log"))
        val bindings = listOf(
            binding("events", "orders", BindingDestinationType.QUEUE, "order.created"),
            binding("events", "audit-fanout", BindingDestinationType.EXCHANGE, "#"),
            binding("audit-fanout", "audit-log", BindingDestinationType.QUEUE, ""),
        )

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        assertFalse(graph.isTooLarge)
        assertEquals(4, graph.nodes.size)
        assertEquals(3, graph.edges.size)

        val eventsNode = graph.nodes.first { it.node.name == "events" }
        val fanoutNode = graph.nodes.first { it.node.name == "audit-fanout" }
        val ordersNode = graph.nodes.first { it.node.name == "orders" }
        val auditLogNode = graph.nodes.first { it.node.name == "audit-log" }

        assertEquals(0, eventsNode.layer)
        assertEquals(1, fanoutNode.layer)
        assertEquals(1, ordersNode.layer)
        assertEquals(2, auditLogNode.layer)
        assertTrue(eventsNode.x < fanoutNode.x)
        assertTrue(fanoutNode.x < auditLogNode.x)
    }

    @Test
    fun `default exchange is excluded by default and included when asked`() {
        val exchanges = listOf(exchange(""), exchange("events"))
        val queues = (1..5).map { queue("q-$it") }
        val bindings = queues.map { binding("", it.name, BindingDestinationType.QUEUE, it.name) } +
            listOf(binding("events", "q-1", BindingDestinationType.QUEUE, "key"))

        val graphDefaultOff = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        assertFalse(graphDefaultOff.isTooLarge)
        assertFalse(graphDefaultOff.nodes.any { it.node.name == "" || it.node.name == "(default exchange)" })
        // Only q-1 is in the graph because q-2..q-5 are only bound to default exchange
        assertEquals(listOf("events", "q-1").sorted(), graphDefaultOff.nodes.map { it.node.name }.sorted())

        val graphDefaultOn = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = true)
        assertTrue(graphDefaultOn.nodes.any { it.node.name == "(default exchange)" })
        assertEquals(7, graphDefaultOn.nodes.size)
    }

    @Test
    fun `graph exceeding max nodes is flagged as too large with empty layout`() {
        val exchanges = listOf(exchange("events"))
        val queues = (1..100).map { queue("q-$it") }
        val bindings = queues.map { binding("events", it.name, BindingDestinationType.QUEUE, "k") }

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        assertTrue(graph.isTooLarge)
        assertEquals(0, graph.nodes.size)
        assertEquals(0, graph.edges.size)
        assertEquals(100, graph.totalBindings)
    }

    @Test
    fun `cycles in exchange bindings do not cause infinite recursion`() {
        val exchanges = listOf(exchange("e1"), exchange("e2"))
        val bindings = listOf(
            binding("e1", "e2", BindingDestinationType.EXCHANGE, "to-e2"),
            binding("e2", "e1", BindingDestinationType.EXCHANGE, "to-e1"),
        )

        val graph = buildBindingGraph(exchanges, emptyList(), bindings, showDefaultExchange = false)
        assertFalse(graph.isTooLarge)
        assertEquals(2, graph.nodes.size)
        assertEquals(2, graph.edges.size)
    }

    @Test
    fun `ordering within layers is stable and sorted by name`() {
        val exchanges = listOf(exchange("e-b"), exchange("e-a"), exchange("e-c"))
        val queues = listOf(queue("q-z"), queue("q-a"))
        val bindings = listOf(
            binding("e-b", "q-z"),
            binding("e-a", "q-z"),
            binding("e-c", "q-a"),
        )

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        val layer0Names = graph.nodes.filter { it.layer == 0 }.map { it.node.name }
        assertEquals(listOf("e-a", "e-b", "e-c"), layer0Names)
        val layer1Names = graph.nodes.filter { it.layer == 1 }.map { it.node.name }
        assertEquals(listOf("q-a", "q-z"), layer1Names)
    }

    @Test
    fun `crossing edges between the same layers receive non-overlapping label anchors that lie on their bezier curves`() {
        val exchanges = listOf(exchange("dev.commands", "direct"), exchange("dev.events", "topic"))
        val queues = listOf(queue("dev-jobs", ready = 10), queue("dev-mail", ready = 5))
        val bindings = listOf(
            binding("dev.commands", "dev-mail", BindingDestinationType.QUEUE, "mail"),
            binding("dev.events", "dev-jobs", BindingDestinationType.QUEUE, "job.#"),
        )

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        assertEquals(2, graph.edges.size)
        val e1 = graph.edges.first { it.edge.routingKey == "mail" }
        val e2 = graph.edges.first { it.edge.routingKey == "job.#" }

        // Both edges share the exact same geometric midpoint, so their label anchors must be staggered apart
        val dx = kotlin.math.abs(e1.labelX - e2.labelX)
        val dy = kotlin.math.abs(e1.labelY - e2.labelY)
        assertTrue(
            dx >= 40f || dy >= 16f,
            "Crossing edges must not share overlapping label anchors: e1=(${e1.labelX}, ${e1.labelY}), e2=(${e2.labelX}, ${e2.labelY})",
        )

        // Each (labelX, labelY) must lie on its own cubic bezier curve B(t) for some t in [0.15, 0.85]
        for (edge in listOf(e1, e2)) {
            val minDist = (15..85).minOf { pct ->
                val t = pct / 100f
                val u = 1f - t
                val midX = (edge.fromX + edge.toX) / 2f
                val bx = u * u * u * edge.fromX + 3f * u * t * midX + t * t * t * edge.toX
                val by = (u * u * u + 3f * u * u * t) * edge.fromY + (3f * u * t * t + t * t * t) * edge.toY
                kotlin.math.hypot((edge.labelX - bx).toDouble(), (edge.labelY - by).toDouble())
            }
            assertTrue(minDist <= 2.5, "Label (${edge.labelX}, ${edge.labelY}) for '${edge.edge.routingKey}' must lie on its bezier curve (minDist=$minDist)")
        }
    }

    @Test
    fun `stream partition destinations absent from queues have empty detail while known queues format ready count with digit grouping`() {
        val exchanges = listOf(exchange("orders", "direct"), exchange("events", "topic"))
        val queues = listOf(queue("dev-jobs", ready = 1240))
        val bindings = listOf(
            binding("orders", "orders-0", BindingDestinationType.QUEUE, "0"),
            binding("events", "dev-jobs", BindingDestinationType.QUEUE, "job.#"),
        )

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        val partitionNode = graph.nodes.first { it.node.name == "orders-0" }.node
        val quorumNode = graph.nodes.first { it.node.name == "dev-jobs" }.node

        assertEquals("", partitionNode.detail, "Stream partition absent from queues must not show fake (0 ready)")
        assertEquals("(1,240 ready)", quorumNode.detail, "Known queue must format readyMessages with groupDigits")
    }

    @Test
    fun `stream destinations matching known streams receive GraphNodeKind Stream and formatted message counts`() {
        val exchanges = listOf(exchange("amq.topic", "topic"))
        val queues = listOf(queue("orders", ready = 25))
        val streams = listOf(
            stream(name = "telemetry-stream", messages = 4500L),
        )
        val bindings = listOf(
            binding("amq.topic", "telemetry-stream", BindingDestinationType.QUEUE, "sensors.#"),
            binding("amq.topic", "orders", BindingDestinationType.QUEUE, "orders.#"),
        )

        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false, streams = streams)
        val streamNode = graph.nodes.first { it.node.name == "telemetry-stream" }.node
        val queueNode = graph.nodes.first { it.node.name == "orders" }.node

        assertEquals(GraphNodeKind.Stream, streamNode.kind)
        assertEquals("(stream · 4,500 msgs)", streamNode.detail)
        assertEquals(GraphNodeKind.Queue, queueNode.kind)
        assertEquals("(25 ready)", queueNode.detail)
    }

    @Test
    fun `StreamInboundRoute translates amq topic routing keys into MQTT topic filters and concrete suggested publish topics`() {
        val multiLevel = StreamInboundRoute(exchange = "amq.topic", routingKey = "sensors.#")
        assertTrue(multiLevel.isMqttTopicExchange)
        assertEquals("sensors/#", multiLevel.mqttTopicFilter)
        assertEquals("sensors/test", multiLevel.suggestedPublishTopic)

        val singleLevel = StreamInboundRoute(exchange = "amq.topic", routingKey = "devices.*.telemetry")
        assertTrue(singleLevel.isMqttTopicExchange)
        assertEquals("devices/+/telemetry", singleLevel.mqttTopicFilter)
        assertEquals("devices/item/telemetry", singleLevel.suggestedPublishTopic)

        val rootWildcard = StreamInboundRoute(exchange = "amq.topic", routingKey = "#")
        assertEquals("#", rootWildcard.mqttTopicFilter)
        assertEquals("test", rootWildcard.suggestedPublishTopic)

        val nonMqtt = StreamInboundRoute(exchange = "orders.exchange", routingKey = "orders.created")
        assertFalse(nonMqtt.isMqttTopicExchange)
        assertNull(nonMqtt.mqttTopicFilter)
        assertNull(nonMqtt.suggestedPublishTopic)
    }
}
