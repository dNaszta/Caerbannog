package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-07b step 2: the review graph of the owner's request and answer flow, built the way the wizard builds it (the stream form with the answers tick).
 * The edge from `amq.topic` to the response stream is an ordinary labelled MQTT route; the note sits between the request stream and `amq.topic`.
 */
class PlanPreviewDeviceFlowTest {
    private val sentence = "Your own service turns a request into an MQTT message; the planner does not plan that."
    private fun graphOf(form: StreamForm, known: KnownTopology? = KnownTopology.None) = planPreview(planOf(*form.actions(ctx()).toTypedArray()), known)

    @Test
    fun `the answers route is an edge from amq topic to the response stream labelled with the filter, and the note sits between the two nodes`() {
        val g = graphOf(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        val edge = g.graph.edges.single().edge
        assertEquals("e:amq.topic", edge.sourceId)
        assertEquals("q:device-commands-dln", edge.targetId)
        assertEquals(mapOf(edge.id to "devices/+/answer"), g.edgeLabels)
        assertEquals(setOf(edge.id), g.mqttRouteEdgeIds)
        assertEquals(listOf(PlanGraphNote("q:device-commands", "e:amq.topic", sentence)), g.notes)
        assertEquals(listOf(PlanLink("q:device-commands", "q:device-commands-dln", PlanLinkKind.ResponseStream)), g.links)
        assertEquals(setOf("e:amq.topic", "q:device-commands", "q:device-commands-dln"), g.graph.nodes.map { it.node.id }.toSet())
    }

    @Test
    fun `the requests route alone draws its edge into the request stream and no note`() {
        val g = graphOf(StreamForm("device-commands", withResponse = true, requestsTopic = "commands/#"))
        val edge = g.graph.edges.single().edge
        assertEquals("q:device-commands", edge.targetId)
        assertEquals(mapOf(edge.id to "commands/#"), g.edgeLabels)
        assertEquals(emptyList(), g.notes)
    }

    @Test
    fun `both ticks draw two edges and one note`() {
        val g = graphOf(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer", requestsTopic = "commands/#"))
        assertEquals(2, g.graph.edges.size)
        assertEquals(setOf("devices/+/answer", "commands/#"), g.edgeLabels.values.toSet())
        assertEquals(1, g.notes.size)
    }

    @Test
    fun `the note is drawn with the broker read and not read, and while it is being read`() {
        val form = StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer")
        listOf<KnownTopology?>(null, KnownTopology.None, KnownTopology(exchanges = mapOf("amq.topic" to "topic"))).forEach {
            assertEquals(1, graphOf(form, it).notes.size, "known = $it")
        }
        val pending = planPreview(planOf(*form.actions(ctx()).toTypedArray()), null, pending = true)
        assertEquals(1, pending.notes.size)
    }
}
