package dev.caerbannog.ui.streams

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.StreamLogTape
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M7-01 visual half: the PIXELS the log tape canvas draws, not just its texts. The card is shown alone in a fixed light theme
 * (so every colour is deterministic) at density 1 (1 dp = 1 px) in a fixed window; the canvas node is captured and read back.
 *
 * Robustness rules: colours are sampled inside a zone, never on an anti-aliased edge or the 1 dp border; the expected colour is
 * computed the way Compose blends it (`compositeOver` in sRGB over the opaque zone background), with a small per-channel
 * tolerance; positions have a few pixels of tolerance. The hatch is checked by its structure (period and share of a row), not by
 * an exact pixel mask. Colour values come from the view: see `StreamLogTapeCanvas` (zone colours) and `CaughtUpGreen`/`LaggingAmber`.
 */
@OptIn(ExperimentalTestApi::class)
class StreamLogTapeRenderingUiTest {

    private val scheme = lightColorScheme()

    // The same colours the canvas derives from the theme (StreamLogTapeView.kt, StreamLogTapeCanvas).
    private val trackBg = scheme.surfaceContainerHighest
    private val truncatedBg = scheme.surfaceContainerLow
    private val hatch = scheme.outlineVariant.copy(alpha = 0.6f).compositeOver(truncatedBg)
    private val dangerZone = scheme.error.copy(alpha = 0.22f).compositeOver(trackBg)
    private val sliceFill = scheme.primary.copy(alpha = 0.28f).compositeOver(trackBg)
    private val selected = scheme.tertiary
    private val caughtUpGreen = Color(0xFF386A20)
    private val laggingAmber = Color(0xFFE65100)
    private val atRisk = scheme.error
    private val inactive = scheme.outline

    /** A captured canvas: its pixels and its size in px (density 1, so also in dp). */
    private class Shot(val pixels: PixelMap) {
        val w get() = pixels.width
        val h get() = pixels.height
        operator fun get(x: Int, y: Int): Color = pixels[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
    }

    private fun ComposeUiTest.capture(tape: StreamLogTape): Shot {
        var density = 1f
        setContent {
            MaterialTheme(colorScheme = scheme) {
                density = LocalDensity.current.density
                StreamLogTapeCard(tape = tape, onSeekOffset = {})
            }
        }
        waitForIdle()
        assertEquals(1f, density, "the test assumes density 1 so that dp equals px")
        val image = onNodeWithTag("streamLogTapeCanvas").captureToImage()
        return Shot(image.toPixelMap())
    }

    private fun shotOf(tape: StreamLogTape): Shot {
        lateinit var shot: Shot
        runDesktopComposeUiTest(800, 400) { shot = capture(tape) }
        return shot
    }

    private fun near(actual: Color, expected: Color, tolerance: Float = 3f / 255f): Boolean =
        abs(actual.red - expected.red) <= tolerance &&
            abs(actual.green - expected.green) <= tolerance &&
            abs(actual.blue - expected.blue) <= tolerance

    private fun assertColor(expected: Color, actual: Color, what: String) =
        assertTrue(near(actual, expected), "$what: expected $expected but the pixel is $actual")

    private fun consumer(key: String, offset: Long?, status: ConsumerStatus) = ConsumerRowState(
        key = key, name = key, connection = "127.0.0.1:50000", user = "svc",
        offset = offset?.toString() ?: "-", behind = "?", status = status, browseFromOffset = offset,
    )

    // Geometry of the canvas content, as the view computes it (the 1 dp border is inside the node).
    private fun truncW(shot: Shot, tape: StreamLogTape) = shot.w * tape.truncatedFraction
    private fun activeStart(shot: Shot, tape: StreamLogTape) = truncW(shot, tape)
    private fun activeW(shot: Shot, tape: StreamLogTape) = (shot.w - truncW(shot, tape)).coerceAtLeast(1f)
    private fun xAtActiveFraction(shot: Shot, tape: StreamLogTape, fraction: Float) =
        (activeStart(shot, tape) + activeW(shot, tape) * fraction).roundToInt()

