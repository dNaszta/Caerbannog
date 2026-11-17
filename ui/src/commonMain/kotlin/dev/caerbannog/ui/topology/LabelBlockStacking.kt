package dev.caerbannog.ui.topology

import kotlin.math.abs

/**
 * One label block of the review graph: its anchor ([x], [y], the middle of the edge's curve), its [height], and [pivot], the distance from the
 * top of the block to the point that sits on the anchor (the middle of the key pill).
 */
internal data class LabelBlockSpec(val x: Float, val y: Float, val height: Float, val pivot: Float)

/** Blocks whose anchors are this close across collide (the same 60 dp as the core's label stagger). */
internal const val STACK_DX = 60f

/** The space kept between two stacked blocks. */
internal const val STACK_GAP = 4f

/** A stack never starts above this, so its top block keeps clear of the border of the graph box. */
internal const val STACK_TOP_MARGIN = 8f

/**
 * The top of every block. A block alone sits on its anchor. Blocks whose anchors are within [STACK_DX] across and within one block height down
 * (also through a third block) form a stack: they are placed top to bottom in the given order, [STACK_GAP] apart, centred on where they would
 * have been alone. A stack that would start above [STACK_TOP_MARGIN] moves down as a whole. Problem it solves: labels of edges that share one
 * curve have the same anchor, and drawing each at a fixed offset made them sit on each other.
 */
internal fun stackLabelBlocks(blocks: List<LabelBlockSpec>): List<StackedBlock> {
    val group = IntArray(blocks.size) { it }
    fun find(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
    for (i in blocks.indices) for (j in i + 1 until blocks.size) {
        val a = blocks[i]; val b = blocks[j]
        if (abs(a.x - b.x) < STACK_DX && abs(a.y - b.y) < maxOf(a.height, b.height)) group[find(j)] = find(i)
    }
    val tops = FloatArray(blocks.size)
    val stacked = BooleanArray(blocks.size)
    blocks.indices.groupBy { find(it) }.values.forEach { members ->
        val alone = members.map { blocks[it].y - blocks[it].pivot }
        val centre = members.indices.map { alone[it] + blocks[members[it]].height / 2f }.average().toFloat()
        val total = members.sumOf { blocks[it].height.toDouble() }.toFloat() + STACK_GAP * (members.size - 1)
        members.forEach { stacked[it] = members.size > 1 }
        var top = maxOf(centre - total / 2f, STACK_TOP_MARGIN)
        members.forEach { i ->
            tops[i] = top
            top += blocks[i].height + STACK_GAP
        }
    }
    return blocks.indices.map { StackedBlock(tops[it], stacked[it]) }
}

/** Where a block goes: its [top], and whether it shares a stack with another block (only then is it moved sideways, see [clampLabelCentre]). */
internal data class StackedBlock(val top: Float, val stacked: Boolean)

/** A node card as a rectangle, in dp. */
internal data class CardRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The centre x of a stacked block of [width] and [height] at [top], moved sideways just far enough to clear the cards it would touch
 * ([STACK_GAP] away), so a stack in the gap between two layers does not cover a card. A block that cannot clear both sides is left centred.
 */
internal fun clampLabelCentre(centre: Float, width: Float, top: Float, height: Float, cards: List<CardRect>): Float {
    val touching = cards.filter { it.top < top + height && top < it.bottom }
    val leftLimit = touching.filter { it.right <= centre }.maxOfOrNull { it.right + STACK_GAP }
    val rightLimit = touching.filter { it.left >= centre }.minOfOrNull { it.left - STACK_GAP }
    var result = centre
    if (leftLimit != null && result - width / 2f < leftLimit) result = leftLimit + width / 2f
    if (rightLimit != null && result + width / 2f > rightLimit) result = rightLimit - width / 2f
    // Squeezed between both: no room, keep the anchor.
    if (leftLimit != null && result - width / 2f < leftLimit - 0.001f) return centre
    return result
}
