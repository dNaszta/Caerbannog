package dev.caerbannog.ui.streams

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M2-17d: the vertical rule of the inline hint popup, as pure arithmetic. All numbers are root pixels; `anchorY` is the line the
 * popup hangs from today (its top edge), the result is the top edge to use. The popup is 80 px tall in the screen tests (four lines of
 * `bodySmall` plus 2 x 8 px padding) and the lazy list viewport there runs from y 116 to y 800.
 */
class HintPopupPlacementTest {
    private val viewportTop = 116f
    private val viewportBottom = 800f
    private val popup = 80f

    private fun top(anchorY: Float, height: Float = popup, from: Float = viewportTop, to: Float = viewportBottom): Float =
        hintPopupTop(anchorY, height, from, to)

    @Test
    fun `a popup that fits below stays where it is`() {
        assertEquals(300f, top(anchorY = 300f))
    }

    @Test
    fun `a popup that ends exactly on the bottom edge still fits below`() {
        assertEquals(720f, top(anchorY = 720f))
    }

    @Test
    fun `a popup one pixel too low flips above and ends on the anchor line`() {
        assertEquals(641f, top(anchorY = 721f))
    }

    @Test
    fun `the bottom-most fully visible row at the top of the list flips above (M2-17d probe, popup top 775)`() {
        assertEquals(695f, top(anchorY = 775f))
    }

    @Test
    fun `the bottom-most fully visible row in the middle of the list flips above (M2-17d probe, popup top 785)`() {
        assertEquals(705f, top(anchorY = 785f))
    }

    @Test
    fun `above wins over a clamp when the popup starts exactly on the top edge`() {
        assertEquals(100f, top(anchorY = 180f, from = 100f, to = 250f))
    }

    @Test
    fun `when it fits neither below nor above it is clamped to the bottom edge`() {
        // Viewport 100..240 is 140 tall: below would end at 250, above would start at 90.
        assertEquals(160f, top(anchorY = 170f, from = 100f, to = 240f))
    }

    @Test
    fun `an anchor above the viewport pulls the popup down to the top edge`() {
        assertEquals(viewportTop, top(anchorY = 60f))
    }

    @Test
    fun `an anchor below the viewport pulls the popup up so that it ends on the bottom edge`() {
        // Above would start at 750, but its bottom edge (830) is outside the viewport too, so it does not fit.
        assertEquals(viewportBottom - popup, top(anchorY = 830f))
    }

    @Test
    fun `a popup taller than the viewport starts on the top edge`() {
        assertEquals(viewportTop, top(anchorY = 400f, height = 900f))
    }
}
