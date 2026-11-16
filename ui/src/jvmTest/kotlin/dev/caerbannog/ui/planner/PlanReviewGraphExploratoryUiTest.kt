package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-04 step 4 (test developer): hostile names reach the review graph's accessible names whole, and the screen stays up. */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphExploratoryUiTest {
    private val hostile = listOf(
        "<b>bold</b>&amp;<script>alert(1)</script>",
        "x".repeat(200),
        "‮evil‬ שלום",
    )

    @Test
    fun `hostile names are drawn as text, named whole for a screen reader, and never change the plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
            hostile.forEach { queue(it) }
            exchange(hostile[0])
            binding(hostile[0], hostile[1], hostile[2])
        }
        showPlanner(r)
        val steps = r.state.plan.steps.size
        assertTrue(steps >= 4, "the rig must hold the hostile steps, got $steps")
        assertDrawn("planner:reviewGraph")
        hostile.forEach { name ->
            val tag = "planGraphNode:q:$name"
            val description = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
            assertEquals("queue $name, new", description)
        }
        assertEquals(steps, r.state.plan.steps.size)
        r.close()
    }
}
