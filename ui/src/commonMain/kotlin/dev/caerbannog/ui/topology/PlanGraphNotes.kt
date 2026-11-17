package dev.caerbannog.ui.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.PlanGraphNote
import dev.caerbannog.core.topology.NODE_HEIGHT
import dev.caerbannog.core.topology.NODE_WIDTH
import dev.caerbannog.core.topology.PositionedEdge
import dev.caerbannog.core.topology.PositionedNode

// The note of the review graph (M9-07b): "the owner's own service sits here". It is a plain box with a dashed outline, never a step or an edge.
// The problem it solves: the sentence must stand between two nodes, but the gap between two layers (140 dp) is narrower than a readable box, so
// the layout makes room first (makeRoomForNotes) and then places the box (placeNotes). Both are pure, so the geometry has plain unit tests.

/** The fixed width of the note box; the sentence wraps inside it. */
internal const val NOTE_WIDTH = 220f

/** The height of the note box: three lines of `labelSmall` (16 dp each) and 8 dp of padding above and below. */
internal const val NOTE_HEIGHT = 64f

/** The gap kept between two columns that hold the nodes of a note: the box and 12 dp on both sides. */
internal const val NOTE_COLUMN_GAP = NOTE_WIDTH + 2 * 12f

/** Space kept above and below the box when it stands in a vertical gap of one column. */
private const val NOTE_VERTICAL_MARGIN = 8f

/** Where a box starts at the top of the first row, below the border of the graph. */
private const val NOTE_TOP_MARGIN = 4f

/** How far above the higher of the two nodes the preferred slot starts: the free band over the first row. */
private const val NOTE_RISE = 20f

/** Distance kept between a note and the next thing in a slot search. */
private const val NOTE_SLOT_GAP = 4f

private const val MAX_SLOT_STEPS = 60

/** The nodes and edges after [makeRoomForNotes]. */
internal data class RoomedGraph(val nodes: List<PositionedNode>, val edges: List<PositionedEdge>)

/**
 * Makes room for each note between its two nodes, on the plan path only. Two columns closer than [NOTE_COLUMN_GAP] are pushed apart by moving the
 * right-hand column and every node to its right; two nodes of one column get a gap of [NOTE_HEIGHT] plus 16 dp under the upper one (the nodes
 * below it move down). The edges follow their nodes: an end moves with its node, a label with the middle of its two ends. With no note, or a
 * note whose nodes are not both drawn, nothing moves.
 */
internal fun makeRoomForNotes(nodes: List<PositionedNode>, edges: List<PositionedEdge>, notes: List<PlanGraphNote>): RoomedGraph {
    var current = RoomedGraph(nodes, edges)
    for (note in notes) {
        val a = current.nodes.firstOrNull { it.node.id == note.sourceId } ?: continue
        val b = current.nodes.firstOrNull { it.node.id == note.targetId } ?: continue
        current = if (a.x == b.x) roomBelow(current, if (a.y <= b.y) a else b) else roomBetween(current, if (a.x < b.x) a else b, if (a.x < b.x) b else a)
    }
    return current
}

private fun roomBetween(graph: RoomedGraph, left: PositionedNode, right: PositionedNode): RoomedGraph {
    val extra = NOTE_COLUMN_GAP - (right.x - (left.x + NODE_WIDTH))
    if (extra <= 0f) return graph
    val moved = graph.nodes.filter { it.x >= right.x }.map { it.node.id }.toSet()
    return move(graph, moved, dx = extra, dy = 0f)
}

private fun roomBelow(graph: RoomedGraph, upper: PositionedNode): RoomedGraph {
    val column = graph.nodes.filter { it.x == upper.x && it.y > upper.y }
    val nextTop = column.minOfOrNull { it.y } ?: return graph
    val extra = NOTE_HEIGHT + 2 * NOTE_VERTICAL_MARGIN - (nextTop - (upper.y + NODE_HEIGHT))
    if (extra <= 0f) return graph
    return move(graph, column.map { it.node.id }.toSet(), dx = 0f, dy = extra)
}

