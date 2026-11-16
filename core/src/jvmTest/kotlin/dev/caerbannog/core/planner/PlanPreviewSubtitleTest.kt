package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-06 step 2 (owner decision of 2026-11-16, M9-04 review item 8): the subtitle of a node of the REVIEW graph is the mockup's form, "exchange, direct"
 * and "queue, quorum" (`docs/ux/Planner.dc.html`), not the Topology screen's "(topic)". The subtitle stays `GraphNode.detail` of the plan graph; the
 * Topology screen's own graph (`buildBindingGraph` on its own) keeps its words, and its tests are not changed.
 */
class PlanPreviewSubtitleTest {
    private fun graphOf(vararg actions: MigrationAction, known: KnownTopology? = KnownTopology.None) =
        planPreview(TopologyPlan.of("", actions.toList(), "/"), known)

    private fun PlanGraph.detailOf(id: String) = graph.nodes.first { it.node.id == id }.node.detail

    @Test
    fun `an exchange of the plan says exchange and its type`() {
        val g = graphOf(
            MigrationAction.DeclareExchange("a", "direct"), MigrationAction.DeclareExchange("b", "fanout"),
            MigrationAction.DeclareExchange("c", "topic"), MigrationAction.DeclareExchange("d", "headers"),
        )
        assertEquals("exchange, direct", g.detailOf("e:a"))
        assertEquals("exchange, fanout", g.detailOf("e:b"))
        assertEquals("exchange, topic", g.detailOf("e:c"))
        assertEquals("exchange, headers", g.detailOf("e:d"))
    }

    @Test
    fun `a queue of the plan says queue and its type`() {
        val g = graphOf(MigrationAction.DeclareQueue("orders", "quorum"), MigrationAction.DeclareQueue("old", "classic"))
        assertEquals("queue, quorum", g.detailOf("q:orders"))
        assertEquals("queue, classic", g.detailOf("q:old"))
    }

    @Test
    fun `an exchange that only the broker has says the type the broker has`() {
        val known = KnownTopology(exchanges = mapOf("amq.topic" to "topic"), queues = setOf("orders"))
        val g = graphOf(MigrationAction.DeclareBinding("amq.topic", "orders", "k"), known = known)
        assertEquals("exchange, topic", g.detailOf("e:amq.topic"))
    }

    @Test
    fun `no node of the review graph uses the parenthesis form, streams included`() {
        val g = graphOf(
            MigrationAction.DeclareExchange("a", "direct"), MigrationAction.DeclareQueue("orders", "quorum"),
            MigrationAction.DeclareBinding("a", "orders", "k"), MigrationAction.DeclareStream("log"),
        )
        g.graph.nodes.forEach { assertEquals(false, it.node.detail.contains("("), "${it.node.id}: \"${it.node.detail}\"") }
    }

    @Test
    fun `a stream of the plan and a stream of the broker both say stream, with no parenthesis and no count`() {
        val declared = graphOf(MigrationAction.DeclareStream("log"))
        assertEquals("stream", declared.detailOf("q:log"))
        val known = KnownTopology(exchanges = mapOf("events" to "direct"), streams = setOf("audit"))
        val g = graphOf(MigrationAction.DeclareBinding("events", "audit", "k"), known = known)
        assertEquals("stream", g.detailOf("q:audit"))
    }
}
