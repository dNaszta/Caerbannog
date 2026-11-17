package dev.caerbannog.ui.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07b step 2: the note of the review graph is drawn and read, and is nothing else (decision 6). Tag fixed here: `planGraphNote:<index>` (0-based, in the
 * order of `PlanGraph.notes`), text = `PlannerTexts.SERVICE_NOT_PLANNED`. The box is placed between the two nodes, has no edge, no click and no focus action.
 * The graph of the Topology screen passes no notes: its twelve screenshots must stay byte identical (checked with `:ui:screenshotTest --tests '*Topology*'`).
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphNoteUiTest {
    private val NOTE_NEAR = 40f

    private val sentence = "Your own service turns a request into an MQTT message; the planner does not plan that."

    private fun rig(prepare: UiRig.() -> Unit) = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None), prepare = prepare)

    private fun UiRig.deviceFlow() {
        stream("device-commands", withResponse = true)
        mqttRoute("devices/+/answer", "device-commands-dln", MqttDestinationKind.Stream)
    }

    private fun DesktopComposeUiTest.rect(tag: String) = node(tag).fetchSemanticsNode().let {
        floatArrayOf(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private fun DesktopComposeUiTest.notes() = PlannerNodeTags.withPrefix(this, "planGraphNote:")

    private fun DesktopComposeUiTest.actionsUnder(tag: String): List<String> {
        val acting = listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetText, SemanticsActions.RequestFocus)
        fun walk(n: SemanticsNode): List<String> =
            acting.filter { n.config.contains(it) }.map { "${it.name} on ${n.config.getOrNull(SemanticsProperties.TestTag) ?: "an untagged node"}" } + n.children.flatMap { walk(it) }
        return walk(node(tag).fetchSemanticsNode())
    }

    @Test
    fun `the note is drawn with the sentence and can be read`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { deviceFlow() }
        showPlanner(r)
        assertEquals(listOf("planGraphNote:0"), notes())
        assertEquals(sentence, textOf("planGraphNote:0"))
        assertEquals(PlannerTexts.SERVICE_NOT_PLANNED, textOf("planGraphNote:0"))
        val n = node("planGraphNote:0").fetchSemanticsNode()
        val said = n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } } +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue(said.any { it.contains(sentence) }, "a screen reader would not read the sentence: $said")
        r.close()
    }

    @Test
    fun `the note sits between the request stream and amq topic, on neither of them and on no routing pill`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { deviceFlow() }
        showPlanner(r)
        val note = rect("planGraphNote:0")
        val request = rect("planGraphNode:q:device-commands")
        val topic = rect("planGraphNode:e:amq.topic")
        val others = listOf(request, topic, rect("planGraphNode:q:device-commands-dln")) + PlannerNodeTags.withPrefix(this, "edgePill:").map { rect(it) }
        others.forEach { o -> assertTrue(note[2] <= o[0] || o[2] <= note[0] || note[3] <= o[1] || o[3] <= note[1], "the note ${note.toList()} overlaps ${o.toList()}") }
        val cx = (note[0] + note[2]) / 2
        val cy = (note[1] + note[3]) / 2
        val slack = 24f
        val xs = listOf((request[0] + request[2]) / 2, (topic[0] + topic[2]) / 2)
        val ys = listOf((request[1] + request[3]) / 2, (topic[1] + topic[3]) / 2)
        assertTrue(cx >= xs.min() - slack && cx <= xs.max() + slack, "the note's centre x $cx is not between the two nodes $xs")
        assertTrue(cy >= ys.min() - slack && cy <= ys.max() + slack, "the note's centre y $cy is not between the two nodes $ys")
        r.close()
    }

    @Test
    fun `the note adds no edge to the graph, only the route's own`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { deviceFlow() }
        showPlanner(r)
        assertEquals(1, r.state.graph.graph.edges.size)
        assertDrawn("planGraphNew:edge-0")
        assertNotDrawn("planGraphNew:edge-1")
        assertEquals(listOf("planGraphEdgeLabel:edge-0"), PlannerNodeTags.withPrefix(this, "planGraphEdgeLabel:"))
        assertEquals("devices/+/answer", textOf("planGraphEdgeLabel:edge-0"))
        r.close()
    }

    @Test
    fun `the note has no click, no long click and no focus action, and clicking it reaches nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { deviceFlow() }
        showPlanner(r)
        assertEquals(emptyList(), actionsUnder("planGraphNote:0"), "the note offers an action")
        val before = r.state.plan
        val calls = r.events.calls.size
        node("planGraphNote:0").performClick()
        waitForIdle()
        assertEquals(before, r.state.plan)
        assertEquals(calls, r.events.calls.size, "a click on the note reached a planner event: ${r.events.calls.drop(calls)}")
        r.close()
    }

    @Test
    fun `the check on the note would see a click or a focus action if there were one`() = runDesktopComposeUiTest(800, 600) {
        setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("planGraphNote:clickable").clickable { }) { Text("x") }
                Box(Modifier.size(40.dp).testTag("planGraphNote:focusable").focusable()) { Text("y") }
            }
        }
        waitForIdle()
        assertTrue(actionsUnder("planGraphNote:clickable").isNotEmpty(), "the probe would not see a click")
        assertTrue(actionsUnder("planGraphNote:focusable").isNotEmpty(), "the probe would not see a focus action")
    }

    @Test
    fun `no note without a route, for a route into the request stream, or into a stream that is not a response stream`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig {
            stream("device-commands", withResponse = true)
            mqttRoute("commands/#", "device-commands", MqttDestinationKind.Stream)
            stream("telemetry")
            mqttRoute("sensors/#", "telemetry", MqttDestinationKind.Stream)
        }
        showPlanner(r)
        assertDrawn("planGraphNode:q:device-commands") // the graph is there, so "no note" is not "no graph"
        assertEquals(emptyList(), notes())
        r.close()
    }

    @Test
    fun `two routes into one response stream draw one note, two pairs draw two`() = runDesktopComposeUiTest(1600, 1000) {
        val r = rig {
            stream("a", withResponse = true)
            mqttRoute("x/+/y", "a-dln", MqttDestinationKind.Stream)
            mqttRoute("z/#", "a-dln", MqttDestinationKind.Stream)
        }
        showPlanner(r)
        assertEquals(listOf("planGraphNote:0"), notes())
        r.close()
        runDesktopComposeUiTest(1600, 1000) {
            val r2 = rig {
                stream("a", withResponse = true); stream("b", withResponse = true)
                mqttRoute("x/+/y", "a-dln", MqttDestinationKind.Stream); mqttRoute("u/+/v", "b-dln", MqttDestinationKind.Stream)
            }
            showPlanner(r2)
            assertEquals(listOf("planGraphNote:0", "planGraphNote:1"), notes().sorted())
            val one = rect("planGraphNote:0")
            val two = rect("planGraphNote:1")
            assertTrue(one[2] <= two[0] || two[2] <= one[0] || one[3] <= two[1] || two[3] <= one[1], "the two notes overlap: ${one.toList()} ${two.toList()}")
            r2.close()
        }
    }

    @Test
    fun `removing the route takes the note away`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { deviceFlow() }
        showPlanner(r)
        assertEquals(1, notes().size)
        node("planStepMenu:2").performClick()
        node("planStepMenu:2:remove").performClick()
        waitForIdle()
        assertEquals(emptyList(), notes())
        r.close()
    }

    @Test
    fun `the note is drawn by the review graph when only the plan is drawn, with no broker read`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { deviceFlow() }
        showPlanner(r)
        assertDrawn("planner:graphNotice")
        assertEquals(1, notes().size)
        r.close()
    }

    @Test
    fun `at 1024 and on a short window the note is still displayed in full`() {
        for ((w, h) in listOf(1024 to 768, 1280 to 480)) runDesktopComposeUiTest(w, h) {
            val r = rig { deviceFlow() }
            showPlanner(r)
            val note = rect("planGraphNote:0")
            val graph = rect("planner:reviewGraph")
            assertTrue(note[2] - note[0] > 100f && note[3] - note[1] > 20f, "the note box is too small to hold the sentence: ${note.toList()}")
            assertTrue(note[0] >= graph[0] - 1f && note[1] >= graph[1] - 1f, "the note starts outside the graph card at ${w}x$h")
            r.close()
        }
    }

    // ---- step 2 addendum (architect's gate, decision 3a) ----

    /**
     * The same-column case (decision of step 5): the note stands next to the request stream and covers nothing. It overlaps the column of the request
     * stream horizontally, is at most [NOTE_NEAR] dp (measured) from that node vertically, and overlaps no node, pill, label or other note.
     */
    private fun DesktopComposeUiTest.assertNoteNextToRequestAndFree(request: String, topic: String, others: List<String>) {
        val note = rect("planGraphNote:0")
        val a = rect("planGraphNode:q:$request")
        val b = rect("planGraphNode:e:$topic")
        (listOf(a, b) + others.map { rect(it) } + PlannerNodeTags.withPrefix(this, "edgePill:").map { rect(it) }).forEach { o ->
            assertTrue(note[2] <= o[0] || o[2] <= note[0] || note[3] <= o[1] || o[3] <= note[1], "the note ${note.toList()} overlaps ${o.toList()}")
        }
        assertTrue(note[0] < a[2] && a[0] < note[2], "the note ${note.toList()} is not over the column of the request stream ${a.toList()}")
        val distance = maxOf(0f, note[1] - a[3], a[1] - note[3])
        assertTrue(distance <= NOTE_NEAR, "the note is $distance px from the request stream, more than $NOTE_NEAR")
    }

    @Test
    fun `the same column case, an exchange bound to amq topic, puts the note next to the request stream and covers nothing`() = runDesktopComposeUiTest(1600, 1000) {
        val r = rig {
            exchange("events")
            add(dev.caerbannog.core.planner.BindingForm("events", "amq.topic", dev.caerbannog.core.topology.BindingDestinationType.EXCHANGE, "k"))
            deviceFlow()
        }
        showPlanner(r)
        assertDrawn("planGraphNode:e:amq.topic")
        assertEquals(listOf("planGraphNote:0"), notes())
        // the precondition of the case: amq.topic and the request stream share a column (same left edge)
        assertEquals(rect("planGraphNode:e:amq.topic")[0], rect("planGraphNode:q:device-commands")[0], "this is not the same column case")
        assertNoteNextToRequestAndFree("device-commands", "amq.topic", listOf("planGraphNode:q:device-commands-dln", "planGraphNode:e:events"))
        r.close()
    }

    @Test
    fun `a graph with no note keeps the 140 dp spacing between its layers and a note widens it to 244 dp`() = runDesktopComposeUiTest(1600, 1000) {
        val r = rig { queue("orders"); exchange("events"); binding("events", "orders", "order.created") }
        showPlanner(r)
        val events = rect("planGraphNode:e:events")
        val orders = rect("planGraphNode:q:orders")
        val scale = (events[2] - events[0]) / 200f
        assertEquals(140f * scale, orders[0] - events[2], 1f, "the gap between the two layers of a plan with no note")
        r.close()
        runDesktopComposeUiTest(1600, 1000) {
            val r2 = rig { deviceFlow() }
            showPlanner(r2)
            val topic = rect("planGraphNode:e:amq.topic")
            val request = rect("planGraphNode:q:device-commands")
            val sc = (topic[2] - topic[0]) / 200f
            assertEquals(244f * sc, request[0] - topic[2], 1f, "the gap between the two columns that hold the note's nodes")
            r2.close()
        }
    }
}
