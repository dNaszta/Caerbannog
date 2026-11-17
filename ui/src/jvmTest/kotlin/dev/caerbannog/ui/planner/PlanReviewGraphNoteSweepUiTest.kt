package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07b step 4 (test developer): a sweep over graph shapes. In every shape, on two window sizes, no note box overlaps a node, an edge label block
 * (the filter text, the "MQTT route" tag, the "new" pill, the routing key pill) or another note, and every note is drawn. A kill test for a note that
 * ignores the labels (`placeNotes` without `labelRects`, `labelRectOf` too narrow): the unit test of the layout pins the function, this one pins what the
 * user sees. The Topology graph (no plan) never draws a note.
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphNoteSweepUiTest {
    private fun UiRig.pair(name: String, vararg filters: String) {
        stream(name, withResponse = true)
        filters.forEach { mqttRoute(it, "$name-dln", MqttDestinationKind.Stream) }
    }

    private val shapes: Map<String, UiRig.() -> Unit> = mapOf(
        "one pair" to { pair("p1", "devices/+/answer") },
        "pair with both routes" to {
            stream("p1", withResponse = true)
            mqttRoute("devices/+/answer", "p1-dln", MqttDestinationKind.Stream)
            mqttRoute("commands/#", "p1", MqttDestinationKind.Stream)
        },
        "three pairs" to { (1..3).forEach { pair("p$it", "dev$it/+/answer") } },
        "five pairs, each with two routes" to { (1..5).forEach { pair("p$it", "dev$it/+/answer", "alt$it/#") } },
        "one pair and twenty routes into its response stream" to { pair("p1", *Array(20) { "d$it/+/answer" }) },
        "pair with a route into a queue and the request stream" to {
            queue("alarms")
            pair("p1", "devices/+/answer")
            mqttRoute("alarms/#", "alarms", MqttDestinationKind.Queue)
            mqttRoute("cmd/#", "p1", MqttDestinationKind.Stream)
        },
        "same column: an exchange bound to amq.topic" to {
            exchange("events")
            add(BindingForm("events", "amq.topic", BindingDestinationType.EXCHANGE, "k"))
            pair("p1", "devices/+/answer")
            pair("p2", "other/+/answer")
        },
        "a key on a binding next to the pair" to {
            exchange("events")
            binding("events", "p1-dln", "answer.key")
            pair("p1", "devices/+/answer")
        },
    )

    private fun DesktopComposeUiTest.rect(tag: String) = node(tag).fetchSemanticsNode().let {
        floatArrayOf(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private fun overlap(a: FloatArray, b: FloatArray) = a[0] < b[2] - 1f && b[0] < a[2] - 1f && a[1] < b[3] - 1f && b[1] < a[3] - 1f

    @Test
    fun `no note covers a node, a label block or another note in any shape`() {
        val problems = mutableListOf<String>()
        for ((shape, prepare) in shapes) for ((w, h) in listOf(1280 to 800, 1024 to 768)) runDesktopComposeUiTest(w, h) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None), prepare = prepare)
            showPlanner(r)
            val expected = r.state.graph.notes.size
            val notes = PlannerNodeTags.withPrefix(this, "planGraphNote:")
            if (notes.size != expected) problems += "$shape ${w}x$h: ${notes.size} notes drawn, ${r.state.graph.notes.size} expected"
            val others = listOf("planGraphNode:", "planGraphEdgeLabel:", "planGraphEdgeTag:", "edgePill:", "planGraphNew:", "planGraphLink:")
                .flatMap { PlannerNodeTags.withPrefix(this, it) }.map { it to rect(it) }
            for (n in notes) {
                val nr = rect(n)
                others.filter { overlap(nr, it.second) }.forEach { problems += "$shape ${w}x$h: $n ${nr.toList()} covers ${it.first} ${it.second.toList()}" }
                notes.filter { it > n }.filter { overlap(nr, rect(it)) }.forEach { problems += "$shape ${w}x$h: $n covers $it" }
            }
            r.close()
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the Topology graph never draws a note`() = runDesktopComposeUiTest(1280, 800) {
        // The plan path is the only one that has notes: a plan without a pair draws none, and the shapes above show that a pair does.
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { exchange("events"); queue("orders"); binding("events", "orders", "k") }
        showPlanner(r)
        assertTrue(PlannerNodeTags.withPrefix(this, "planGraphNote:").isEmpty())
        r.close()
    }

    @Test
    fun `the estimate of a label's width that keeps the note clear is not narrower than the label drawn`() {
        val short = mutableListOf<String>()
        for (length in listOf(3, 12, 20, 30, 60)) runDesktopComposeUiTest(1600, 1000) {
            val filter = "a".repeat(length)
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { pair("p1", filter) }
            showPlanner(r)
            val edge = r.state.graph.graph.edges.single().edge.id
            val drawn = rect("planGraphEdgeLabel:$edge").let { it[2] - it[0] }
            val estimate = dev.caerbannog.ui.topology.labelRectOf(0f, 0f, 10f, maxOf(length, "MQTT route".length)).let { it.right - it.left }
            if (estimate < drawn - 1f) short += "a filter of $length characters is drawn $drawn px wide and estimated $estimate"
            r.close()
        }
        assertEquals(emptyList(), short)
    }

    @Test
    fun `in the same column case the note stays next to the request stream and covers nothing whatever the length of the filter`() {
        val problems = mutableListOf<String>()
        for (length in listOf(8, 16, 17, 24, 40)) runDesktopComposeUiTest(1600, 1000) {
            val filter = "d".repeat(length - 6) + "/+/ans"
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
                exchange("events")
                add(BindingForm("events", "amq.topic", BindingDestinationType.EXCHANGE, "k"))
                pair("p1", filter)
            }
            showPlanner(r)
            val note = rect("planGraphNote:0")
            val request = rect("planGraphNode:q:p1")
            val topic = rect("planGraphNode:e:amq.topic")
            val where = "a filter of $length characters"
            if (!(note[0] < request[2] && request[0] < note[2])) problems += "$where: the note ${note.toList()} is not over the column of the request stream ${request.toList()}"
            val distance = maxOf(0f, note[1] - request[3], request[1] - note[3])
            if (distance > 40f) problems += "$where: the note is $distance px from the request stream (at most 40)"
            for ((name, other) in listOf("the request stream" to request, "amq.topic" to topic) +
                (PlannerNodeTags.withPrefix(this, "planGraphNode:") + PlannerNodeTags.withPrefix(this, "planGraphEdgeLabel:") + PlannerNodeTags.withPrefix(this, "planGraphEdgeTag:") +
                    PlannerNodeTags.withPrefix(this, "edgePill:")).map { it to rect(it) }) {
                if (overlap(note, other)) problems += "$where: the note covers $name"
            }
            r.close()
        }
        assertEquals(emptyList(), problems)
    }

    private fun DesktopComposeUiTest.displayed(tag: String) = runCatching { node(tag).assertIsDisplayed() }.isSuccess

    private fun DesktopComposeUiTest.buttons() = listOf("planWizard:cancel", "planWizard:back", "planWizard:next").associateWith { displayed(it) }

    private fun DesktopComposeUiTest.openStreamForm(ticks: Boolean) {
        showPlanner(UiRig().also { rigs += it })
        node("planner:addStepBtn").performClick()
        runCatching { node("planWizard:kind:Stream").performScrollTo() }
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performScrollTo().performClick()
        if (ticks) {
            node("planForm:answersOverMqtt").performScrollTo().performClick()
            node("planForm:answersTopic").performScrollTo().performTextInput("devices/+/answer")
            node("planForm:requestsOverMqtt").performScrollTo().performClick()
            node("planForm:requestsTopic").performScrollTo().performTextInput("commands/#")
        }
    }

    private val rigs = mutableListOf<UiRig>()

    @Test
    fun `the form with both ticks keeps the buttons on a 1000 x 600 window and loses none more than the plain pair form at 1000 x 480`() {
        runDesktopComposeUiTest(1000, 600) {
            openStreamForm(ticks = true)
            assertEquals(mapOf("planWizard:cancel" to true, "planWizard:back" to true, "planWizard:next" to true), buttons())
        }
        var plain = emptyMap<String, Boolean>()
        runDesktopComposeUiTest(1000, 480) { openStreamForm(ticks = false); plain = buttons() }
        runDesktopComposeUiTest(1000, 480) {
            openStreamForm(ticks = true)
            val withTicks = buttons()
            assertEquals(plain.filterValues { it }.keys, withTicks.filterValues { it }.keys, "buttons on screen at 480: plain pair $plain, with ticks $withTicks")
        }
        rigs.forEach { it.close() }
    }
}
