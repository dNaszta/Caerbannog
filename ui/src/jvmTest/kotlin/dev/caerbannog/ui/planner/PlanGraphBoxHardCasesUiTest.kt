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

/**
 * M9-07 step 4 round 3 (test developer): the graph box (`planner:reviewGraph`) of a checked plan in the stacked layout, for two harder realistic
 * cases than the check screenshot: a 12 step plan, and a summary with every count nonzero and a 40 character connection name. The architect's floor
 * is 140 dp; a window of 1280 x 800 and the common laptop size 1366 x 768 are measured, with the broker read.
 */
@OptIn(ExperimentalTestApi::class)
class PlanGraphBoxHardCasesUiTest {
    private val sevenActions = listOf(
        MigrationAction.DeclareQueue("invoices"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareQueue("orders"),
        MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created"), MigrationAction.DeclareStream("telemetry"),
        MigrationAction.DeleteQueue("legacy-orders"), MigrationAction.DeleteQueue("gone-orders"),
    )

    private fun port() = FakeDeclareCheck()
        .answerNamed("invoices", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Different)
        .answerNamed("evnts", BrokerFinding.Absent).answerNamed("telemetry", BrokerFinding.Unreadable("The broker did not answer."))
        .answerNamed("legacy-orders", BrokerFinding.Same).answerNamed("gone-orders", BrokerFinding.Absent)

    private fun measure(w: Int, h: Int, connection: String?, actions: List<MigrationAction>, port: FakeDeclareCheck, readable: Boolean = true): Float {
        var box = 0f
        runDesktopComposeUiTest(w, h) {
            val r = UiRig(
                context = PlannerContext(controlLevel = TopologyControlLevel.DECLARE_AND_EDIT, connectionName = connection ?: "uat-eu"),
                declareCheck = port, reader = FakeKnownTopologyReader(if (readable) KnownTopology.None else null),
            ) { store.putDraft("", actions) }
            showPlanner(r)
            node("planner:checkBtn").performClick()
            waitForIdle()
            box = node("planner:reviewGraph").fetchSemanticsNode().size.height / density.density
            r.close()
        }
        return box
    }

    private val sizes = listOf(1280 to 800, 1366 to 768)

    @Test
    fun `seven results, broker read or not, in the four sizes`() {
        val low = buildList {
            for ((w, h) in listOf(1280 to 800, 1366 to 768, 1440 to 900, 1600 to 1000, 1920 to 1080)) for (read in listOf(true, false)) {
                val b = measure(w, h, null, sevenActions, port(), read)
                if (b < 140f) add("$w x $h read=$read: $b dp")
            }
        }
        assertEquals(emptyList(), low, "graph box under the 140 dp floor")
    }

    @Test
    fun `twelve checked steps keep a 140 dp graph box`() {
        val actions = (1..12).map { MigrationAction.DeclareQueue("queue-$it") }
        val low = sizes.mapNotNull { (w, h) ->
            val p = FakeDeclareCheck().also { f -> actions.forEach { f.answer(it, BrokerFinding.Absent) } }
            measure(w, h, null, actions, p).takeIf { it < 140f }?.let { "$w x $h: $it dp" }
        }
        assertEquals(emptyList(), low, "graph box under the floor with 12 steps")
    }

    @Test
    fun `a connection called prod-eu does not push the graph box under 140 dp at 1280 x 800`() {
        // Seven characters in "Check against prod-eu" make the header's action row wrap at 1280 (the chip drops to a second line, 48 dp), which the
        // body then lacks; "uat-eu", "stg-eu" and "prod" do not wrap. The steps panel then shows two whole rows, not three.
        val b = measure(1280, 800, "prod-eu", sevenActions, port())
        assertEquals(true, b >= 140f, "the graph box is $b dp with a connection called prod-eu")
    }

    @Test
    fun `a long summary with every count and a 40 character connection name keeps a 140 dp graph box`() {
        val name = "a-very-long-connection-name-of-40-chars!".take(40)
        val low = sizes.mapNotNull { (w, h) ->
            measure(w, h, name, sevenActions, port()).takeIf { it < 140f }?.let { "$w x $h: $it dp" }
        }
        assertEquals(emptyList(), low, "graph box under the floor with a long summary")
    }

    @Test
    fun `Save, Check and Dismiss stay on the window after a check of seven results at 1280 x 800 and 1366 x 768`() {
        val short = mutableListOf<String>()
        for ((w, h) in sizes) for (name in listOf("uat-eu", "prod-eu")) runDesktopComposeUiTest(w, h) {
            val r = UiRig(
                context = PlannerContext(controlLevel = TopologyControlLevel.DECLARE_AND_EDIT, connectionName = name), declareCheck = port(),
                reader = FakeKnownTopologyReader(KnownTopology.None),
            ) { store.putDraft("", sevenActions) }
            showPlanner(r)
            node("planner:checkBtn").performClick()
            waitForIdle()
            for (tag in listOf("planner:saveBtn", "planner:checkBtn", "planner:dismissBtn")) {
                val n = node(tag).fetchSemanticsNode()
                if (n.positionInRoot.y < 0f || n.positionInRoot.y + n.size.height > h) short += "$w x $h $name: $tag is off the window"
            }
            r.close()
        }
        assertEquals(emptyList(), short)
    }
}