private fun move(graph: RoomedGraph, ids: Set<String>, dx: Float, dy: Float): RoomedGraph {
    val nodes = graph.nodes.map { if (it.node.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it }
    val edges = graph.edges.map { e ->
        val fromMoved = e.edge.sourceId in ids
        val toMoved = e.edge.targetId in ids
        val fromDx = if (fromMoved) dx else 0f
        val toDx = if (toMoved) dx else 0f
        val fromDy = if (fromMoved) dy else 0f
        val toDy = if (toMoved) dy else 0f
        e.copy(
            fromX = e.fromX + fromDx, toX = e.toX + toDx, fromY = e.fromY + fromDy, toY = e.toY + toDy,
            labelX = e.labelX + (fromDx + toDx) / 2f, labelY = e.labelY + (fromDy + toDy) / 2f,
        )
    }
    return RoomedGraph(nodes, edges)
}

/**
 * The rectangle of each note in graph coordinates (dp), in the order of [notes]; null for a note whose two nodes are not both in [nodes].
 * Two columns: the box is centred on the free band between them, preferably at the top of the first row (about y = 4). One column: it stands in
 * the gap under the upper node. A box never covers a node, a label block ([labelRects]) or another note: when its preferred slot is taken it
 * takes the nearest free slot up or down, and when none is free within a long search it keeps the preferred one (shown, never dropped).
 */
internal fun placeNotes(nodes: List<PositionedNode>, labelRects: List<CardRect>, notes: List<PlanGraphNote>): List<CardRect?> {
    val cards = nodes.map { CardRect(it.x, it.y, it.x + NODE_WIDTH, it.y + NODE_HEIGHT) }
    val placed = mutableListOf<CardRect>()
    return notes.map { note ->
        val a = nodes.firstOrNull { it.node.id == note.sourceId }
        val b = nodes.firstOrNull { it.node.id == note.targetId }
        if (a == null || b == null) return@map null
        val (centreX, preferredTop) = if (a.x == b.x) {
            val upper = if (a.y <= b.y) a else b
            val below = nodes.filter { it.x == upper.x && it.y > upper.y }.minOfOrNull { it.y } ?: (upper.y + NODE_HEIGHT + NOTE_HEIGHT + 2 * NOTE_VERTICAL_MARGIN)
            val gap = below - (upper.y + NODE_HEIGHT)
            (upper.x + NODE_WIDTH / 2f) to (upper.y + NODE_HEIGHT + (gap - NOTE_HEIGHT) / 2f)
        } else {
            val left = if (a.x < b.x) a else b
            val right = if (a.x < b.x) b else a
            ((left.x + NODE_WIDTH + right.x) / 2f) to maxOf(NOTE_TOP_MARGIN, minOf(a.y, b.y) - NOTE_RISE)
        }
        fun rectAt(top: Float) = CardRect(centreX - NOTE_WIDTH / 2f, top, centreX + NOTE_WIDTH / 2f, top + NOTE_HEIGHT)
        val rect = slotCandidates(preferredTop).map(::rectAt).firstOrNull { r ->
            // A label's width is only an estimate, so it is kept clear without a margin; cards and other notes keep one.
            (cards + placed).none { r.overlaps(it, NOTE_SLOT_GAP) } && labelRects.none { r.overlaps(it, 0f) }
        }
            ?: rectAt(preferredTop)
        placed += rect
        rect
    }
}

/** The preferred top, then the slots above and below it, nearest first (above before below), never above the top margin. */
private fun slotCandidates(preferred: Float): Sequence<Float> = sequence {
    yield(preferred)
    val step = NOTE_HEIGHT + NOTE_SLOT_GAP
    for (k in 1..MAX_SLOT_STEPS) {
        if (preferred - k * step >= NOTE_TOP_MARGIN) yield(preferred - k * step)
        yield(preferred + k * step)
    }
}

private fun CardRect.overlaps(other: CardRect, gap: Float): Boolean =
    left < other.right + gap && other.left < right + gap && top < other.bottom + gap && other.top < bottom + gap

/**
 * The rough rectangle of a label block for [placeNotes], centred on [centre] at [top]: the real width is known only when the block is measured,
 * so it is estimated from the text it holds ([textLength] characters of the longest line, about 7.3 dp each, 7.5 is used in the small monospace font, plus the pill's
 * padding). A little too wide is safe (the note moves to the next free slot); far too wide would push it away from its two nodes.
 */
internal fun labelRectOf(centre: Float, top: Float, height: Float, textLength: Int): CardRect {
    val half = (textLength * LABEL_CHAR_WIDTH + 12f) / 2f
    return CardRect(centre - half, top, centre + half, top + height)
}

private const val LABEL_CHAR_WIDTH = 7.5f

/**
 * One note: a dashed rounded box with the sentence, drawn under the nodes and the labels. Read-only: no click, no long click, no focus and no
 * edge, so nothing in it can reach a planner event. The tag is `planGraphNote:<index>`.
 */
@Composable
internal fun PlanGraphNoteBox(index: Int, text: String, rect: CardRect) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .offset(x = rect.left.dp, y = rect.top.dp)
            .width(NOTE_WIDTH.dp)
            .heightIn(min = NOTE_HEIGHT.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .drawBehind {
                val width = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 3f))),
                )
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("planGraphNote:$index"),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
