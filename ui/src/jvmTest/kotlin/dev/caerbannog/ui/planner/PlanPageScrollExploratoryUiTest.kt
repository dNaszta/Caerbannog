package dev.caerbannog.ui.planner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
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
 * M9-07 step 4 round 4 (test developer): the scrollable stacked page. Windows from 768 down to 700 dp high, other densities. For each: the graph box
 * keeps 140 dp, OR the page draws its scrollbar (`planner:pageScrollbar`) and the card and graph can be scrolled into view; nothing is clipped
 * without a bar; Save, Check and Dismiss stay in the window.
 */
@OptIn(ExperimentalTestApi::class)
class PlanPageScrollExploratoryUiTest {
    private val seven = listOf(
        MigrationAction.DeclareQueue("invoices"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareQueue("orders"),
        MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created"), MigrationAction.DeclareStream("telemetry"),
        MigrationAction.DeleteQueue("legacy-orders"), MigrationAction.DeleteQueue("gone-orders"),
    )

    private fun port() = FakeDeclareCheck()
        .answerNamed("invoices", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Different)
        .answerNamed("evnts", BrokerFinding.Absent).answerNamed("telemetry", BrokerFinding.Unreadable("The broker did not answer."))
        .answerNamed("legacy-orders", BrokerFinding.Same).answerNamed("gone-orders", BrokerFinding.Absent)

    private class Result(val graphDp: Float, val pageBar: Boolean, val cardClipped: Boolean, val graphClipped: Boolean, val buttonsOut: List<String>, val reachable: Boolean)

    private fun probe(w: Int, h: Int, density: Float, readable: Boolean, name: String = "uat-eu", actions: List<MigrationAction> = seven, steps3: Boolean = false): Result {
        lateinit var out: Result
        runDesktopComposeUiTest(w, h) {
            val p = if (steps3) FakeDeclareCheck().also { f -> actions.forEach { f.answer(it, BrokerFinding.Absent) } } else port()
            val r = UiRig(
                context = PlannerContext(controlLevel = TopologyControlLevel.DECLARE_AND_EDIT, connectionName = name), declareCheck = p,
                reader = FakeKnownTopologyReader(if (readable) KnownTopology.None else null),
            ) { store.putDraft("", actions) }
            setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(density)) { PlannerOver(r) } }
            waitForIdle()
            node("planner:checkBtn").performClick()
            waitForIdle()
            val bar = PlannerNodeTags.withPrefix(this, "planner:pageScrollbar").isNotEmpty()
            fun rect(tag: String) = node(tag).fetchSemanticsNode().let { floatArrayOf(it.positionInRoot.y, it.positionInRoot.y + it.size.height) }
            val card = rect("planner:reviewCard")
            val graph = rect("planner:reviewGraph")
            val out2 = listOf("planner:saveBtn", "planner:checkBtn", "planner:dismissBtn").filter { val b = rect(it); b[0] < 0f || b[1] > h }
            val dp = (graph[1] - graph[0]) / density
            val cardClipped = card[1] > h + 1f
            val graphClipped = graph[1] > h + 1f
            var reachable = true
            if (cardClipped || graphClipped) {
                reachable = runCatching { node("planner:reviewCard").performScrollTo(); waitForIdle(); true }.getOrDefault(false)
            }
            out = Result(dp, bar, cardClipped, graphClipped, out2, reachable)
            r.close()
        }
        return out
    }

    private val windows = listOf(1280 to 800, 1366 to 768, 1440 to 900, 1600 to 1000, 1280 to 720, 1024 to 768, 1366 to 700)

    @Test
    fun `seven results in every window keep 140 dp or scroll the page with its bar, never clip without a bar`() {
        val bad = mutableListOf<String>()
        val all = StringBuilder()
        for ((w, h) in windows) for (read in listOf(true, false)) {
            val x = probe(w, h, 1f, read)
            all.append("$w x $h read=$read graph=${x.graphDp} bar=${x.pageBar} cardClipped=${x.cardClipped} reach=${x.reachable}\n")
            if (x.graphDp < 140f && !x.pageBar) bad += "$w x $h read=$read: graph ${x.graphDp} dp and no page scrollbar"
            if ((x.cardClipped || x.graphClipped) && !x.pageBar) bad += "$w x $h read=$read: clipped without a page scrollbar"
            if ((x.cardClipped || x.graphClipped) && !x.reachable) bad += "$w x $h read=$read: card cannot be scrolled into view"
            if (x.buttonsOut.isNotEmpty()) bad += "$w x $h read=$read: ${x.buttonsOut} off the window"
        }
        println("MEASURE\n$all")
        assertEquals(emptyList(), bad, all.toString())
    }

    @Test
    fun `the tight three step case at 1280 x 800 survives other densities - clipped content has a page scrollbar`() {
        val three = listOf(MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareBinding("events", "orders", "order.created"))
        val bad = mutableListOf<String>()
        for (d in listOf(1f, 1.25f, 1.5f, 2f)) for (read in listOf(true, false)) {
            val x = probe(1280, 800, d, read, actions = three, steps3 = true)
            if ((x.cardClipped || x.graphClipped) && !x.pageBar) bad += "density $d read=$read: card clipped (graph ${x.graphDp} dp) and no page scrollbar"
            if (x.buttonsOut.isNotEmpty()) bad += "density $d read=$read: ${x.buttonsOut} off the window"
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun `twelve steps and long names keep 140 dp or scroll`() {
        val twelve = (1..12).map { MigrationAction.DeclareQueue("queue-$it") }
        val bad = mutableListOf<String>()
        for ((w, h) in listOf(1280 to 800, 1366 to 768)) {
            val a = probe(w, h, 1f, true, actions = twelve, steps3 = true)
            if (a.graphDp < 140f && !a.pageBar) bad += "12 steps $w x $h: ${a.graphDp} dp no bar"
            for (n in listOf("prod-eu", "a-very-long-connection-name-of-40-chars!".take(40))) {
                val b = probe(w, h, 1f, true, name = n)
                if (b.graphDp < 140f && !b.pageBar) bad += "$n $w x $h: ${b.graphDp} dp no bar"
                if ((b.cardClipped) && !b.pageBar) bad += "$n $w x $h clipped no bar"
            }
        }
        assertEquals(emptyList(), bad)
    }
}
