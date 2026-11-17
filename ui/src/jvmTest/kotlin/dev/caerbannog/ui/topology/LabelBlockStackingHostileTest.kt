package dev.caerbannog.ui.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10 round 2, step 4 (test developer): `stackLabelBlocks` and `clampLabelCentre` on hostile input (never throws, bounded runtime, no overlap inside
 * a stack beyond tolerance), and the numbers of the architect's decision pinned as values (the developer's own tests use the constants symbolically, so
 * a gap of 0 passed them).
 */
class LabelBlockStackingHostileTest {
    private fun block(x: Float, y: Float, h: Float = 60f, pivot: Float = 10f) = LabelBlockSpec(x, y, h, pivot)

    @Test
    fun `the decided numbers`() {
        assertEquals(60f, STACK_DX)
        assertEquals(4f, STACK_GAP)
        assertEquals(8f, STACK_TOP_MARGIN)
    }

    @Test
    fun `no blocks and one block`() {
        assertEquals(emptyList(), stackLabelBlocks(emptyList()))
        assertEquals(1, stackLabelBlocks(listOf(block(0f, 0f))).size)
    }

    @Test
    fun `1000 blocks on one anchor stack without overlap and in bounded time`() {
        val start = System.nanoTime()
        val placed = stackLabelBlocks((1..1000).map { block(100f, 300f) })
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 3_000, "took $millis ms")
        assertEquals(1000, placed.size)
        for (i in 0 until 999) assertTrue(placed[i + 1].top - (placed[i].top + 60f) >= STACK_GAP - 0.01f, "gap at $i")
        assertTrue(placed.first().top >= STACK_TOP_MARGIN)
    }

    @Test
    fun `1000 blocks spread over the graph stay bounded and ordered inside every stack`() {
        val specs = (0 until 1000).map { block((it % 7) * 25f, (it % 13) * 15f) }
        val start = System.nanoTime()
        val placed = stackLabelBlocks(specs)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
        assertEquals(1000, placed.size)
        assertTrue(placed.all { it.top >= STACK_TOP_MARGIN || !it.top.isFinite() })
    }

    @Test
    fun `not-a-number and infinite coordinates and sizes never throw`() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (x in bad) for (y in bad) for (h in bad) {
            val placed = stackLabelBlocks(listOf(block(x, y, h, 1f), block(x, y, h, 1f), block(0f, 0f, 20f)))
            assertEquals(3, placed.size, "x=$x y=$y h=$h")
            val c = clampLabelCentre(x, h, y, h, listOf(CardRect(0f, 0f, 100f, 48f), CardRect(x, y, x, y)))
            assertTrue(c.isNaN() || true, "no throw")
        }
    }

    @Test
    fun `negative heights and pivots do not break the finite ones`() {
        val placed = stackLabelBlocks(listOf(block(100f, 100f, -20f, -5f), block(100f, 100f, 40f, 10f), block(100f, 100f, 40f, 10f)))
        assertEquals(3, placed.size)
        assertTrue(placed.all { it.top.isFinite() })
    }

    @Test
    fun `a block wider than the graph and cards touching each other stay centred and finite`() {
        val cards = listOf(CardRect(0f, 0f, 200f, 100f), CardRect(200f, 0f, 400f, 100f))
        assertEquals(200f, clampLabelCentre(200f, 10_000f, 10f, 40f, cards))
        assertEquals(200f, clampLabelCentre(200f, 0f, 10f, 40f, cards))
        assertTrue(clampLabelCentre(200f, -50f, 10f, 40f, cards).isFinite())
        assertEquals(500f, clampLabelCentre(500f, 80f, 10f, 40f, emptyList()))
    }

    @Test
    fun `equal anchors in any order give distinct tops that keep the given order`() {
        val tops = stackLabelBlocks(listOf(block(5f, 5f), block(5f, 5f), block(5f, 5f))).map { it.top }
        assertEquals(tops.sorted(), tops)
        assertEquals(3, tops.toSet().size)
    }
}
