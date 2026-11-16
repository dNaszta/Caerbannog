package dev.caerbannog.ui.planner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-04 round 2, written first (red). F1: at the default window the review graph must not be silently clipped: every node is fully inside
 * the visible graph box, OR a scrollbar tagged `planner:graphScrollbar` is drawn (only when the content overflows). Any remedy satisfies it
 * (a scrollbar on the shared canvas, a higher side-by-side breakpoint, a lower minimum width). F2: while the read of the broker's names is
 * pending the card draws the plan with no "could not be read" notice and no "not in this plan" mark.
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphRound2UiTest {
    // ---- F1 ----

    @Test
    fun `at 1280 the three item plan is fully visible and needs no scrollbar`() = runDesktopComposeUiTest(1280, 800) {
        // M9-06, owner decision of 2026-11-16 (F4, option A): the side-by-side breakpoint is 1400 dp, so at 1280 the review card stacks under the
        // step list and a small plan fits. Before, "or the graph has a scrollbar" was enough; now the graph must fit.
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { threeSteps() }
        showPlanner(r)
        val box = node("planner:reviewGraph").fetchSemanticsNode().boundsInRoot
        val ids = r.state.graph.graph.nodes.map { it.node.id }
        assertTrue(ids.isNotEmpty())
        val clipped = ids.filter { id ->
            // positionInRoot and size are the layout's, not clipped by the scroll box (boundsInRoot is clipped, so it would always look inside).
            val n = node("planGraphNode:$id").fetchSemanticsNode()
            n.positionInRoot.x < box.left - 0.5f || n.positionInRoot.x + n.size.width > box.right + 0.5f
        }
        assertEquals(emptyList(), clipped, "nodes $clipped are cut off by the graph box ($box) at 1280 px")
        assertNotDrawn("planner:graphScrollbar")
        r.close()
    }

    @Test
    fun `a graph that fits draws no scrollbar`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { queue("orders") }
        showPlanner(r)
        assertNotDrawn("planner:graphScrollbar")
        r.close()
    }

    @Test
    fun `sixty nodes are scrollable and say so with a scrollbar`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { (1..60).forEach { queue("queue-$it") } }
        showPlanner(r)
        assertEquals(60, r.state.graph.graph.nodes.size)
        assertDrawn("planner:graphScrollbar")
        r.close()
    }

    // ---- F2 ----

    @Test
    fun `while the read is pending the card shows no notice and no not in this plan mark`() = runDesktopComposeUiTest(1280, 800) {
        val reader = FakeKnownTopologyReader(KnownTopology(exchanges = mapOf("events" to "direct")))
        lateinit var gate: dev.caerbannog.core.connections.fakes.CallGate
        // Held at the end of prepare: the rig builds the view model the screen shows right after it, and that one reads next.
        val r = UiRig(reader = reader) { queue("orders"); binding("events", "orders", "k"); gate = reader.holdNext() }
        showPlanner(r)
        assertDrawn("planner:reviewGraph")
        assertNotDrawn("planner:graphNotice")
        assertNotDrawn("planGraphNotInPlan:e:events")
        gate.release()
        waitForIdle()
        assertNotDrawn("planner:graphNotice")
        assertNotDrawn("planGraphNotInPlan:e:events")
        r.close()
    }

    @Test
    fun `after a failed read the notice and the marks appear`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(null)) { queue("orders"); binding("events", "orders", "k") }
        showPlanner(r)
        assertDrawn("planner:graphNotice")
        assertDrawn("planGraphNotInPlan:e:events")
        r.close()
    }
}
