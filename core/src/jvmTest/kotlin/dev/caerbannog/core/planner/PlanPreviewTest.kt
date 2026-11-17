package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.GraphNodeKind
import dev.caerbannog.core.topology.MAX_GRAPH_NODES
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-04 step 2: `planPreview(plan, known)` turns a plan and what the broker has into the review graph, as a pure function. Contract fixed by
 * these tests (spec "Review graph", "Which existing items are drawn"; brief M9-04 Scope):
 *  - every exchange, queue and stream the plan DECLARES is a node, with or without a binding (the picture of the result, not only of the
 *    bindings); `buildBindingGraph` alone draws only the ends of bindings, so it needs an additive way to be given nodes (the test developer
 *    asks the architect to confirm this is wanted, see the step 2 report);
 *  - the ends of the plan's bindings that the broker has, by name, are drawn as existing; the broker's other items and ALL its bindings never;
 *  - a name that the plan declares and `known` also holds is existing, not new;
 *  - an end that neither the plan nor the broker has is "not in this plan";
 *  - `known == null` is "only the plan": everything the plan declares is new, nothing is invented, and a notice says why
 *    ([OnlyThePlanCause], third parameter, default NotConnected, because a null is "no reader" by default).
 */
class PlanPreviewTest {
    private fun queue(name: String) = MigrationAction.DeclareQueue(name = name)
    private fun exchange(name: String, type: String = "direct") = MigrationAction.DeclareExchange(name = name, type = type)
    private fun bind(exchange: String, queue: String, key: String = "") = MigrationAction.DeclareBinding(exchange = exchange, queue = queue, routingKey = key)
    private val none = KnownTopology.None

    private fun PlanGraph.ids() = graph.nodes.map { it.node.id }.toSet()
    private fun PlanGraph.node(id: String) = graph.nodes.first { it.node.id == id }.node

    @Test
    fun `a plan alone draws what it declares, all new, with the binding as a new edge`() {
        val g = planPreview(planOf(queue("orders"), exchange("events"), bind("events", "orders", "order.created")), none)
        assertEquals(setOf("e:events", "q:orders"), g.ids())
        assertEquals(g.ids(), g.newNodeIds)
        assertEquals(1, g.graph.edges.size)
        assertEquals(setOf("edge-0"), g.newEdgeIds)
        val edge = g.graph.edges.single().edge
        assertEquals("e:events", edge.sourceId)
        assertEquals("q:orders", edge.targetId)
        assertEquals("order.created", edge.routingKey)
        assertEquals(emptySet(), g.notInPlanNodeIds)
        assertNull(g.notice, "a connected, readable broker with a small plan has nothing to say")
        assertFalse(g.graph.isTooLarge)
    }

    @Test
    fun `ids are e colon name for an exchange and q colon name for a queue or stream`() {
        val g = planPreview(planOf(queue("orders"), MigrationAction.DeclareStream(name = "audit-log"), exchange("events")), none)
        assertEquals(setOf("e:events", "q:orders", "q:audit-log"), g.ids())
        assertEquals(GraphNodeKind.Exchange, g.node("e:events").kind)
        assertEquals(GraphNodeKind.Queue, g.node("q:orders").kind)
        assertEquals(GraphNodeKind.Stream, g.node("q:audit-log").kind)
    }

    @Test
    fun `a declared queue without a binding is still drawn`() {
        val g = planPreview(planOf(queue("orders")), none)
        assertEquals(setOf("q:orders"), g.ids())
        assertEquals(emptyList(), g.graph.edges)
        assertEquals(setOf("q:orders"), g.newNodeIds)
    }

    @Test
    fun `an exchange of the broker that a binding touches is drawn and not new, the rest of the broker is not drawn`() {
        val known = KnownTopology(exchanges = mapOf("events" to "topic", "audit" to "direct"), queues = setOf("audit", "other"), streams = setOf("s"))
        val g = planPreview(planOf(queue("orders"), bind("events", "orders", "order.created")), known)
        assertEquals(setOf("e:events", "q:orders"), g.ids(), "audit, other and s are not touched by a binding of the plan")
        assertEquals(setOf("q:orders"), g.newNodeIds)
        assertEquals("exchange, topic", g.node("e:events").detail, "the existing exchange keeps the type the broker has, in the mockup's words")
        assertEquals(setOf("edge-0"), g.newEdgeIds, "the plan's binding is new even between an existing end and a new one")
        assertEquals(emptySet(), g.notInPlanNodeIds)
        assertNull(g.notice)
    }