    private val topRow = 6 // inside the bar, above the pin circles and the slice border, below the selection diamond's tip rows

    // ---- sanity -------------------------------------------------------------------------------------------------------------

    @Test
    fun `a loaded tape is not blank - the canvas has a real size and more than one colour`() {
        val tape = StreamLogTape.from(2_000, 10_000, 5_000, 5_099, selectedOffset = 5_050)!!
        val shot = shotOf(tape)

        assertTrue(shot.w > 300 && shot.h in 20..40, "canvas size ${shot.w} x ${shot.h}")
        val distinct = HashSet<Int>()
        for (y in 0 until shot.h) for (x in 0 until shot.w) distinct += shot[x, y].hashCode()
        assertTrue(distinct.size >= 4, "a tape with a truncated zone, a slice and a cursor draws at least 4 colours, got ${distinct.size}")
    }

    // ---- zones and their order ----------------------------------------------------------------------------------------------

    @Test
    fun `a stream that starts at 0 is one plain track - no hatch, no danger zone`() {
        val tape = StreamLogTape.from(0, 1_000)!!
        val shot = shotOf(tape)

        for (x in 4 until shot.w - 4) assertColor(trackBg, shot[x, topRow], "x=$x of a stream starting at offset 0")
    }

    @Test
    fun `a truncated stream draws, left to right, the hatched expired zone, the red danger zone, then the plain active track`() {
        val tape = StreamLogTape.from(2_000, 10_000)!!
        val shot = shotOf(tape)
        val truncEnd = truncW(shot, tape).roundToInt()
        val dangerEnd = (activeStart(shot, tape) + activeW(shot, tape) * tape.dangerZoneActiveFraction).roundToInt()

        // Danger zone: the first 10 percent of the active window, blended error colour over the track.
        for (x in truncEnd + 3 until dangerEnd - 3) assertColor(dangerZone, shot[x, topRow], "danger zone x=$x")
        // Active track after the danger zone (skip the dashed border of the danger zone).
        for (x in dangerEnd + 4 until shot.w - 4) assertColor(trackBg, shot[x, topRow], "active track x=$x")
        // Expired zone is neither of those colours: it is the lower surface (plus hatch).
        val midTrunc = (truncEnd / 2)
        assertFalse(near(shot[midTrunc, topRow], trackBg) && near(shot[midTrunc + 3, topRow], trackBg), "the expired zone must not look like the active track")
    }

    @Test
    fun `the expired zone is as wide as the model says - the zone change sits at the truncated fraction of the width`() {
        val tape = StreamLogTape.from(2_000, 10_000)!!
        val shot = shotOf(tape)
        val expectedEdge = truncW(shot, tape).roundToInt()

        // Walk right along a row; the first pixel that is the danger colour is where the active window starts.
        var firstDanger = -1
        for (x in 2 until shot.w) if (near(shot[x, topRow], dangerZone)) { firstDanger = x; break }
        assertTrue(firstDanger >= 0, "no danger-coloured pixel found")
        assertTrue(abs(firstDanger - expectedEdge) <= 4, "active window starts at x=$firstDanger, the model says $expectedEdge")
        assertEquals(0.14f, tape.truncatedFraction, "the view draws the model's fixed 14 percent")
    }

    @Test
    fun `the red danger zone is about 10 percent of the active window and ends where the model says`() {
        val tape = StreamLogTape.from(2_000, 10_000)!!
        val shot = shotOf(tape)
        val start = activeStart(shot, tape).roundToInt()
        var end = start
        while (end < shot.w - 1 && near(shot[end + 1, topRow], dangerZone)) end++
        val width = end - start + 1
        val expected = activeW(shot, tape) * 0.10f
        assertTrue(abs(width - expected) <= 6f, "danger zone is $width px wide, expected about $expected")
    }

