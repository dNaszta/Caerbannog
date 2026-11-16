package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07 step 2: the pair in the step list and the dotted link in the review graph (mockup steps 8 and 9, section 8, 1280 x 800). The response
 * stream's row has a second line "Response stream (DLN) of <name>" (tag `planStepPair:<i>`), is indented under its request stream, and is drawn
 * from the NAMES of the plan (`PlanPairs`): the same after a restart of the draft, gone when the names no longer follow the rule. The graph draws
 * `planGraphLink:<source id>:<target id>` with the text `PlannerTexts.RESPONSE_LINK_LABEL` for each pair, and nothing between two streams that
 * are not a pair. Nothing here offers an action on a link (read only).
 */
@OptIn(ExperimentalTestApi::class)
class PlanStepListPairUiTest {
    // ---- the step list ----

    @Test
    fun `the response stream's row says whose response it is and the request's row says nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        assertEquals("Response stream (DLN) of telemetry", textOf("planStepPair:1"))
        assertNotDrawn("planStepPair:0")
        assertTrue(textOf("planStep:1").contains("Response stream (DLN) of telemetry"), "the row's text: ${textOf("planStep:1")}")
        assertTrue(!textOf("planStep:0").contains("Response stream"), textOf("planStep:0"))
        r.close()
    }

    @Test
    fun `the response stream's row is indented under its request stream`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        val request = onNodeWithText("Declare stream telemetry", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val response = onNodeWithText("Declare stream telemetry-dln", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue(response > request + 4f, "the response stream's title starts at x=$response, its request's at x=$request: no indent")
        r.close()
    }

    @Test
    fun `two streams whose names do not follow the rule are plain rows`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry"); stream("telemetry-replies") }
        showPlanner(r)
        assertNotDrawn("planStepPair:0")
        assertNotDrawn("planStepPair:1")
        r.close()
    }

    @Test
    fun `a saved pair is shown as a pair again when the draft is opened`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig {
            store.putDraft("", listOf(MigrationAction.DeclareStream("telemetry"), MigrationAction.DeclareStream("telemetry-dln")))
        }
        showPlanner(r)
        assertEquals("Response stream (DLN) of telemetry", textOf("planStepPair:1"))
        r.close()
    }

    @Test
    fun `removing the request stream leaves the response stream as a plain row`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:remove").performClick()
        assertEquals(listOf("Declare stream telemetry-dln"), r.state.plan.steps.map { it.title })
        assertNotDrawn("planStepPair:0")
        assertTrue(textOf("planStep:0").contains("Declare stream telemetry-dln"))
        r.close()
    }

    @Test
    fun `a pair among other steps keeps the other rows as they were`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { queue("jobs"); stream("telemetry", withResponse = true); exchange("events") }
        showPlanner(r)
        assertNotDrawn("planStepPair:0")
        assertNotDrawn("planStepPair:1")
        assertEquals("Response stream (DLN) of telemetry", textOf("planStepPair:2"))
        assertNotDrawn("planStepPair:3")
        r.close()
    }

    @Test
    fun `a name that may not be echoed is left out of the line and the line stays whole`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("a b", withResponse = true) }
        showPlanner(r)
        assertEquals("Response stream (DLN)", textOf("planStepPair:1"))
        r.close()
    }

    // ---- the review graph ----

    @Test
    fun `the graph draws both streams as new and the dotted link between them`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { stream("telemetry", withResponse = true) }
        showPlanner(r)
        assertDrawn("planGraphNode:q:telemetry")
        assertDrawn("planGraphNode:q:telemetry-dln")
        assertDrawn("planGraphNew:q:telemetry")
        assertDrawn("planGraphNew:q:telemetry-dln")
        assertEquals("response (DLN)", textOf("planGraphLink:q:telemetry:q:telemetry-dln"))
        assertEquals(listOf("planGraphLink:q:telemetry:q:telemetry-dln"), PlannerNodeTags.withPrefix(this, "planGraphLink:"))
        assertEquals(emptyList(), PlannerNodeTags.withPrefix(this, "edgePill:"), "a link is not a routing key")
        r.close()
    }

    @Test
    fun `no link between streams that are not a pair`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { stream("telemetry"); stream("telemetry-replies") }
        showPlanner(r)
        assertEquals(emptyList(), PlannerNodeTags.withPrefix(this, "planGraphLink:"))
        r.close()
    }

    @Test
    fun `the link is drawn while only the plan is known too`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        assertEquals("response (DLN)", textOf("planGraphLink:q:telemetry:q:telemetry-dln"))
        r.close()
    }

    @Test
    fun `a link offers no action`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { stream("telemetry", withResponse = true) }
        showPlanner(r)
        val before = r.state.plan
        node("planGraphLink:q:telemetry:q:telemetry-dln").performClick()
        assertEquals(before, r.state.plan)
        r.close()
    }
}

/** The test tags drawn with a prefix; local to the M9-07 tests. */
@OptIn(ExperimentalTestApi::class)
internal object PlannerNodeTags {
    fun withPrefix(ui: DesktopComposeUiTest, prefix: String): List<String> =
        ui.onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
}
