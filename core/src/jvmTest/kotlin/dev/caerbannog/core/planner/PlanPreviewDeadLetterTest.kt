package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-09 step 2, written first: `planPreview` fills `links` with `PlanLink(queue node, exchange node, PlanLinkKind.DeadLetter)` (function `deadLetterLinks(plan)`
 * next to `responseLinks(plan)`): for each `DeclarePolicy` that holds `dead-letter-exchange` (text) whose pattern `ExactPattern.nameOf` reads as exactly ONE name,
 * and for each `DeclareQueue` whose arguments carry `x-dead-letter-exchange` as text. Derived from names, never from the broker. The source queue is drawn even when
 * the plan does not declare it, and is existing when `known` has it. The edge label is `PlannerTexts.DEAD_LETTER_EDGE_LABEL` ("dead letters"). RED until built.
 * One inference beyond the brief, from the existing rule "a link never points at a node the graph does not draw" (PlanPreviewResponseStreamTest): the exchange end
 * of a link is drawn too, so a hand-written policy that names an exchange of no step still has both ends.
 */
class PlanPreviewDeadLetterTest {
    private fun policy(name: String, pattern: String, vararg definition: Pair<String, ArgumentValue>, applyTo: String = "queues") =
        MigrationAction.DeclarePolicy(name = name, pattern = pattern, definition = mapOf(*definition), applyTo = applyTo)

    private fun dlx(exchange: String) = "dead-letter-exchange" to ArgumentValue.Text(exchange)
    private val deadLetter = PlanLinkKind.DeadLetter

    @Test
    fun `the label of the edge is the constant, as written`() {
        assertEquals("dead letters", PlannerTexts.DEAD_LETTER_EDGE_LABEL)
    }

