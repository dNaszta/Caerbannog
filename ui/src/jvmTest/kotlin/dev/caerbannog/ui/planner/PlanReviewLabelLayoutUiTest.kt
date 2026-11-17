package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-10 round 2 (F1), RED first: the labels of an edge in the review graph. Acceptance of "Round 2 decision" in the brief, measured by
 * `positionInRoot` and `size` of the tags `edgePill:<key>`, `planGraphNew:edge-<i>`, `planGraphEdgeLabel:<id>`, `planGraphEdgeTag:<id>` (the labels of one
 * edge are one block), the node cards `planGraphNode:*`, the link labels `planGraphLink:*` and the inner graph box `planner:reviewGraphContent`
 * (a tag the fix adds on the review path only). One test per plan shape and window size, so a failure names the shape and the two boxes.
 * Checked per shape: no two label boxes of different edges intersect; no label box intersects a node card or a link label; the four elements of one
 * edge do not intersect; the number of drawn filters equals the number of routes; every label box lies inside `planner:reviewGraphContent` (0.5 dp).
 * Acceptance 3 (one route, one plain binding): top to bottom key pill, "new", filter, tag; each element centred on the edge's label x (1 dp).
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewLabelLayoutUiTest {
    private class Box(val name: String, val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
        override fun toString() = "$name [${l.toInt()},${t.toInt()} - ${r.toInt()},${b.toInt()}]"
    }

    private fun DesktopComposeUiTest.drawn(tag: String) = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.box(tag: String): Box = node(tag).fetchSemanticsNode().let {
        Box(tag, it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private val known = KnownTopology(queues = setOf("old-q"))

    private fun problems(width: Int, height: Int, routes: Int, prepare: UiRig.() -> Unit): List<String> {
        val found = mutableListOf<String>()
        runDesktopComposeUiTest(width, height) {
            val r = UiRig(reader = FakeKnownTopologyReader(known), prepare = prepare)
            showPlanner(r)
            val graph = r.state.graph.graph
            val byKey = graph.edges.associate { it.edge.routingKey to it.edge.id }
            // the four elements of each edge, by edge id
            val blocks = mutableMapOf<String, MutableList<Box>>()
            graph.edges.forEach { e ->
                val id = e.edge.id
                val tags = listOfNotNull(
                    "edgePill:${e.edge.routingKey}".takeIf { e.edge.routingKey.isNotEmpty() },
                    "planGraphNew:$id", "planGraphEdgeLabel:$id", "planGraphEdgeTag:$id",
                )
                blocks[id] = tags.filter { drawn(it) }.map { box(it) }.toMutableList()
            }
            val fixed = PlannerNodeTags.withPrefix(this, "planGraphNode:") + PlannerNodeTags.withPrefix(this, "planGraphLink:")
            val others = fixed.map { box(it) }
            val ids = blocks.keys.toList()
            for (i in ids.indices) {
                val own = blocks.getValue(ids[i])
                for (a in own.indices) for (b in a + 1 until own.size) if (own[a].hits(own[b])) found += "${ids[i]}: ${own[a]} overlaps ${own[b]} (inside one edge)"
                for (j in i + 1 until ids.size) for (a in own) for (b in blocks.getValue(ids[j])) if (a.hits(b)) found += "${ids[i]} vs ${ids[j]}: $a overlaps $b"
                for (a in own) for (o in others) if (a.hits(o)) found += "${ids[i]}: $a overlaps $o"
            }
            val filters = PlannerNodeTags.withPrefix(this, "planGraphEdgeLabel:").size
            if (filters != routes) found += "$filters filters drawn for $routes routes"
            if (!drawn("planner:reviewGraphContent")) {
                found += "planner:reviewGraphContent is not drawn"
            } else {
                val content = box("planner:reviewGraphContent")
                blocks.values.flatten().forEach { b ->
                    if (b.l < content.l - 0.5f || b.t < content.t - 0.5f || b.r > content.r + 0.5f || b.b > content.b + 0.5f) found += "$b is outside $content"
                }
            }
            check(byKey.isNotEmpty()) { "the plan drew no edge" }
            r.close()
        }
        return found
    }

    @Test
    fun `TwoRoutesIntoOneStream at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 2) { stream("telemetry"); (1..2).forEach { mqttRoute("sensors/room$it/+", "telemetry", MqttDestinationKind.Stream) } })
    }

    @Test
    fun `TwoRoutesIntoOneStream at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 2) { stream("telemetry"); (1..2).forEach { mqttRoute("sensors/room$it/+", "telemetry", MqttDestinationKind.Stream) } })
    }

    @Test
    fun `FiveRoutesIntoOneStream at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 5) { stream("telemetry"); (1..5).forEach { mqttRoute("sensors/room$it/+", "telemetry", MqttDestinationKind.Stream) } })
    }

    @Test
    fun `FiveRoutesIntoOneStream at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 5) { stream("telemetry"); (1..5).forEach { mqttRoute("sensors/room$it/+", "telemetry", MqttDestinationKind.Stream) } })
    }

    @Test
    fun `TwoPlainBindingsIntoOneStream at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 0) { exchange("events", "topic"); stream("telemetry"); (1..2).forEach { binding("events", "telemetry", "sensors.room$it.*") } })
    }

    @Test
    fun `TwoPlainBindingsIntoOneStream at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 0) { exchange("events", "topic"); stream("telemetry"); (1..2).forEach { binding("events", "telemetry", "sensors.room$it.*") } })
    }

    @Test
    fun `FivePlainBindingsIntoOneStream at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 0) { exchange("events", "topic"); stream("telemetry"); (1..5).forEach { binding("events", "telemetry", "sensors.room$it.*") } })
    }

    @Test
    fun `FivePlainBindingsIntoOneStream at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 0) { exchange("events", "topic"); stream("telemetry"); (1..5).forEach { binding("events", "telemetry", "sensors.room$it.*") } })
    }

    @Test
    fun `FiveRoutesIntoFourDestinations at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 5) { stream("s1"); queue("q1"); mqttRoute("a/#", "s1"); mqttRoute("b/#", "q1"); mqttRoute("c/#", "old-q"); mqttRoute("d/+", "ghost"); mqttRoute("e/#", "q1") })
    }

    @Test
    fun `FiveRoutesIntoFourDestinations at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 5) { stream("s1"); queue("q1"); mqttRoute("a/#", "s1"); mqttRoute("b/#", "q1"); mqttRoute("c/#", "old-q"); mqttRoute("d/+", "ghost"); mqttRoute("e/#", "q1") })
    }

    @Test
    fun `TwoRoutesWithAStreamPair at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 2) { stream("telemetry", withResponse = true); (1..2).forEach { mqttRoute("sensors/room$it/+", if (it % 2 == 0) "telemetry-dln" else "telemetry") } })
    }

    @Test
    fun `TwoRoutesWithAStreamPair at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 2) { stream("telemetry", withResponse = true); (1..2).forEach { mqttRoute("sensors/room$it/+", if (it % 2 == 0) "telemetry-dln" else "telemetry") } })
    }

    @Test
    fun `FiveRoutesWithAStreamPair at 1280 x 800`() {
        assertEquals(emptyList(), problems(1280, 800, routes = 5) { stream("telemetry", withResponse = true); (1..5).forEach { mqttRoute("sensors/room$it/+", if (it % 2 == 0) "telemetry-dln" else "telemetry") } })
    }

    @Test
    fun `FiveRoutesWithAStreamPair at 1024 x 768`() {
        assertEquals(emptyList(), problems(1024, 768, routes = 5) { stream("telemetry", withResponse = true); (1..5).forEach { mqttRoute("sensors/room$it/+", if (it % 2 == 0) "telemetry-dln" else "telemetry") } })
    }

    // ---- acceptance 3: the order and the centre ----

    private fun DesktopComposeUiTest.checkBlock(r: UiRig, keyTag: String, id: String, withRoute: Boolean): List<String> {
        val found = mutableListOf<String>()
        val content = box("planner:reviewGraphContent")
        val g = r.state.graph.graph
        val shift = (g.nodes.minOfOrNull { it.x } ?: 24f) - 24f
        val labelX = g.edges.single { it.edge.id == id }.labelX - shift
        val order = listOfNotNull(keyTag, "planGraphNew:$id", "planGraphEdgeLabel:$id".takeIf { withRoute }, "planGraphEdgeTag:$id".takeIf { withRoute }).map { box(it) }
        for (i in 0 until order.size - 1) if (order[i].t >= order[i + 1].t) found += "${order[i]} is not above ${order[i + 1]}"
        order.forEach { b ->
            val centre = (b.l + b.r) / 2f - content.l
            if (abs(centre - labelX) > 1f) found += "$b is centred at $centre, the label x is $labelX"
        }
        return found
    }

    @Test
    fun `one route is a block of key pill, new, filter and tag, centred on the label x`() {
        val found = mutableListOf<String>()
        runDesktopComposeUiTest(1280, 800) {
            val r = UiRig(reader = FakeKnownTopologyReader(known)) { stream("telemetry"); mqttRoute("sensors/+/temp", "telemetry", MqttDestinationKind.Stream) }
            showPlanner(r)
            if (!drawn("planner:reviewGraphContent")) found += "planner:reviewGraphContent is not drawn" else found += checkBlock(r, "edgePill:sensors.*.temp", "edge-0", true)
            r.close()
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `one plain binding is a block of key pill and new, centred on the label x`() {
        val found = mutableListOf<String>()
        runDesktopComposeUiTest(1280, 800) {
            val r = UiRig(reader = FakeKnownTopologyReader(known)) { exchange("events", "topic"); stream("telemetry"); binding("events", "telemetry", "sensors.room1.*") }
            showPlanner(r)
            if (!drawn("planner:reviewGraphContent")) found += "planner:reviewGraphContent is not drawn" else found += checkBlock(r, "edgePill:sensors.room1.*", "edge-0", false)
            r.close()
        }
        assertEquals(emptyList(), found)
    }
}