    @Test
    fun `a truncated stream that is too short for a cliff has the hatch but no danger zone`() {
        val tape = StreamLogTape.from(5, 9)!! // span 4 is below the 10-message minimum
        assertEquals(0f, tape.dangerZoneActiveFraction)
        val shot = shotOf(tape)
        for (x in truncW(shot, tape).roundToInt() + 4 until shot.w - 4) assertColor(trackBg, shot[x, topRow], "x=$x with no cliff")
    }

    // ---- hatching -----------------------------------------------------------------------------------------------------------

    @Test
    fun `the expired zone is hatched - diagonal lines every 8 px on the lower surface colour`() {
        val tape = StreamLogTape.from(2_000, 10_000)!!
        val shot = shotOf(tape)
        val left = 4
        val right = truncW(shot, tape).roundToInt() - 4
        val row = shot.h / 2

        // Hatch pixel = clearly not the plain expired-zone background.
        fun hatched(x: Int, y: Int) = !near(shot[x, y], truncatedBg, tolerance = 4f / 255f)
        val share = (left..right).count { hatched(it, row) }.toFloat() / (right - left + 1)
        assertTrue(share in 0.08f..0.45f, "share of hatch pixels in a row is $share (a 1 px line every 8 px, anti-aliased)")

        // Period 8 px along the row (step = 8 dp at density 1).
        val periodic = (left..right - 8).count { hatched(it, row) == hatched(it + 8, row) }
        assertTrue(periodic >= (right - 8 - left + 1) * 0.95, "the pattern repeats every 8 px in $periodic of ${right - 8 - left + 1} columns")

        // A diagonal: the line seen at (x, y) continues one pixel right and one pixel up (x + y stays constant).
        val hit = (left..right - 3).firstOrNull { hatched(it, row) && !hatched(it + 3, row) }
        assertNotNull(hit, "no hatch pixel found on the middle row")
        val upper = shot.h / 2 - 4
        assertTrue(hatched(hit + 4, upper) || hatched(hit + 3, upper) || hatched(hit + 5, upper), "the line must continue up and to the right")

        // The hatch colour is the blended outline, darker than the background it sits on.
        val darkest = (left..right).map { shot[it, row] }.minByOrNull { it.red + it.green + it.blue }!!
        assertTrue(near(darkest, hatch, tolerance = 25f / 255f), "darkest hatch pixel $darkest should be near $hatch")
    }

    @Test
    fun `the active track is not hatched`() {
        val tape = StreamLogTape.from(2_000, 10_000)!!
        val shot = shotOf(tape)
        val row = shot.h / 2
        val from = (activeStart(shot, tape) + activeW(shot, tape) * 0.2f).roundToInt()
        for (x in from until shot.w - 4) assertColor(trackBg, shot[x, row], "active track x=$x")
    }

    // ---- page slice, cursor, pins -------------------------------------------------------------------------------------------

    @Test
    fun `the page slice is a tinted band at the proportional position, the rest of the track stays plain`() {
        val tape = StreamLogTape.from(0, 1_000, pageFirstOffset = 400, pageLastOffset = 499)!!
        val shot = shotOf(tape)
        val slice = tape.pageSlice!!
        val row = shot.h / 2
        val startX = xAtActiveFraction(shot, tape, slice.startFraction)
        val endX = xAtActiveFraction(shot, tape, slice.endFraction).coerceAtLeast(startX + 6) // the view draws at least 6 dp

        // Inside the slice (clear of its 1,5 dp border): the translucent primary over the track.
        for (x in startX + 3 until endX - 3) assertColor(sliceFill, shot[x, row], "inside the slice x=$x")
        // Outside the slice: plain track, both sides.
        assertColor(trackBg, shot[startX - 10, row], "left of the slice")
        assertColor(trackBg, shot[endX + 10, row], "right of the slice")
        // Its left border is where the model puts it.
        var edge = -1
        for (x in 3 until shot.w) if (!near(shot[x, row], trackBg)) { edge = x; break }
        assertTrue(abs(edge - startX) <= 4, "slice starts at x=$edge, expected $startX")
    }

