package dev.caerbannog.core.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
