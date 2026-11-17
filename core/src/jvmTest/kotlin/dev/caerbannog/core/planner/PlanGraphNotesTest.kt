package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07b step 2: the note of the review graph, "the owner's own service sits here" (decisions 4 and 6). API fixed by these tests (beyond the brief):
 *  - `planGraphNotes(plan: TopologyPlan): List<PlanGraphNote>` (one argument: the broker plays no part, a pair is recognised in the plan only);
 *  - `PlannerTexts.SERVICE_NOT_PLANNED`; the note is `PlanGraphNote("q:<request>", "e:amq.topic", SERVICE_NOT_PLANNED)`, one per request stream, in
 *    the order of `PlanPairs.requestsAnsweredOverMqtt`; `planPreview(...).notes` is that list and nothing else changes in the graph.
 */
class PlanGraphNotesTest {
    private val sentence = "Your own service turns a request into an MQTT message; the planner does not plan that."
    private fun stream(name: String) = MigrationAction.DeclareStream(name = name)
    private fun route(to: String, key: String = "devices.*.answer") = MigrationAction.DeclareBinding("amq.topic", to, key)
    private fun note(request: String) = PlanGraphNote("q:$request", "e:amq.topic", sentence)

    @Test
    fun `the sentence is the fixed text and says nothing about correlation ids`() {
        assertEquals(sentence, PlannerTexts.SERVICE_NOT_PLANNED)
        assertTrue("correlation" !in PlannerTexts.SERVICE_NOT_PLANNED.lowercase(), "the pair says nothing about correlation (owner decision O16)")
    }

    @Test
    fun `a pair with an answers route has one note between the request stream and amq topic`() {
        val plan = planOf(stream("device-commands"), stream("device-commands-dln"), route("device-commands-dln"))
        assertEquals(listOf(note("device-commands")), planGraphNotes(plan))
        assertEquals(listOf(note("device-commands")), planPreview(plan, KnownTopology.None).notes)
    }

    @Test
    fun `no route, no note`() {
        assertEquals(emptyList(), planGraphNotes(planOf(stream("a"), stream("a-dln"))))
        assertEquals(emptyList(), planGraphNotes(TopologyPlan()))
        assertEquals(emptyList(), planPreview(planOf(stream("a"), stream("a-dln")), KnownTopology.None).notes)
    }

    @Test
    fun `a route into the request stream or into a stream that is not a response stream gives no note`() {
        assertEquals(emptyList(), planGraphNotes(planOf(stream("a"), stream("a-dln"), route("a", "commands.#"))))
        assertEquals(emptyList(), planGraphNotes(planOf(stream("telemetry"), route("telemetry", "sensors.#"))))
        assertEquals(emptyList(), planGraphNotes(planOf(stream("a"), stream("a-replies"), route("a-replies"))))
    }

    @Test
    fun `two routes into the same response stream give one note`() {
        val plan = planOf(stream("a"), stream("a-dln"), route("a-dln"), route("a-dln", "x.#"))
        assertEquals(listOf(note("a")), planGraphNotes(plan))
    }

    @Test
    fun `two pairs with routes give two notes in plan order`() {
        val plan = planOf(stream("a"), stream("a-dln"), stream("b"), stream("b-dln"), route("a-dln"), route("b-dln", "x.*.y"))
        assertEquals(listOf(note("a"), note("b")), planGraphNotes(plan))
    }

    @Test
    fun `a pair typed by hand gets the note, as does a pair of a reopened file`() {
        // hand-typed: two single stream steps and a plain binding; reopened: the same actions come back from a file (A10, A11)
        val actions = listOf(stream("orders-dln"), stream("orders"), route("orders-dln"))
        assertEquals(listOf(note("orders")), planGraphNotes(planOf(*actions.toTypedArray())))
    }

    @Test
    fun `the note follows the steps, so removing the route or the pair removes it`() {
        val plan = planOf(stream("a"), stream("a-dln"), route("a-dln"))
        val withoutRoute = plan.copy(steps = plan.steps.filter { it.action !is MigrationAction.DeclareBinding })
        val withoutRequest = plan.copy(steps = plan.steps.filter { (it.action as? MigrationAction.DeclareStream)?.name != "a" })
        assertEquals(emptyList(), planGraphNotes(withoutRoute))
        assertEquals(emptyList(), planGraphNotes(withoutRequest))
    }

    @Test
    fun `a pair on the broker is not a pair of the plan`() {
        val known = KnownTopology(streams = setOf("a", "a-dln"))
        assertEquals(emptyList(), planPreview(planOf(route("a-dln")), known).notes)
    }

    @Test
    fun `a note changes no node, no edge and no link of the graph`() {
        val plan = planOf(stream("a"), stream("a-dln"), route("a-dln"))
        val withNote = planPreview(plan, KnownTopology.None)
        val without = planPreview(planOf(stream("a"), stream("a-dln"), route("a-dln", "x.y")), KnownTopology.None)
        assertEquals(1, withNote.graph.edges.size, "only the route's edge")
        assertEquals(setOf("e:amq.topic", "q:a", "q:a-dln"), withNote.graph.nodes.map { it.node.id }.toSet())
        assertEquals(listOf(PlanLink("q:a", "q:a-dln", PlanLinkKind.ResponseStream)), withNote.links)
        assertEquals(withNote.graph.edges.size, without.graph.edges.size)
    }
}
