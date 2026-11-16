package dev.caerbannog.ui.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.planner.planPreview
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-04 step 2: the review card's Graph and JSON switch and the review graph (scenarios tagged @m9-04 in 19_topology_planner.feature).
 * The screen is driven by a real [PlannerViewModel] over fakes (see PlannerUiKit); the too large plan is a hand-made state. Everything the
 * graph says (marks, names, notices, legend) comes from `core`; these tests prove the screen draws it, tags it, and offers no control on it.
 *
 * The drawing code itself is shared with the Bindings graph (a pure move): `BindingsGraphUiTest` and the Topology screenshots are its gate.
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphUiTest {
    private val broker = KnownTopology(exchanges = mapOf("events" to "direct"), queues = setOf("audit", "orders-old"))

    private fun connectedRig(known: KnownTopology? = broker, prepare: UiRig.() -> Unit = {}) =
        UiRig(reader = FakeKnownTopologyReader(known), prepare = prepare)

    private fun DesktopComposeUiTest.description(tag: String): String =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    private fun DesktopComposeUiTest.drawnNodeTags(prefix: String): List<String> =
        onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()

    // ---- the switch ----

    @Test
    fun `the review card shows the graph first and the JSON text on request`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { threeSteps() }
        showPlanner(r)
        assertDrawn("planner:reviewGraph")
        assertNotDrawn("planner:jsonView")
        assertDrawn("planner:reviewViewSwitch")
        assertEquals(ReviewView.Graph, r.state.graphView)
        node("planner:reviewViewSwitch:Json").performClick()
        waitForIdle()
        assertEquals(listOf("selectReviewView:Json"), r.events.calls.filter { it.startsWith("selectReviewView") })
        assertNotDrawn("planner:reviewGraph")
        assertTrue(textOf("planner:jsonView").contains("\"version\": \"draft\""), textOf("planner:jsonView"))
        node("planner:jsonCopyBtn").assertIsEnabled()
        node("planner:reviewViewSwitch:Graph").performClick()
        waitForIdle()
        assertDrawn("planner:reviewGraph")
        assertNotDrawn("planner:jsonView")
        r.close()
    }

    @Test
    fun `the switch is a radio group that says which view is on`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { threeSteps() }
        showPlanner(r)
        fun selected(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
        assertEquals(true, selected("planner:reviewViewSwitch:Graph"))
        assertEquals(false, selected("planner:reviewViewSwitch:Json"))
        showJson()
        assertEquals(false, selected("planner:reviewViewSwitch:Graph"))
        assertEquals(true, selected("planner:reviewViewSwitch:Json"))
        r.close()
    }

    @Test
    fun `switching the view changes nothing in the plan or the draft`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { threeSteps(); vm.setDescription("keep me") }
        showPlanner(r)
        val plan = r.state.plan
        val drafts = r.store.savedTexts.toList()
        showJson()
        node("planner:reviewViewSwitch:Graph").performClick()
        waitForIdle()
        assertEquals(plan, r.state.plan)
        assertEquals(drafts, r.store.savedTexts)
        assertEquals(listOf("selectReviewView:Json", "selectReviewView:Graph"), r.events.calls)
        r.close()
    }

    @Test
    fun `an empty plan has nothing to draw and says so`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig()
        showPlanner(r)
        assertDrawn("planner:reviewEmpty")
        assertNotDrawn("planner:reviewGraph")
        r.close()
    }

    // ---- marks, names, legend ----

    @Test
    fun `new parts are marked, the broker's are not, and what the plan does not touch is not drawn`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("orders"); binding("events", "orders", "order.created") }
        showPlanner(r)
        assertDrawn("planGraphNode:q:orders")
        assertDrawn("planGraphNew:q:orders")
        assertDrawn("planGraphNode:e:events")
        assertNotDrawn("planGraphNew:e:events")
        assertDrawn("planGraphNew:edge-0")
        assertNotDrawn("planGraphNode:q:audit")
        assertNotDrawn("planGraphNode:q:orders-old")
        assertDrawn("edgePill:order.created")
        assertNotDrawn("planner:graphNotice")
        r.close()
    }

    @Test
    fun `a name the plan declares and the broker already has is drawn as existing`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("audit") }
        showPlanner(r)
        assertDrawn("planGraphNode:q:audit")
        assertNotDrawn("planGraphNew:q:audit")
        assertEquals("queue audit, already on the broker", description("planGraphNode:q:audit"))
        r.close()
    }

    @Test
    fun `every node says what it is and whether it is new`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("orders"); binding("events", "ghost", "k") }
        showPlanner(r)
        assertEquals("queue orders, new", description("planGraphNode:q:orders"))
        assertEquals("exchange events, already on the broker", description("planGraphNode:e:events"))
        assertEquals("queue ghost, not in this plan", description("planGraphNode:q:ghost"))
        r.close()
    }

    @Test
    fun `an end that the plan does not declare and the broker does not have is dotted and says not in this plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("orders"); binding("nowhere", "orders", "k") }
        showPlanner(r)
        assertEquals("exchange nowhere, not in this plan", description("planGraphNode:e:nowhere"))
        assertNotDrawn("planGraphNew:e:nowhere")
        assertDrawn("planGraphNotInPlan:e:nowhere") // the words on the node, not the dotted outline alone
        assertNotDrawn("planGraphNotInPlan:q:orders")
        r.close()
    }

    @Test
    fun `the legend names the marks`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { threeSteps() }
        showPlanner(r)
        for (entry in PlannerTexts.GRAPH_LEGEND) onNodeWithText(entry, useUnmergedTree = true).assertExists("legend entry missing: $entry")
        r.close()
    }

    @Test
    fun `the graph follows the plan when a step is removed`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("orders"); binding("events", "orders", "order.created") }
        showPlanner(r)
        assertDrawn("edgePill:order.created")
        r.vm.removeStep(r.state.plan.steps.last().id)
        waitForIdle()
        assertNotDrawn("edgePill:order.created")
        assertNotDrawn("planGraphNew:edge-0")
        assertDrawn("planGraphNode:q:orders")
        assertNotDrawn("planGraphNode:e:events")
        r.close()
    }

    // ---- notices ----

    @Test
    fun `without a connection only the plan is drawn and a line says so`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(context = dev.caerbannog.core.planner.PlannerContext(connectionName = null), reader = null) { queue("orders"); binding("events", "orders", "k") }
        showPlanner(r)
        val notice = textOf("planner:graphNotice")
        assertTrue(notice.contains("Only the plan is drawn") && notice.contains("Not connected: what already exists on a broker is not shown."), notice)
        assertEquals("exchange events, not in this plan", description("planGraphNode:e:events"))
        assertDrawn("planGraphNew:q:orders")
        r.close()
    }

    @Test
    fun `when the broker could not be read only the plan is drawn and the line says the broker could not be read`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig(known = null) { queue("orders"); binding("events", "orders", "k") }
        showPlanner(r)
        val notice = textOf("planner:graphNotice")
        assertTrue(notice.contains("Only the plan is drawn") && notice.contains("The broker could not be read: what already exists is not shown."), notice)
        assertEquals("exchange events, not in this plan", description("planGraphNode:e:events"))
        assertDrawn("planGraphNew:q:orders")
        r.close()
    }

    @Test
    fun `a plan that is too large to draw says so, draws no node and keeps the step list`() = runDesktopComposeUiTest(1280, 800) {
        val plan = TopologyPlan().append((1..61).map { MigrationAction.DeclareQueue(name = "q$it") })
        showState(PlannerState(plan = plan, graph = planPreview(plan, KnownTopology.None), isLoaded = true))
        val notice = textOf("planner:graphNotice")
        assertTrue(notice.contains("This plan is too large to draw") && notice.contains("The step list is the review."), notice)
        assertEquals(emptyList(), drawnNodeTags("planGraphNode:"))
        assertDrawn("planner:stepList")
        assertDrawn("planStep:0")
    }

    // ---- keyboard and read only ----

    @Test
    fun `graph nodes can be focused with the keyboard and carry their name`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { queue("orders"); binding("events", "orders", "k") }
        showPlanner(r)
        for (id in listOf("q:orders", "e:events")) {
            val n = node("planGraphNode:$id")
            assertTrue(n.fetchSemanticsNode().config.contains(SemanticsActions.RequestFocus), "node $id cannot take focus, so Tab cannot reach it")
            n.requestFocus()
            waitForIdle()
            assertEquals(true, node("planGraphNode:$id").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused), "node $id did not take focus")
        }
        assertEquals("queue orders, new", description("planGraphNode:q:orders"))
        r.close()
    }

    /** Every semantics node under the node tagged [tag] that offers an action that changes something (a click, a long click, a text, a toggle). */
    private fun DesktopComposeUiTest.actionsUnder(tag: String): List<String> {
        val acting = listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetText, SemanticsActions.SetSelection)
        fun walk(n: SemanticsNode): List<String> =
            acting.filter { n.config.contains(it) }.map { "${it.name} on ${n.config.getOrNull(SemanticsProperties.TestTag) ?: "an untagged node"}" } + n.children.flatMap { walk(it) }
        return walk(node(tag).fetchSemanticsNode())
    }

    @Test
    fun `no control on the graph adds, changes, moves or removes a step`() = runDesktopComposeUiTest(1280, 800) {
        val r = connectedRig { threeSteps() }
        showPlanner(r)
        assertEquals(emptyList(), actionsUnder("planner:reviewGraph"), "the review graph offers an action")
        val before = r.state.plan
        val calls = r.events.calls.size
        for (tag in drawnNodeTags("planGraphNode:") + drawnNodeTags("edgePill:")) {
            node(tag).performClick()
            waitForIdle()
        }
        assertEquals(before, r.state.plan)
        assertEquals(calls, r.events.calls.size, "a click on the graph reached a planner event: ${r.events.calls.drop(calls)}")
        r.close()
    }

    @Test
    fun `the read only check would see a click action if there were one`() = runDesktopComposeUiTest(800, 600) {
        setContent {
            Column(Modifier.testTag("planner:reviewGraph")) {
                Box(Modifier.size(40.dp).testTag("planGraphNode:probe").clickable { }) { Text("probe") }
            }
        }
        waitForIdle()
        assertTrue(actionsUnder("planner:reviewGraph").isNotEmpty(), "the scan is blind: a clickable node was not seen")
    }
}
