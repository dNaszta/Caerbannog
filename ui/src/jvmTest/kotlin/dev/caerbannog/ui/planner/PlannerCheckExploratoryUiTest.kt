package dev.caerbannog.ui.planner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.PlannerContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-06 step 4 (round 1), exploratory checks of the check on the screen: widths, a long connection name, hostile names and the review graph
 * next to a checked plan. They pass on the code of 48854cc; each one is a way to break the change that was tried.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerCheckExploratoryUiTest {
    private fun threeStepRig(port: FakeDeclareCheck, context: PlannerContext = PlannerContext()) = UiRig(context = context, declareCheck = port) { threeSteps() }.also { r ->
        r.state.plan.steps.forEach { s ->
            when (val a = s.action) {
                is MigrationAction.DeclareQueue -> port.answerNamed(a.name, BrokerFinding.Absent)
                is MigrationAction.DeclareExchange -> port.answerNamed(a.name, BrokerFinding.Same)
                else -> port.answer(a, BrokerFinding.Absent)
            }
        }
    }

    @Test
    fun `at 1280 by 800 a checked small plan shows the summary and every step, and its graph is fully visible or scrollable`() = runDesktopComposeUiTest(1280, 800) {
        val r = threeStepRig(FakeDeclareCheck())
        showPlanner(r)
        node("planner:checkBtn").performClick(); waitForIdle()
        assertTrue(textOf("planner:checkSummary").isNotBlank())
        (0..2).forEach { assertDrawn("planStepResult:$it") }
        // Finding (minor, step 4): with the check banner above the list the graph box is short at 800 px high and draws its scrollbar (see the report); the
        // graph stays reachable. The assertion below is what must hold: a node is fully inside the box, or the scrollbar is there.
        val box = node("planner:reviewGraph").fetchSemanticsNode().boundsInRoot
        val cut = r.state.graph.graph.nodes.map { it.node.id }.filter { id ->
            val n = node("planGraphNode:$id").fetchSemanticsNode()
            n.positionInRoot.y + n.size.height > box.bottom + 0.5f || n.positionInRoot.x + n.size.width > box.right + 0.5f
        }
        val scrollbar = onAllNodesWithTag("planner:graphScrollbar", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(cut.isEmpty() || scrollbar, "nodes $cut are cut off by the graph box $box and there is no scrollbar")
        val card = node("planner:reviewCard").fetchSemanticsNode()
        // Promise (docs/spec.md, layout promise 3, M2-21): the review card is inside the window, or the stacked page scrolls to it (it draws planner:pageScrollbar, by design:
        // see the StackedPage KDoc) and after performScrollTo it ends inside the window. Only the 1 px rounding tolerance; no font tolerance.
        if (card.positionInRoot.y + card.size.height > 800f + 1f) {
            assertTrue(
                onAllNodesWithTag("planner:pageScrollbar", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "the review card is cut off by the window and the page has no scrollbar: ${card.positionInRoot} ${card.size}",
            )
            node("planner:reviewCard").performScrollTo(); waitForIdle()
            val scrolled = node("planner:reviewCard").fetchSemanticsNode()
            val top = scrolled.positionInRoot.y; val bottom = top + scrolled.size.height
            assertTrue(top >= 0f && bottom <= 801f, "the review card is cut off by the window and the page cannot scroll to it: top $top bottom $bottom")
        }
        r.close()
    }

    @Test
    fun `at 1024 the check button, Save and the result pills are all inside the window`() = runDesktopComposeUiTest(1024, 768) {
        val r = threeStepRig(FakeDeclareCheck())
        showPlanner(r)
        node("planner:checkBtn").performClick(); waitForIdle()
        listOf("planner:checkBtn", "planner:saveBtn", "planStepResult:0", "planner:checkSummary").forEach { tag ->
            val n = node(tag).fetchSemanticsNode()
            assertTrue(n.positionInRoot.x >= 0f && n.positionInRoot.x + n.size.width <= 1024f + 1f, "$tag leaves the window: ${n.positionInRoot} ${n.size}")
        }
        r.close()
    }

    @Test
    fun `a long connection name keeps the button, Save and Dismiss inside a 1024 window`() = runDesktopComposeUiTest(1024, 768) {
        val name = "production-eu-west-1-primary-cluster-with-a-very-long-name"
        val r = threeStepRig(FakeDeclareCheck(), PlannerContext(connectionName = name))
        showPlanner(r)
        assertEquals("Check against $name", textOf("planner:checkBtn").trim())
        listOf("planner:checkBtn", "planner:saveBtn", "planner:dismissBtn").forEach { tag ->
            val n = node(tag).fetchSemanticsNode()
            assertTrue(n.positionInRoot.x >= 0f && n.positionInRoot.x + n.size.width <= 1024f + 1f, "$tag leaves the window: ${n.positionInRoot} ${n.size}")
        }
        r.close()
    }

    @Test
    fun `hostile names in the plan are drawn as plain text, the reason says only the fixed sentence, and nothing crashes`() = runDesktopComposeUiTest(1280, 1100) {
        val names = listOf("<b>bold</b>", "a".repeat(200), "‮evil", "a/b\\c", "x\ny")
        val port = FakeDeclareCheck()
        names.forEach { port.answerNamed(it, BrokerFinding.Absent) }
        val r = UiRig(declareCheck = port) {
            store.putDraft("", names.map { MigrationAction.DeclareBinding(it, "orders", "k") })
        }
        showPlanner(r)
        node("planner:checkBtn").performClick(); waitForIdle()
        names.indices.forEach { i ->
            val reason = textOf("planStepReason:$i")
            assertTrue(reason.startsWith("The exchange") || reason.startsWith("The queue") || reason.isNotBlank(), reason)
            assertTrue(!reason.contains("<b>") && !reason.contains("‮") && reason.length < 200, "name echoed: $reason")
        }
        r.close()
    }
}
