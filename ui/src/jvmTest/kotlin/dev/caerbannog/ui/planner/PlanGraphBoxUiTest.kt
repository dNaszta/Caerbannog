package dev.caerbannog.ui.planner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07 step 4 round 2 (test developer): the architect's intent is that a checked long plan keeps a readable review graph (a graph box of at least
 * 140 dp). A1 pins it for seven uniform queues; these pin it for the plan of the check screenshot (seven steps, seven different results, with
 * reasons and a long summary), measured on the graph box itself (`planner:reviewGraph`), because the card's own content (title, summary and caveat,
 * legend, the "Only the plan is drawn" notice, the note) takes 136 to 216 dp of a card the floor sizes at 288. RED on `98860b2`: a floor on the card
 * is not a floor on the graph box.
 */
@OptIn(ExperimentalTestApi::class)
class PlanGraphBoxUiTest {
    private fun graphBoxDp(w: Int, h: Int, readable: Boolean): Pair<Float, Boolean> {
        var box = 0f
        var notice = false
        runDesktopComposeUiTest(w, h) {
            val port = FakeDeclareCheck()
            port.answerNamed("invoices", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Different)
                .answerNamed("evnts", BrokerFinding.Absent).answerNamed("telemetry", BrokerFinding.Unreadable("The broker did not answer."))
                .answerNamed("legacy-orders", BrokerFinding.Same).answerNamed("gone-orders", BrokerFinding.Absent)
            val r = UiRig(
                context = PlannerContext(controlLevel = TopologyControlLevel.DECLARE_AND_EDIT), declareCheck = port,
                reader = if (readable) FakeKnownTopologyReader(KnownTopology.None) else FakeKnownTopologyReader(null),
            ) {
                store.putDraft(
                    "",
                    listOf(
                        MigrationAction.DeclareQueue("invoices"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareQueue("orders"),
                        MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created"), MigrationAction.DeclareStream("telemetry"),
                        MigrationAction.DeleteQueue("legacy-orders"), MigrationAction.DeleteQueue("gone-orders"),
                    ),
                )
            }
            showPlanner(r)
            node("planner:checkBtn").performClick()
            waitForIdle()
            box = node("planner:reviewGraph").fetchSemanticsNode().size.height / density.density
            notice = PlannerNodeTags.withPrefix(this, "planner:graphNotice").isNotEmpty()
            r.close()
        }
        return box to notice
    }

    @Test
    fun `the graph box of the seven result plan is at least 140 dp high in the sizes of a laptop and a desktop`() {
        val low = listOf(1280 to 800, 1440 to 900, 1600 to 1000).mapNotNull { (w, h) ->
            val (box, _) = graphBoxDp(w, h, readable = true)
            "$w x $h: $box dp".takeIf { box < 140f }
        }
        assertEquals(emptyList(), low, "the graph box is below the floor of 140 dp (broker read, no notice)")
    }

    @Test
    fun `with the notice the plan is only drawn the graph box still keeps 140 dp at 1600 x 1000`() {
        val (box, notice) = graphBoxDp(1600, 1000, readable = false)
        assertTrue(notice, "the notice is drawn")
        assertTrue(box >= 140f, "with the \"Only the plan is drawn\" notice the graph box is $box dp")
    }
}
