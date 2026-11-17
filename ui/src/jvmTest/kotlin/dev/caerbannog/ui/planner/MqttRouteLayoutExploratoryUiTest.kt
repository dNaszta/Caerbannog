package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10 step 4 (test developer): the labelled edge of a route in the review graph with 2 and 5 routes on close edges, into one stream, into a stream
 * pair (M9-07), at 1024, 1280 and 1600 px wide: the filter and "MQTT route" must not sit on a key pill, a "new" pill, another route's label, a link
 * label or a node. And the wizard of the new kind on short windows (1000 x 600 and 1000 x 480), with the Stream kind at the same size as the control.
 */
@OptIn(ExperimentalTestApi::class)
class MqttRouteLayoutExploratoryUiTest {
    private fun DesktopComposeUiTest.rect(tag: String) = node(tag).fetchSemanticsNode().let {
        floatArrayOf(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private fun overlap(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun DesktopComposeUiTest.problems(where: String): List<String> {
        val labels = PlannerNodeTags.withPrefix(this, "planGraphEdgeLabel:") + PlannerNodeTags.withPrefix(this, "planGraphEdgeTag:")
        val others = PlannerNodeTags.withPrefix(this, "edgePill:") + PlannerNodeTags.withPrefix(this, "planGraphNew:edge-") +
            PlannerNodeTags.withPrefix(this, "planGraphLink:") + PlannerNodeTags.withPrefix(this, "planGraphNode:")
        val out = mutableListOf<String>()
        for (l in labels) for (o in others) if (overlap(rect(l), rect(o))) out += "$where: $l overlaps $o"
        val groups = labels.groupBy { it.substringAfter(':') }
        val ids = groups.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) for (a in groups.getValue(ids[i])) for (b in groups.getValue(ids[j])) {
            if (overlap(rect(a), rect(b))) out += "$where: $a overlaps $b"
        }
        return out
    }

    @Test
    fun `route labels do not overlap anything, for 2 and 5 routes, into a stream and into a pair, at three widths`() {
        val found = mutableListOf<String>()
        for (width in listOf(1024, 1280, 1600)) for (routes in listOf(1, 2, 5)) for (pair in listOf(false, true)) {
            runDesktopComposeUiTest(width, 900) {
                val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
                    stream("telemetry", withResponse = pair)
                    (1..routes).forEach { mqttRoute("sensors/room$it/+", if (pair && it % 2 == 0) "telemetry-dln" else "telemetry", MqttDestinationKind.Stream) }
                }
                showPlanner(r)
                val where = "$width px, $routes routes, pair=$pair"
                val labelCount = PlannerNodeTags.withPrefix(this, "planGraphEdgeLabel:").size
                if (labelCount != routes) found += "$where: $labelCount labels drawn"
                found += problems(where)
                r.close()
            }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `route labels do not overlap when the routes lead to several destinations and one is an existing queue`() {
        val found = mutableListOf<String>()
        for (width in listOf(1024, 1280)) runDesktopComposeUiTest(width, 900) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology(queues = setOf("old-q")))) {
                stream("s1"); queue("q1")
                mqttRoute("a/#", "s1"); mqttRoute("b/#", "q1"); mqttRoute("c/#", "old-q"); mqttRoute("d/+", "ghost"); mqttRoute("e/#", "q1")
            }
            showPlanner(r)
            found += problems("$width px, 5 routes, 4 destinations")
            r.close()
        }
        assertEquals(emptyList(), found)
    }

    private fun DesktopComposeUiTest.nodeSays(id: String) =
        node("planGraphNode:$id").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString(" ")

    @Test
    fun `how amq topic reads in the graph for a route, by what the planner knows of the broker`() {
        // Round 2 (finding F2, decided as a general rule): a built-in amq.* exchange that the plan does not declare is existing. At step 4 this read "not in this plan" for a hand-made list or no read:
        // is true but reads as a defect for a route when the broker was read and does not list it (a hand-made KnownTopology) or when nothing was read.
        runDesktopComposeUiTest(1280, 800) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology(exchanges = mapOf("amq.topic" to "topic")))) { stream("telemetry"); mqttRoute("a/#", "telemetry") }
            showPlanner(r)
            assertEquals("exchange amq.topic, already on the broker", nodeSays("e:amq.topic"))
            r.close()
        }
        runDesktopComposeUiTest(1280, 800) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology(exchanges = mapOf("other" to "topic")))) { stream("telemetry"); mqttRoute("a/#", "telemetry") }
            showPlanner(r)
            assertEquals("exchange amq.topic, already on the broker", nodeSays("e:amq.topic"))
            r.close()
        }
        runDesktopComposeUiTest(1280, 800) {
            val r = UiRig { stream("telemetry"); mqttRoute("a/#", "telemetry") }
            showPlanner(r)
            assertEquals("exchange amq.topic, already on the broker", nodeSays("e:amq.topic"))
            r.close()
        }
    }

    // ---- the wizard on short windows ----

    private fun DesktopComposeUiTest.drawn(tag: String) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** What the wizard draws on page 1 and on the form for [kind] in a window of [h] dp, as words. */
    private fun wizardAt(h: Int, kind: String): List<String> {
        val seen = mutableListOf<String>()
        runDesktopComposeUiTest(1000, h) {
            val r = UiRig()
            showPlanner(r)
            node("planner:addStepBtn").performClick()
            seen += "page 1 Next drawn=${drawn("planWizard:next")}"
            if (drawn("planWizard:kind:$kind")) {
                node("planWizard:kind:$kind").performClick()
                if (drawn("planWizard:next")) node("planWizard:next").performClick()
            }
            seen += "form Next drawn=${drawn("planWizard:next")}"
            seen += "form Cancel drawn=${drawn("planWizard:cancel")}"
            r.close()
        }
        return seen
    }

    @Test
    fun `on short windows the MQTT route wizard is no worse than the Stream wizard, which is the control`() {
        // Measured at step 4: at 1000 x 480 BOTH forms lose their Next button (pre-existing for Stream, M9-07; finding F4, minor); at 1000 x 600 both keep it.
        for (h in listOf(600, 480)) assertEquals(wizardAt(h, "Stream"), wizardAt(h, "MQTT route"), "window 1000 x $h")
        assertEquals(listOf("page 1 Next drawn=true", "form Next drawn=true", "form Cancel drawn=true"), wizardAt(600, "MQTT route"))
    }
}
