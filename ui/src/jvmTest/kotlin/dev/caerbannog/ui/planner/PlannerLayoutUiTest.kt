package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
    fun `a seven step plan at 1600 x 1000 shows at least the first three rows - the list scrolls when the card needs its floor`() = runDesktopComposeUiTest(1600, 1000) {
        // Relaxed by the architect (M9-07 round 2, A4): the card has a floor, so the list may scroll. Unchecked, the card needs less; if all seven rows
        // still fit the test does not mind. The first three rows must be fully inside the panel either way.
        val r = UiRig { (1..7).forEach { queue("queue-$it") } }
        showPlanner(r)
        val list = node("planner:stepList").fetchSemanticsNode()
        val listBottom = list.positionInRoot.y + list.size.height
        for (i in 0..2) {
            assertTrue(bottomOf("planStep:$i") <= listBottom + 0.5f, "row $i ends at ${bottomOf("planStep:$i")}, below the list panel that ends at $listBottom")
        }
        r.close()
    }

    // ---- M9-07 round 2 (A3): the card floor makes a long plan scroll in its panel ----

    private fun checkedSevenRig(): UiRig {
        val port = dev.caerbannog.core.migrations.fakes.FakeDeclareCheck()
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None), declareCheck = port) { (1..7).forEach { queue("queue-$it") } }
        r.state.plan.steps.forEach { port.answer(it.action, dev.caerbannog.core.migrations.BrokerFinding.Absent) }
        return r
    }

    @Test
    fun `a checked seven step plan at 1600 x 1000 scrolls in its panel, with the first three rows whole and the panel no smaller than header and three rows`() {
        // STEPS_PANEL_MIN is measured, not guessed: the panel (`planner:stepList`) of a three step plan of the same kind in the same window hugs its
        // rows, so its height is "three rows" as the screen draws them.
        var threeRows = 0f
        runDesktopComposeUiTest(1600, 1000) {
            val r = UiRig { (1..3).forEach { queue("queue-$it") } }
            showPlanner(r)
            threeRows = node("planner:stepList").fetchSemanticsNode().size.height.toFloat()
            r.close()
        }
        assertTrue(threeRows > 150f, "the measure of three rows is $threeRows")
        runDesktopComposeUiTest(1600, 1000) {
            val r = checkedSevenRig()
            showPlanner(r)
            node("planner:checkBtn").performClick()
            waitForIdle()
            assertDrawn("planner:checkSummary")
            val list = node("planner:stepList").fetchSemanticsNode()
            val listBottom = list.positionInRoot.y + list.size.height
            for (i in 0..2) {
                assertTrue(bottomOf("planStep:$i") <= listBottom + 0.5f, "row $i ends at ${bottomOf("planStep:$i")}, below the panel that ends at $listBottom")
            }
            assertTrue(list.size.height >= threeRows - 1f, "the panel is ${list.size.height} px high, less than header and three rows ($threeRows)")
            // The list scrolls (its scrollbar is not tagged, so the fact is tested): row 6 is not whole inside the panel, and scrolling brings it in.
            val sixWhole = runCatching { bottomOf("planStep:6") <= listBottom + 0.5f }.getOrDefault(false)
            assertTrue(!sixWhole, "all seven rows are whole in the panel: the card did not get its floor")
            node("planner:stepList").performScrollToIndex(6)
            waitForIdle()
            val after = node("planner:stepList").fetchSemanticsNode()
            assertTrue(bottomOf("planStep:6") <= after.positionInRoot.y + after.size.height + 0.5f, "row 6 cannot be scrolled into the panel")
            r.close()
        }
    }
}
