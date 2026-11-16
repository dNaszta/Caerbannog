package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07 step 2 (additions to `PlanPreviewTest`, own file so the M9-04 file stays as it was): `planPreview` fills `links` with
 * `PlanLink(request node id, response node id, PlanLinkKind.ResponseStream)` from `PlanPairs`. Node ids are `q:<name>`; the other placeholders
 * (`PlanLinkKind.DeadLetter`, `notes`) stay empty until M9-09 and M9-07b. A link is derived from names, never from the broker.
 */
class PlanPreviewResponseStreamTest {
    private fun stream(name: String) = MigrationAction.DeclareStream(name = name)

    @Test
    fun `a pair has one response stream link from the request to the response`() {
        val g = planPreview(planOf(stream("telemetry"), stream("telemetry-dln")), KnownTopology.None)
        assertEquals(listOf(PlanLink("q:telemetry", "q:telemetry-dln", PlanLinkKind.ResponseStream)), g.links)
        assertEquals(setOf("q:telemetry", "q:telemetry-dln"), g.newNodeIds, "both are new")
        assertEquals(emptyList(), g.graph.edges, "a link is not an edge: no routing between the two")
        assertEquals(emptyList(), g.notes)
    }

    @Test
    fun `the link follows each style's name`() {
        for (style in NamingStyle.entries) {
            val response = PlanNaming.derived("orders", DerivedWord.DLN, style)
            val g = planPreview(planOf(stream("orders"), stream(response)), KnownTopology.None)
            assertEquals(listOf(PlanLink("q:orders", "q:$response", PlanLinkKind.ResponseStream)), g.links, "style $style")
        }
    }

    @Test
    fun `no pair, no link`() {
        assertEquals(emptyList(), planPreview(planOf(stream("telemetry"), stream("telemetry-replies")), KnownTopology.None).links)
        assertEquals(emptyList(), planPreview(planOf(stream("telemetry-dln")), KnownTopology.None).links)
        assertEquals(emptyList(), planPreview(TopologyPlan(), KnownTopology.None).links)
    }

    @Test
    fun `a pair with an end the broker already has is still linked and the end is existing`() {
        val known = KnownTopology(streams = setOf("telemetry"))
        val g = planPreview(planOf(stream("telemetry"), stream("telemetry-dln")), known)
        assertEquals(listOf(PlanLink("q:telemetry", "q:telemetry-dln", PlanLinkKind.ResponseStream)), g.links)
        assertEquals(PlanMark.Existing, g.markOf("q:telemetry"))
        assertEquals(PlanMark.New, g.markOf("q:telemetry-dln"))
    }

    @Test
    fun `the broker's own pair is not drawn and not linked, only the plan's names are paired`() {
        val known = KnownTopology(streams = setOf("sensors", "sensors-dln"))
        assertEquals(emptyList(), planPreview(planOf(stream("sensors")), known).links)
    }

    @Test
    fun `a link never points at a node the graph does not draw`() {
        val g = planPreview(planOf(stream("a"), stream("a-dln"), stream("b"), stream("b-dln")), KnownTopology.None)
        val ids = g.graph.nodes.map { it.node.id }.toSet()
        assertEquals(2, g.links.size)
        assertTrue(g.links.all { it.sourceId in ids && it.targetId in ids }, "links ${g.links} ids $ids")
    }

    @Test
    fun `the same plan gives the same links and an unknown graph still draws them`() {
        val plan = planOf(stream("telemetry"), stream("telemetry-dln"))
        assertEquals(planPreview(plan, null, OnlyThePlanCause.BrokerNotRead).links, planPreview(plan, KnownTopology.None).links)
        assertEquals(1, planPreview(plan, null, pending = true).links.size)
    }

    @Test
    fun `a plan too large to draw keeps its notice and the links are not drawn from it`() {
        val many = (1..70).flatMap { listOf(stream("s$it"), stream("s$it-dln")) }
        val g = planPreview(planOf(*many.toTypedArray()), KnownTopology.None)
        assertEquals(PlanGraphNotice.TooLarge, g.notice)
    }

    @Test
    fun `the label of a link is the fixed text`() {
        assertEquals("response (DLN)", PlannerTexts.RESPONSE_LINK_LABEL)
    }
}