    @Test
    fun `the page slice moves right when the page is later in the stream`() {
        fun startOf(page: Long): Int {
            val tape = StreamLogTape.from(0, 1_000, pageFirstOffset = page, pageLastOffset = page + 49)!!
            val shot = shotOf(tape)
            var edge = -1
            for (x in 3 until shot.w) if (!near(shot[x, shot.h / 2], trackBg)) { edge = x; break }
            return edge
        }
        val early = startOf(100)
        val late = startOf(800)
        assertTrue(late - early > 300, "page 800 starts at x=$late, page 100 at x=$early")
    }

    @Test
    fun `the selected message cursor is a solid tertiary line at the proportional x`() {
        val tape = StreamLogTape.from(0, 1_000, selectedOffset = 700)!!
        val shot = shotOf(tape)
        val x = xAtActiveFraction(shot, tape, tape.selectedPin!!.fraction)
        val row = shot.h - 4 // below the diamond at the top

        assertTrue((x - 2..x + 2).any { near(shot[it, row], selected) }, "no cursor colour around x=$x on row $row")
        assertColor(trackBg, shot[x - 12, row], "left of the cursor")
        assertColor(trackBg, shot[x + 12, row], "right of the cursor")
        // The diamond at the top is wider than the line: more cursor-coloured pixels on a row near the top than on a low row.
        fun width(y: Int) = (x - 8..x + 8).count { near(shot[it, y], selected) }
        assertTrue(width(4) > width(row), "the diamond head is wider (${width(4)} px) than the line (${width(row)} px)")
    }

    @Test
    fun `consumer pins are coloured by health and sit at the proportional x of their offset`() {
        val tape = StreamLogTape.from(
            100, 1_100,
            consumers = listOf(
                consumer("risk", 150, ConsumerStatus.Behind(950)), // inside the cliff zone 100..200
                consumer("lag", 600, ConsumerStatus.Behind(500)),
                consumer("head", 1_100, ConsumerStatus.CaughtUp),
                consumer("standby", 300, ConsumerStatus.NotActive),
            ),
        )!!
        val shot = shotOf(tape)
        val row = shot.h / 2 // the pin circle is centred here

        val expected = mapOf("risk" to atRisk, "lag" to laggingAmber, "head" to caughtUpGreen, "standby" to inactive)
        val xs = mutableMapOf<String, Int>()
        for (pin in tape.consumerPins) {
            val raw = activeStart(shot, tape) + activeW(shot, tape) * pin.fraction
            val x = raw.coerceIn(activeStart(shot, tape) + 4f, shot.w - 4f).roundToInt()
            xs[pin.key] = x
            assertColor(expected.getValue(pin.key), shot[x, row], "pin ${pin.key} ($${pin.health}) at x=$x")
        }
        // Order on the tape follows the offsets: risk (150) < standby (300) < lag (600) < head (1100).
        assertTrue(xs.getValue("risk") < xs.getValue("standby") && xs.getValue("standby") < xs.getValue("lag") && xs.getValue("lag") < xs.getValue("head"), "pin order $xs")
    }

    @Test
    fun `a consumer pin at the head has its centre clamped 4 px inside the right edge, so it never leaves the bar`() {
        val tape = StreamLogTape.from(0, 1_000, consumers = listOf(consumer("head", 1_000, ConsumerStatus.CaughtUp)))!!
        val shot = shotOf(tape)
        val row = shot.h / 2
        val green = (0 until shot.w).filter { near(shot[it, row], caughtUpGreen) }

        assertTrue(green.isNotEmpty(), "the head pin is drawn")
        assertColor(caughtUpGreen, shot[shot.w - 4, row], "the clamped centre of the head pin")
        // Radius 4,5 against a clamp of 4: the circle touches the edge (clipped by the bar), it is not pushed outside it.
        assertTrue(green.max() >= shot.w - 2, "the pin reaches the right edge of the bar (max x=${green.max()}, width ${shot.w})")
        assertTrue(green.min() >= shot.w - 12, "the pin is a small circle, not a bar-wide fill (min x=${green.min()})")
    }
}
