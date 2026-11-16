package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.MAX_GRAPH_NODES
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-04 step 4 (test developer): hostile and boundary inputs of `planPreview` that the step 2 tests did not name. */
class PlanPreviewExploratoryTest {
    private fun queue(name: String) = MigrationAction.DeclareQueue(name = name)
    private fun exchange(name: String) = MigrationAction.DeclareExchange(name = name, type = "direct")
    private fun bind(exchange: String, queue: String, key: String = "") = MigrationAction.DeclareBinding(exchange = exchange, queue = queue, routingKey = key)

    private val hostile = listOf(
        "<b>bold</b>&amp;<script>alert(1)</script>",
        "x".repeat(200),
        "‮evil‬-rtl שלום",
        "with space and \"quotes\" 'single' \\ back",
        "e:looks-like-an-id",
        "q:also",
    )

    @Test
    fun `hostile names are drawn verbatim, as new, and every accessible name keeps the name whole`() {
        for (name in hostile) {
            val g = planPreview(planOf(queue(name), exchange(name), bind(name, name, name)), KnownTopology.None)
            assertEquals(setOf("e:$name", "q:$name"), g.graph.nodes.map { it.node.id }.toSet(), "ids for <$name>")
            assertEquals(g.graph.nodes.map { it.node.id }.toSet(), g.newNodeIds)
            g.graph.nodes.forEach { n ->
                assertTrue(g.accessibleName(n.node).contains(name), "accessible name must carry <$name>: ${g.accessibleName(n.node)}")
                assertTrue(g.accessibleName(n.node).endsWith(", new"))
            }
        }
    }

    @Test
    fun `an exchange and a queue with the same name are two nodes`() {
        val g = planPreview(planOf(queue("same"), exchange("same")), KnownTopology.None)
        assertEquals(setOf("e:same", "q:same"), g.graph.nodes.map { it.node.id }.toSet())
    }

    @Test
    fun `the broker size does not count, only what the plan touches is drawn`() {
        val known = KnownTopology(
            exchanges = (1..300).associate { "ex$it" to "direct" },
            queues = (1..500).map { "bq$it" }.toSet(),
            streams = (1..200).map { "bs$it" }.toSet(),
        )
        val g = planPreview(planOf(queue("mine"), bind("ex1", "bq1", "k")), known)
        assertFalse(g.graph.isTooLarge)
        assertEquals(setOf("q:mine", "e:ex1", "q:bq1"), g.graph.nodes.map { it.node.id }.toSet())
        assertEquals(setOf("q:mine"), g.newNodeIds)
        assertNull(g.notice)
    }

    @Test
    fun `60 declared queues plus one binding end that is already counted stay at the cap, 61 is too large`() {
        val sixty = (1..MAX_GRAPH_NODES - 1).map { queue("q$it") } + exchange("ex")
        assertFalse(planPreview(planOf(*sixty.toTypedArray()), KnownTopology.None).graph.isTooLarge)
        val sixtyOne = sixty + queue("one-more")
        assertTrue(planPreview(planOf(*sixtyOne.toTypedArray()), KnownTopology.None).graph.isTooLarge)
    }

    @Test
    fun `bindings to the same pair twice draw two edges and both are new`() {
        val g = planPreview(planOf(queue("a"), exchange("x"), bind("x", "a", "k1"), bind("x", "a", "k2")), KnownTopology.None)
        assertEquals(setOf("edge-0", "edge-1"), g.newEdgeIds)
        assertEquals(2, g.graph.edges.size)
    }
}
