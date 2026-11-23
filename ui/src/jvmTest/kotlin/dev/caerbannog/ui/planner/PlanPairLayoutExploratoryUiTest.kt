package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07 step 4 (test developer): the stacked layout after `STACKED_STEPS_SHARE`, over widths and step counts; and the pair link in the review
 * graph (a link label against a routing key pill, two pairs in one column, a link across a node).
 */
@OptIn(ExperimentalTestApi::class)
class PlanPairLayoutExploratoryUiTest {
    private fun DesktopComposeUiTest.rect(tag: String) = node(tag).fetchSemanticsNode().let {
        floatArrayOf(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private fun mixed(n: Int): UiRig.() -> Unit = { (1..n).forEach { i -> if (i % 3 == 0) stream("s$i", withResponse = false) else queue("q$i") } }

    @Test
    fun `the stacked and the side by side layouts keep every step reachable and the card on the page, for 1 3 7 12 and 30 steps`() {
        var chrome = 0f
        // chrome only turns a wanted content width into a window width; it is not a promise.
        runDesktopComposeUiTest(1280, 800) { val r = UiRig(); showPlanner(r); chrome = 1280f - node("planner:reviewCard").fetchSemanticsNode().size.width; r.close() }
        val problems = mutableListOf<String>()
        for (content in listOf(1024f - chrome, 1280f - chrome, 1399f, 1400f)) {
            val w = (content + chrome).toInt() + 1
            for (n in listOf(1, 3, 7, 12, 30)) {
                runDesktopComposeUiTest(w, 800) {
                    val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None), prepare = mixed(n))
                    showPlanner(r)
                    val list = rect("planner:stepList"); val card = rect("planner:reviewCard")
                    val where = "window $w x 800, $n steps"
                    val stacked = card[1] >= list[3] - 1f
                    // Promise (docs/spec.md, layout promise 3, M2-21): a stacked card is inside the window, or the page scrolls to it (planner:pageScrollbar, by design:
                    // the StackedPage KDoc) and after performScrollTo it ends inside the window. Side by side has no page scroll, so there it stays strict (below).
                    if (stacked && card[3] > 800f + 1f) {
                        if (onAllNodesWithTag("planner:pageScrollbar", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                            problems += "$where: stacked card ends at ${card[3]}, below the window, and the page has no scrollbar"
                        } else {
                            node("planner:reviewCard").performScrollTo(); waitForIdle()
                            val scrolled = rect("planner:reviewCard")
                            if (scrolled[1] < 0f || scrolled[3] > 801f) problems += "$where: stacked card cannot be scrolled into the window: top ${scrolled[1]} bottom ${scrolled[3]}"
                        }
                    }
                    if (stacked && card[3] - card[1] < 150f) problems += "$where: stacked card is only ${card[3] - card[1]} px high"
                    if (!stacked && card[3] > 800f + 1f) problems += "$where: side by side card ends at ${card[3]}"
                    if (list[3] > card[1] + 1f && stacked.not() && list[2] > card[0] + 1f) problems += "$where: list and card overlap"
                    node("planner:stepList").performScrollToIndex(n - 1)
                    waitForIdle()
                    val last = rect("planStep:${n - 1}")
                    val l2 = rect("planner:stepList")
                    if (last[3] > l2[3] + 1f || last[1] < l2[1] - 1f) problems += "$where: the last step cannot be scrolled into its panel"
                    r.close()
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    // ---- the link in the graph ----

    @Test
    fun `a link label does not sit on a routing key pill of a binding next to it`() = runDesktopComposeUiTest(1600, 1000) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
            exchange("events"); stream("telemetry", withResponse = true); binding("events", "telemetry", "sensor.temp"); binding("events", "telemetry-dln", "answer.temp")
        }
        showPlanner(r)
        val link = rect("planGraphLink:q:telemetry:q:telemetry-dln")
        val pills = PlannerNodeTags.withPrefix(this, "edgePill:").map { rect(it) }
        val hits = pills.filter { p -> p[0] < link[2] && link[0] < p[2] && p[1] < link[3] && link[1] < p[3] }
        assertEquals(0, hits.size, "the link label $link overlaps routing key pills $hits")
        r.close()
    }

    @Test
    fun `no link label overlaps another, for 2 3 and 10 pairs`() {
        for (pairs in listOf(2, 3, 10)) runDesktopComposeUiTest(1600, 1000) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { (1..pairs).forEach { stream("s$it", withResponse = true) } }
            showPlanner(r)
            val tags = PlannerNodeTags.withPrefix(this, "planGraphLink:")
            assertEquals(pairs, tags.size)
            val rs = tags.map { rect(it) }
            for (i in rs.indices) for (j in i + 1 until rs.size) {
                val a = rs[i]; val b = rs[j]
                assertTrue(!(a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]), "$pairs pairs: labels $i and $j overlap: ${a.toList()} ${b.toList()}")
            }
            r.close()
        }
    }

    @Test
    fun `a link label does not cover a node`() {
        for (pairs in listOf(1, 2, 3, 10)) runDesktopComposeUiTest(1600, 1000) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
                exchange("events"); (1..pairs).forEach { stream("s$it", withResponse = true); binding("events", "s$it", "k$it") }
            }
            showPlanner(r)
            val nodes = PlannerNodeTags.withPrefix(this, "planGraphNode:").map { rect(it) }
            PlannerNodeTags.withPrefix(this, "planGraphLink:").forEach { tag ->
                val l = rect(tag)
                val hit = nodes.filter { n -> n[0] < l[2] && l[0] < n[2] && n[1] < l[3] && l[1] < n[3] }
                assertEquals(0, hit.size, "$pairs pairs: $tag covers a node")
            }
            r.close()
        }
    }

    @Test
    fun `sixty one streams of pairs show the too large notice and no link`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { (1..31).forEach { stream("p$it", withResponse = true) } }
        showPlanner(r)
        assertDrawn("planner:graphNotice")
        assertEquals(emptyList(), PlannerNodeTags.withPrefix(this, "planGraphLink:"))
        r.close()
    }
}
