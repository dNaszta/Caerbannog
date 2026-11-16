package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-06 step 2, the two owner decisions of 2026-11-16 that live in the first commit that touches the review card:
 *  - F4 (option A): the step list and the review card stand side by side only when the CONTENT of the screen is at least 1400 dp wide (the
 *    `BoxWithConstraints` of the body, as today with 900 dp); below that the card stacks under the list. At a 1280 px window it stacks.
 *  - M9-04 item 8: a node of the review graph says "exchange, direct" / "queue, quorum" (the mockup), not "(direct)".
 * The width of the content is measured on the screen (the review card fills it when stacked), so the test does not depend on the width of the drawer.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerLayoutUiTest {
    private fun DesktopComposeUiTest.placement(): String {
        val list = node("planner:stepList").fetchSemanticsNode()
        val card = node("planner:reviewCard").fetchSemanticsNode()
        val listRight = list.positionInRoot.x + list.size.width
        val listBottom = list.positionInRoot.y + list.size.height
        return when {
            card.positionInRoot.y >= listBottom - 1f -> "stacked"
            card.positionInRoot.x >= listRight - 1f -> "side by side"
            else -> "overlapping: list $list, card $card"
        }
    }

    private fun DesktopComposeUiTest.contentWidthDp(): Float = node("planner:reviewCard").fetchSemanticsNode().size.width / density.density

    private fun rig() = UiRig { threeSteps() }

    @Test
    fun `at a 1280 px window the review card stacks under the step list`() = runDesktopComposeUiTest(1280, 900) {
        val r = rig()
        showPlanner(r)
        assertEquals("stacked", placement())
        r.close()
    }

    @Test
    fun `a wide window puts the list and the card side by side`() = runDesktopComposeUiTest(2200, 900) {
        val r = rig()
        showPlanner(r)
        assertEquals("side by side", placement())
        r.close()
    }

    @Test
    fun `the breakpoint is 1400 dp of content - stacked at 1399 and side by side at 1400`() {
        // Measure the chrome (drawer, padding) once: at 1280 the card is stacked and fills the content.
        var chrome = 0f
        runDesktopComposeUiTest(1280, 900) {
            val r = rig(); showPlanner(r)
            chrome = 1280f - contentWidthDp()
            r.close()
        }
        assertTrue(chrome in 0f..600f, "the chrome around the content measured $chrome dp, which is not a drawer and some padding")
        val at1399 = (1399f + chrome).toInt()
        runDesktopComposeUiTest(at1399, 900) {
            val r = rig(); showPlanner(r)
            assertEquals("stacked", placement(), "content ${contentWidthDp()} dp is below 1400")
            r.close()
        }
        val at1400 = (1400f + chrome).toInt() + 1 // the +1 absorbs the rounding of the measured chrome
        runDesktopComposeUiTest(at1400, 900) {
            val r = rig(); showPlanner(r)
            assertEquals("side by side", placement(), "window $at1400 px gives the content 1400 dp or more")
            r.close()
        }
    }

    // ---- the node subtitle ----

    @Test
    fun `a node says its kind and type in the mockup's words`() = runDesktopComposeUiTest(1280, 900) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { exchange("events", "direct"); queue("orders", "quorum"); binding("events", "orders", "k") }
        showPlanner(r)
        assertTrue(textOf("planGraphNode:e:events").contains("exchange, direct"), textOf("planGraphNode:e:events"))
        assertTrue(textOf("planGraphNode:q:orders").contains("queue, quorum"), textOf("planGraphNode:q:orders"))
        assertTrue(!textOf("planGraphNode:e:events").contains("(direct)"), "the old form is still drawn: ${textOf("planGraphNode:e:events")}")
        r.close()
    }

    // ---- M9-07: the stacked layout (owner list item 10 of M9-06, "as recommended") ----
    // Intent only; any remedy satisfies it (the panel hugs its rows, or the card moves up, or the rows are spread).

    private fun DesktopComposeUiTest.bottomOf(tag: String): Float = node(tag).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }

    @Test
    fun `stacked, the review card starts right under the last step of a three step plan - no empty panel between`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        assertEquals("stacked", placement())
        val gapDp = (node("planner:reviewCard").fetchSemanticsNode().positionInRoot.y - bottomOf("planStep:2")) / density.density
        assertTrue(gapDp <= 32f, "there are $gapDp dp between the last step row and the review card; the step panel leaves a gap (the limit is one panel border and one spacing, 32 dp)")
        r.close()
    }

    @Test
    fun `the step list of a three step plan does not scroll inside its panel at 1280 x 800 and at 1600 x 1000`() {
        for ((w, h) in listOf(1280 to 800, 1600 to 1000)) {
            runDesktopComposeUiTest(w, h) {
                val r = rig()
                showPlanner(r)
                val list = node("planner:stepList").fetchSemanticsNode()
                val listBottom = list.positionInRoot.y + list.size.height
                for (i in 0..2) {
                    assertTrue(bottomOf("planStep:$i") <= listBottom + 0.5f, "at $w x $h the row $i ends at ${bottomOf("planStep:$i")}, below the list panel that ends at $listBottom: the list scrolls")
                }
                r.close()
            }
        }
    }

    @Test
    fun `a seven step plan at 1600 x 1000 shows every row without scrolling the list`() = runDesktopComposeUiTest(1600, 1000) {
        // The size of the M9-06 check screenshots (step 4 of that task saw the list scroll inside its panel here; at 1600 px the content is still under 1400 dp, so the card is stacked). The page has room: 7 rows are ~400 dp.
        val r = UiRig { (1..7).forEach { queue("queue-$it") } }
        showPlanner(r)
        val list = node("planner:stepList").fetchSemanticsNode()
        val listBottom = list.positionInRoot.y + list.size.height
        for (i in 0..6) {
            assertTrue(bottomOf("planStep:$i") <= listBottom + 0.5f, "row $i ends at ${bottomOf("planStep:$i")}, below the list panel that ends at $listBottom: the list scrolls")
        }
        r.close()
    }
}
