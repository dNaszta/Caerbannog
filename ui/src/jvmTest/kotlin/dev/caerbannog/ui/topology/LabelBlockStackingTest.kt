package dev.caerbannog.ui.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-10 round 2 (F1): the pure stacking of the label blocks of the review graph. */
class LabelBlockStackingTest {
    private fun block(x: Float, y: Float, h: Float = 60f, pivot: Float = 10f) = LabelBlockSpec(x, y, h, pivot)

    @Test
    fun `a block alone sits on its anchor`() {
        assertEquals(listOf(90f), stackLabelBlocks(listOf(block(100f, 100f))).map { it.top })
    }

    @Test
    fun `nothing is empty`() {
        assertEquals(emptyList(), stackLabelBlocks(emptyList()))
    }

    @Test
    fun `two blocks on one anchor are stacked with the gap, in order, centred on the anchor`() {
        val tops = stackLabelBlocks(listOf(block(100f, 200f), block(100f, 200f))).map { it.top }
        assertEquals(tops[0] + 60f + STACK_GAP, tops[1])
        assertEquals((190f + 30f), ((tops[0] + tops[1] + 60f) / 2f), 0.001f) // the middle of the stack is the middle of one block alone
    }

    @Test
    fun `five blocks never touch and keep their order`() {
        val tops = stackLabelBlocks((1..5).map { block(100f, 300f) }).map { it.top }
        for (i in 0 until 4) assertTrue(tops[i + 1] - (tops[i] + 60f) >= STACK_GAP - 0.001f, "gap between $i and ${i + 1}")
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `blocks far apart across or down are not stacked`() {
        val tops = stackLabelBlocks(listOf(block(100f, 200f), block(160f, 200f), block(100f, 260f))).map { it.top }
        assertEquals(listOf(190f, 190f, 250f), tops)
    }

    @Test
    fun `a block near but not equal is still stacked, and a chain counts`() {
        val tops = stackLabelBlocks(listOf(block(100f, 200f), block(150f, 230f), block(200f, 260f))).map { it.top }
        assertTrue(tops[1] >= tops[0] + 60f + STACK_GAP - 0.001f && tops[2] >= tops[1] + 60f + STACK_GAP - 0.001f, tops.toString())
    }

    @Test
    fun `a stack that would start above the margin moves down as a whole`() {
        val tops = stackLabelBlocks(listOf(block(100f, 20f), block(100f, 20f))).map { it.top }
        assertEquals(STACK_TOP_MARGIN, tops[0])
        assertEquals(STACK_TOP_MARGIN + 60f + STACK_GAP, tops[1])
    }

    @Test
    fun `only blocks that share a stack are marked as stacked`() {
        val placed = stackLabelBlocks(listOf(block(100f, 200f), block(100f, 200f), block(400f, 200f)))
        assertEquals(listOf(true, true, false), placed.map { it.stacked })
    }

    private val cards = listOf(CardRect(0f, 100f, 200f, 148f), CardRect(340f, 100f, 540f, 148f))

    @Test
    fun `a block that touches a card on its left is moved right, clear of it`() {
        val c = clampLabelCentre(centre = 240f, width = 110f, top = 90f, height = 60f, cards = cards)
        assertEquals(200f + STACK_GAP + 55f, c, 0.001f)
    }

    @Test
    fun `a block that touches a card on its right is moved left, clear of it`() {
        val c = clampLabelCentre(centre = 300f, width = 110f, top = 90f, height = 60f, cards = cards)
        assertEquals(340f - STACK_GAP - 55f, c, 0.001f)
    }

    @Test
    fun `a block above or below the cards, or already clear of them, stays where it is`() {
        assertEquals(240f, clampLabelCentre(240f, 110f, top = 20f, height = 60f, cards = cards))
        assertEquals(270f, clampLabelCentre(270f, 110f, top = 90f, height = 60f, cards = cards))
    }

    @Test
    fun `a block too wide for the gap stays centred`() {
        assertEquals(270f, clampLabelCentre(270f, 200f, top = 90f, height = 60f, cards = cards))
    }
}