    @Test
    fun `the broker's own bindings are never drawn`() {
        // KnownTopology holds names only, so there is nothing to draw an old binding from: the graph has exactly the plan's edges.
        val known = KnownTopology(exchanges = mapOf("events" to "direct"), queues = setOf("audit", "orders"))
        val g = planPreview(planOf(bind("events", "orders", "k")), known)
        assertEquals(1, g.graph.edges.size)
        assertEquals(setOf("e:events", "q:orders"), g.ids())
        assertEquals(emptySet(), g.newNodeIds, "both ends exist on the broker")
        assertEquals(setOf("edge-0"), g.newEdgeIds)
    }

    @Test
    fun `a name the plan declares and the broker already has is drawn as existing, not new`() {
        val g = planPreview(planOf(queue("orders"), exchange("events", "topic")), KnownTopology(exchanges = mapOf("events" to "direct"), queues = setOf("orders")))
        assertEquals(setOf("e:events", "q:orders"), g.ids())
        assertEquals(emptySet(), g.newNodeIds)
        assertEquals(PlanMark.Existing, g.markOf("q:orders"))
        assertEquals(PlanMark.Existing, g.markOf("e:events"))
    }

    @Test
    fun `a declared stream that the broker has, and a stream a binding ends in, are drawn as streams`() {
        val known = KnownTopology(streams = setOf("audit-log", "orders-stream"))
        val g = planPreview(planOf(MigrationAction.DeclareStream(name = "audit-log"), exchange("events"), bind("events", "orders-stream")), known)
        assertEquals(GraphNodeKind.Stream, g.node("q:audit-log").kind)
        assertEquals(GraphNodeKind.Stream, g.node("q:orders-stream").kind)
        assertEquals(PlanMark.Existing, g.markOf("q:audit-log"))
        assertEquals(PlanMark.Existing, g.markOf("q:orders-stream"))
        assertEquals(PlanMark.New, g.markOf("e:events"))
    }

    @Test
    fun `an end that neither the plan nor the broker has is not in this plan`() {
        val g = planPreview(planOf(queue("orders"), bind("events", "orders", "k")), none)
        assertEquals(setOf("e:events", "q:orders"), g.ids())
        assertEquals(setOf("e:events"), g.notInPlanNodeIds)
        assertEquals(setOf("q:orders"), g.newNodeIds)
        assertEquals(PlanMark.NotInPlan, g.markOf("e:events"))
        assertTrue(g.notInPlanNodeIds.intersect(g.newNodeIds).isEmpty())
        val both = planPreview(planOf(bind("events", "orders", "k")), none)
        assertEquals(setOf("e:events", "q:orders"), both.notInPlanNodeIds)
        assertEquals(GraphNodeKind.Queue, both.node("q:orders").kind, "an unknown destination is drawn as a queue")
    }

    @Test
    fun `a binding to an exchange draws the destination as an exchange`() {
        val plan = planOf(exchange("in"), exchange("out"), MigrationAction.DeclareBinding("in", "out", "k", destinationType = BindingDestinationType.EXCHANGE))
        val g = planPreview(plan, none)
        assertEquals(setOf("e:in", "e:out"), g.ids())
        assertEquals("e:out", g.graph.edges.single().edge.targetId)
    }