    @Test
    fun `the recipe's four steps draw the path and one dead-letter link from the queue to the exchange`() {
        val ctx = ctx(known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", null))))
        val plan = TopologyPlan().append(DeadLetterForm("orders").actions(ctx))
        val g = planPreview(plan, ctx.known)
        assertEquals(listOf(PlanLink("q:orders", "e:orders-dlx", deadLetter)), g.links)
        assertEquals(setOf("q:orders", "e:orders-dlx", "q:orders-dlq"), g.graph.nodes.map { it.node.id }.toSet())
        assertEquals(listOf("e:orders-dlx" to "q:orders-dlq"), g.graph.edges.map { it.edge.sourceId to it.edge.targetId }, "the binding is the one edge; the link is none")
        assertEquals(PlanMark.Existing, g.markOf("q:orders"), "the source is already on the broker")
        assertEquals(PlanMark.New, g.markOf("q:orders-dlq"))
        assertEquals(PlanMark.New, g.markOf("e:orders-dlx"))
    }

    @Test
    fun `the source is drawn though no step declares it, and a name the broker lacks is not in this plan`() {
        val plan = planOf(MigrationAction.DeclareExchange("orders-dlx", "fanout"), policy("orders-dlx", "^orders$", dlx("orders-dlx")))
        val unread = planPreview(plan, null)
        assertTrue("q:orders" in unread.graph.nodes.map { it.node.id }, "drawn")
        assertEquals(listOf(PlanLink("q:orders", "e:orders-dlx", deadLetter)), unread.links)
        val read = planPreview(plan, KnownTopology(queues = setOf("other")))
        assertEquals(PlanMark.NotInPlan, read.markOf("q:orders"))
        assertEquals(PlanMark.Existing, planPreview(plan, KnownTopology(queues = setOf("orders"))).markOf("q:orders"))
    }

    @Test
    fun `a source queue the plan declares is new, not existing`() {
        val plan = planOf(
            MigrationAction.DeclareQueue("orders", "quorum"), MigrationAction.DeclareExchange("orders-dlx", "fanout"),
            policy("orders-dlx", "^orders$", dlx("orders-dlx")),
        )
        assertEquals(PlanMark.New, planPreview(plan, KnownTopology.None).markOf("q:orders"))
    }

    @Test
    fun `a pattern for several names or no exact name gives no link`() {
        for (pattern in listOf("^(orders|jobs)$", "^orders", "orders", "^ord.*$", ".*", "^orders\$x")) {
            val g = planPreview(planOf(MigrationAction.DeclareExchange("x", "fanout"), policy("p", pattern, dlx("x"))), KnownTopology.None)
            assertEquals(emptyList(), g.links, "pattern <$pattern>")
            assertTrue("q:orders" !in g.graph.nodes.map { it.node.id }, "no node is drawn for <$pattern>")
        }
    }

    @Test
    fun `a policy without a dead-letter exchange, or with a non-text one, gives no link`() {
        assertEquals(emptyList(), planPreview(planOf(policy("p", "^orders$", "max-length" to ArgumentValue.Whole(5))), KnownTopology.None).links)
        assertEquals(emptyList(), planPreview(planOf(policy("p", "^orders$", "dead-letter-exchange" to ArgumentValue.Whole(5))), KnownTopology.None).links)
    }

    @Test
    fun `a pattern with an escaped special character names the queue`() {
        val g = planPreview(planOf(MigrationAction.DeclareExchange("x", "fanout"), policy("p", ExactPattern.of(listOf("orders.eu")), dlx("x"))), KnownTopology.None)
        assertEquals(listOf(PlanLink("q:orders.eu", "e:x", deadLetter)), g.links)
    }

    @Test
    fun `a queue with the x-dead-letter-exchange argument as text links to that exchange`() {
        val queue = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-dead-letter-exchange" to ArgumentValue.Text("legacy-dlx")))
        val g = planPreview(planOf(queue, MigrationAction.DeclareExchange("legacy-dlx", "fanout")), KnownTopology.None)
        assertEquals(listOf(PlanLink("q:orders", "e:legacy-dlx", deadLetter)), g.links)
    }

    @Test
    fun `an argument that is not text gives no link`() {
        val queue = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-dead-letter-exchange" to ArgumentValue.Whole(1)))
        assertEquals(emptyList(), planPreview(planOf(queue), KnownTopology.None).links)
    }

    @Test
    fun `a link never points at a node the graph does not draw`() {
        val plan = planOf(policy("p", "^orders$", dlx("nowhere")), MigrationAction.DeclareQueue("q2", arguments = mapOf("x-dead-letter-exchange" to ArgumentValue.Text("elsewhere"))))
        val g = planPreview(plan, KnownTopology.None)
        val ids = g.graph.nodes.map { it.node.id }.toSet()
        assertEquals(2, g.links.size)
        g.links.forEach { assertTrue(it.sourceId in ids && it.targetId in ids, "both ends of $it are drawn: $ids") }
    }

    @Test
    fun `two recipes give two links, the same pair twice gives one`() {
        val a = MigrationAction.DeclareExchange("a-dlx", "fanout")
        val b = MigrationAction.DeclareExchange("b-dlx", "fanout")
        val pa = policy("a-dlx", "^a$", dlx("a-dlx"))
        val g = planPreview(planOf(a, b, pa, policy("b-dlx", "^b$", dlx("b-dlx")), pa), KnownTopology.None)
        assertEquals(listOf(PlanLink("q:a", "e:a-dlx", deadLetter), PlanLink("q:b", "e:b-dlx", deadLetter)), g.links)
    }

    @Test
    fun `the response-stream links stay, and come with the dead-letter ones`() {
        val plan = planOf(
            MigrationAction.DeclareStream("t"), MigrationAction.DeclareStream("t-dln"),
            MigrationAction.DeclareExchange("x", "fanout"), policy("p", "^o$", dlx("x")),
        )
        assertEquals(
            setOf(PlanLink("q:t", "q:t-dln", PlanLinkKind.ResponseStream), PlanLink("q:o", "e:x", deadLetter)),
            planPreview(plan, KnownTopology.None).links.toSet(),
        )
    }

    @Test
    fun `the broker's own policies draw no link, only the plan's steps do`() {
        val known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", "p", ownDeadLetterExchange = "legacy")))
        assertEquals(emptyList(), planPreview(TopologyPlan(), known).links)
    }
}
