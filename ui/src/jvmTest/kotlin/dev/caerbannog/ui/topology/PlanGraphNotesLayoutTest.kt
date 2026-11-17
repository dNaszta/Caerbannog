package dev.caerbannog.ui.topology

import dev.caerbannog.core.planner.PlanGraphNote
import dev.caerbannog.core.topology.GraphEdge
import dev.caerbannog.core.topology.GraphNode
import dev.caerbannog.core.topology.GraphNodeKind
import dev.caerbannog.core.topology.NODE_HEIGHT
import dev.caerbannog.core.topology.NODE_WIDTH
import dev.caerbannog.core.topology.PositionedEdge
import dev.caerbannog.core.topology.PositionedNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-07b: the pure layout of the review graph note, [makeRoomForNotes] and [placeNotes]. */
class PlanGraphNotesLayoutTest {
    private fun node(id: String, x: Float, y: Float) =
        PositionedNode(GraphNode(id, id.drop(2), if (id.startsWith("e:")) GraphNodeKind.Exchange else GraphNodeKind.Stream, "", 0), 0, 0, x, y)

    private fun edge(from: PositionedNode, to: PositionedNode) = PositionedEdge(
        GraphEdge("edge", from.node.id, to.node.id, "k", "{}"), from.x + NODE_WIDTH, from.y + NODE_HEIGHT / 2, to.x, to.y + NODE_HEIGHT / 2,
    )

    private val note = PlanGraphNote("q:req", "e:amq.topic", "sentence")
    private fun overlap(a: CardRect, b: CardRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun `two columns 140 apart are pushed to 244 and the columns to the right and their edges follow`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val req = node("q:req", 364f, 24f)
        val far = node("q:far", 728f, 24f)
        val roomed = makeRoomForNotes(listOf(topic, req, far), listOf(edge(topic, req), edge(req, far)), listOf(note))
        assertEquals(listOf(24f, 364f + 104f, 728f + 104f), roomed.nodes.map { it.x })
        assertEquals(244f, roomed.nodes[1].x - (roomed.nodes[0].x + NODE_WIDTH))
        val first = roomed.edges[0]
        assertEquals(224f, first.fromX)
        assertEquals(468f, first.toX)
        assertEquals(346f, first.labelX, "the label stays in the middle of the widened gap")
        val second = roomed.edges[1]
        assertEquals(468f + NODE_WIDTH, second.fromX)
        assertEquals(728f + 104f, second.toX)
    }

    @Test
    fun `no note, or a gap that is wide enough, moves nothing`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val req = node("q:req", 24f + NODE_WIDTH + 244f, 24f)
        val nodes = listOf(topic, req)
        val edges = listOf(edge(topic, req))
        assertEquals(RoomedGraph(nodes, edges), makeRoomForNotes(nodes, edges, emptyList()))
        assertEquals(RoomedGraph(nodes, edges), makeRoomForNotes(nodes, edges, listOf(note)))
        assertEquals(RoomedGraph(nodes, edges), makeRoomForNotes(nodes, edges, listOf(PlanGraphNote("q:none", "e:amq.topic", "x"))))
    }

    @Test
    fun `two nodes of one column get a vertical gap and the nodes below move down`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val req = node("q:req", 24f, 92f)
        val below = node("q:zzz", 24f, 160f)
        val side = node("q:side", 364f, 160f)
        val roomed = makeRoomForNotes(listOf(topic, req, below, side), listOf(edge(topic, side)), listOf(note))
        val ys = roomed.nodes.associate { it.node.id to it.y }
        assertEquals(NOTE_HEIGHT + 16f, ys.getValue("q:req") - (ys.getValue("e:amq.topic") + NODE_HEIGHT))
        assertEquals(160f + (ys.getValue("q:req") - 92f), ys.getValue("q:zzz"))
        assertEquals(160f, ys.getValue("q:side"), "another column does not move")
    }

    @Test
    fun `a note between two columns stands at the top of the first row, centred on the band`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val req = node("q:req", 24f + NODE_WIDTH + NOTE_COLUMN_GAP, 24f)
        val rect = placeNotes(listOf(topic, req), emptyList(), listOf(note)).single()!!
        assertEquals(4f, rect.top)
        assertEquals(NOTE_WIDTH, rect.right - rect.left)
        assertEquals((topic.x + NODE_WIDTH + req.x) / 2f, (rect.left + rect.right) / 2f)
    }

    @Test
    fun `a note in a vertical gap stands between the two nodes of one column`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val req = node("q:req", 24f, 24f + NODE_HEIGHT + NOTE_HEIGHT + 16f)
        val rect = placeNotes(listOf(topic, req), emptyList(), listOf(note)).single()!!
        assertTrue(rect.top >= topic.y + NODE_HEIGHT && rect.bottom <= req.y, "$rect")
    }

    @Test
    fun `a taken slot gives the nearest free one, and no note covers a node, a label or another note`() {
        val topic = node("e:amq.topic", 24f, 24f)
        val a = node("q:a", 24f + NODE_WIDTH + NOTE_COLUMN_GAP, 24f)
        val b = node("q:b", 24f + NODE_WIDTH + NOTE_COLUMN_GAP, 92f)
        val label = CardRect(150f, 70f, 330f, 140f)
        val rects = placeNotes(listOf(topic, a, b), listOf(label), listOf(PlanGraphNote("q:a", "e:amq.topic", "x"), PlanGraphNote("q:b", "e:amq.topic", "x"))).map { it!! }
        assertTrue(!overlap(rects[0], rects[1]), "the notes overlap: $rects")
        rects.forEach { r ->
            assertTrue(!overlap(r, label), "$r covers the label")
            listOf(topic, a, b).forEach { n -> assertTrue(!overlap(r, CardRect(n.x, n.y, n.x + NODE_WIDTH, n.y + NODE_HEIGHT)), "$r covers ${n.node.id}") }
        }
    }

    @Test
    fun `a note whose node is not drawn has no place`() {
        assertNull(placeNotes(listOf(node("q:req", 24f, 24f)), emptyList(), listOf(note)).single())
    }
}