    @Test
    fun `no known gives only the plan, all new, nothing marked not in this plan, and the notice says why`() {
        val plan = planOf(queue("orders"), bind("events", "orders", "k"))
        val g = planPreview(plan, null)
        assertEquals(setOf("e:events", "q:orders"), g.ids())
        assertEquals(setOf("q:orders"), g.newNodeIds)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected), g.notice)
        assertEquals(setOf("e:events"), g.notInPlanNodeIds, "without a read, an end that the plan does not declare cannot be said to exist")
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), planPreview(plan, null, OnlyThePlanCause.BrokerNotRead).notice)
    }

    @Test
    fun `more than 60 nodes give the too large notice and no nodes, and 60 are still drawn`() {
        val over = planPreview(planOf(*(1..MAX_GRAPH_NODES + 1).map { queue("q$it") }.toTypedArray()), none)
        assertTrue(over.graph.isTooLarge)
        assertEquals(emptyList(), over.graph.nodes)
        assertEquals(PlanGraphNotice.TooLarge, over.notice)
        val at = planPreview(planOf(*(1..MAX_GRAPH_NODES).map { queue("q$it") }.toTypedArray()), none)
        assertFalse(at.graph.isTooLarge)
        assertEquals(MAX_GRAPH_NODES, at.graph.nodes.size)
        assertNull(at.notice)
    }

    @Test
    fun `too large wins over only the plan when both apply`() {
        val g = planPreview(planOf(*(1..MAX_GRAPH_NODES + 1).map { queue("q$it") }.toTypedArray()), null)
        assertEquals(PlanGraphNotice.TooLarge, g.notice)
    }

    @Test
    fun `removing a step removes its edge, and removing a declaration removes its node`() {
        val plan = planOf(queue("orders"), exchange("events"), bind("events", "orders", "k"))
        val bindingId = plan.steps.last().id
        val without = planPreview(plan.remove(bindingId), none)
        assertEquals(setOf("e:events", "q:orders"), without.ids())
        assertEquals(emptyList(), without.graph.edges)
        assertEquals(emptySet(), without.newEdgeIds)
        assertEquals(setOf("e:events"), planPreview(plan.remove(bindingId).remove(plan.steps.first().id), none).ids())
    }

    @Test
    fun `edge ids follow the order of the plan's bindings, not the order of all steps`() {
        val plan = planOf(queue("a"), bind("x", "a", "first"), queue("b"), bind("y", "b", "second"), bind("x", "b", "third"))
        val g = planPreview(plan, none)
        assertEquals(setOf("edge-0", "edge-1", "edge-2"), g.newEdgeIds)
        val byId = g.graph.edges.associate { it.edge.id to it.edge }
        assertEquals("first", byId.getValue("edge-0").routingKey)
        assertEquals("second", byId.getValue("edge-1").routingKey)
        assertEquals("third", byId.getValue("edge-2").routingKey)
        assertEquals("q:b", byId.getValue("edge-2").targetId)
    }

    @Test
    fun `the fields that later tasks fill are empty now`() {
        val g = planPreview(planOf(queue("orders"), bind("events", "orders", "k")), none)
        assertEquals(emptyList(), g.links)
        assertEquals(emptyMap(), g.edgeLabels)
        assertEquals(emptyList(), g.notes)
    }

    @Test
    fun `a plan without steps is an empty graph and says nothing`() {
        val g = planPreview(TopologyPlan(), none)
        assertEquals(emptyList(), g.graph.nodes)
        assertNull(g.notice)
        assertEquals(emptySet(), g.newNodeIds)
    }

    @Test
    fun `the accessible name says the kind, the name and the mark`() {
        val known = KnownTopology(exchanges = mapOf("events" to "direct"))
        val g = planPreview(planOf(queue("orders"), MigrationAction.DeclareStream(name = "audit-log"), bind("events", "orders"), bind("events", "ghost")), known)
        assertEquals("queue orders, new", g.accessibleName(g.node("q:orders")))
        assertEquals("stream audit-log, new", g.accessibleName(g.node("q:audit-log")))
        assertEquals("exchange events, already on the broker", g.accessibleName(g.node("e:events")))
        assertEquals("queue ghost, not in this plan", g.accessibleName(g.node("q:ghost")))
    }

    @Test
    fun `it is pure and cheap, the same input gives the same graph`() {
        val plan = planOf(queue("orders"), exchange("events"), bind("events", "orders", "k"))
        assertEquals(planPreview(plan, none), planPreview(plan, none))
    }

    @Test
    fun `M9-14c a stream with settings is still a stream node, drawn like any stream the plan declares`() {
        val withSettings = MigrationAction.DeclareStream(
            name = "audit-log",
            arguments = mapOf("x-max-age" to dev.caerbannog.core.json.ArgumentValue.Text("7D"), "x-queue-leader-locator" to dev.caerbannog.core.json.ArgumentValue.Text("balanced")),
        )
        val plain = planPreview(planOf(MigrationAction.DeclareStream(name = "audit-log")), none)
        val g = planPreview(planOf(withSettings), none)
        assertEquals(plain.ids(), g.ids(), "the settings do not change the picture")
        assertEquals(GraphNodeKind.Stream, g.node("q:audit-log").kind)
        assertEquals("Declare stream audit-log", withSettings.description)
    }
}
