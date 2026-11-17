package dev.caerbannog.ui.planner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M9-10 round 2, step 4: many routes into one stream. The review graph grows to hold the lowest label block; the graph must stay usable: the
 * graph box (`planner:reviewGraph`) scrolls, shows its scrollbar when it overflows (`planner:graphScrollbar`), and the last route's filter can be
 * scrolled into view, at 1280 x 800 and 1024 x 768, for 20 and 100 routes; no label of the last route is cut off by the content box.
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewManyRoutesUiTest {
    private fun check(width: Int, height: Int, routes: Int) = runDesktopComposeUiTest(width, height) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
            stream("telemetry")
            (1..routes).forEach { mqttRoute("sensors/room$it/+", "telemetry", MqttDestinationKind.Stream) }
        }
        showPlanner(r)
        val last = "planGraphEdgeLabel:edge-${routes - 1}"
        fun unclipped(tag: String) = node(tag).fetchSemanticsNode().let { n -> floatArrayOf(n.positionInRoot.x, n.positionInRoot.y, n.positionInRoot.x + n.size.width, n.positionInRoot.y + n.size.height) }
        val box = unclipped("planner:reviewGraph")
        val contentHeight = unclipped("planner:reviewGraphContent").let { it[3] - it[1] }
        val overflows = contentHeight > box[3] - box[1] + 1f
        fun inside(r: FloatArray) = r[1] >= box[1] - 1f && r[3] <= box[3] + 1f
        // Swipe the graph box up until the last filter is inside it (the mouse wheel does not move it in the test harness; the containers are untouched by M9-10), at most 400 swipes (bounded, no sleeping).
        var notches = 0
        while (overflows && !inside(unclipped(last)) && notches < 400) {
            node("planner:reviewGraph").performTouchInput { swipeUp() }
            waitForIdle()
            notches++
        }
        val label = unclipped(last)
        assertTrue(inside(label), "the last filter ${label.toList()} is not inside the graph box ${box.toList()} after $notches notches ($routes routes, $width x $height); content height $contentHeight")
        if (overflows) assertTrue(onAllNodes(hasTestTag("planner:graphScrollbar"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the graph overflows (content $contentHeight in a box of ${box[3] - box[1]}) and shows no scrollbar")
        println("MANYROUTES $routes routes at $width x $height: graph box ${box[3] - box[1]} dp high, content $contentHeight dp, overflow=$overflows, notches=$notches")
        r.close()
    }

    @Test fun `20 routes at 1280 x 800`() = check(1280, 800, 20)
    @Test fun `20 routes at 1024 x 768`() = check(1024, 768, 20)
    @Test fun `100 routes at 1280 x 800`() = check(1280, 800, 100)
    @Test fun `100 routes at 1024 x 768`() = check(1024, 768, 100)
}
