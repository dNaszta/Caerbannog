package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.planner.PlanMark
import dev.caerbannog.core.planner.ReviewView
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-04 steps for the `@m9-04` scenarios of `19_topology_planner.feature`: the review graph on the REAL planner screen (PlannerScreens, Compose UI
 * test API) next to the state `core` derives (`PlannerState.graph`, `planPreview`). A mark is checked twice, in the state and on the screen (the
 * accessible name and the "new" pill), so the screen cannot draw something the state does not say. The broker is the fake reader of [PlannerDriver]
 * (names only; it records what it was asked).
 */
class PlannerGraphSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    private fun nodeId(kind: String, name: String) = if (kind == "exchange") "e:$name" else "q:$name"

    private fun assertMark(kind: String, name: String, mark: PlanMark, words: String) {
        val id = nodeId(kind, name)
        val graph = d.state.graph
        assertTrue(graph.graph.nodes.any { it.node.id == id }, "$id is not in the graph: ${graph.graph.nodes.map { it.node.id }}")
        assertEquals(mark, graph.markOf(id), "state: mark of $id")
        assertEquals("$kind $name, $words", screen.description("planGraphNode:$id"), "screen: what $id announces")
        assertEquals(mark == PlanMark.New, screen.isDrawn("planGraphNew:$id"), "screen: the \"new\" pill of $id")
    }

    // ---- the broker and the plan ----

    @Given("the broker has the direct exchange {string} bound to the quorum queue {string} with routing key {string}")
    fun brokerBinding(exchange: String, queue: String, @Suppress("UNUSED_PARAMETER") key: String) {
        // The planner reads names, never bindings (spec "Which existing items are drawn"): the binding is part of the broker's state and must not be drawn.
        d.known = d.known.copy(exchanges = d.known.exchanges + (exchange to "direct"), queues = d.known.queues + queue)
    }

    @Given("the broker cannot be read")
    fun brokerUnreadable() { d.brokerReadable = false; d.restart() }

    @Given("a plan that declares {int} quorum queues")
    fun manyQueues(count: Int) {
        d.newPlan()
        (1..count).forEach { d.addWithWizard(d.queueForm("queue-$it", "quorum")) }
        assertEquals(count, d.plan.steps.size)
    }

    @Given("a plan that binds {string} to the new queue {string}")
    fun bindsToNewQueue(exchange: String, queue: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(queue, "quorum"))
        d.addWithWizard(d.bindingForm(exchange, queue, "order.created"))
    }

    @When("I remove the binding step")
    fun removeBinding() {
        val step = d.plan.steps.lastOrNull { it.action is MigrationAction.DeclareBinding } ?: error("no binding step in ${d.titles()}")
        d.vm.removeStep(step.id)
    }

    // ---- looking ----

    @When("I look at the review graph")
    fun lookAtGraph() {
        assertTrue(screen.isDrawn("planner:reviewCard"), "the review card is not drawn")
        assertEquals(ReviewView.Graph, d.state.graphView, "the graph is the first view")
        assertTrue(screen.isDrawn("planner:reviewGraph") || screen.isDrawn("planner:graphNotice"), "neither the graph nor a notice is drawn")
    }

    @When("I look at the review card")
    fun lookAtCard() { assertTrue(screen.isDrawn("planner:reviewCard"), "the review card is not drawn") }

    @Then("the graph is shown")
    fun graphShown() {
        assertTrue(screen.isDrawn("planner:reviewGraph"), "the graph is not drawn")
        assertTrue(!screen.isDrawn("planner:jsonView"), "the JSON text is drawn beside the graph")
    }

    @When("I switch the review to JSON")
    fun switchToJson() { screen.click("planner:reviewViewSwitch:Json") }

    @Then("the migration text with the version {string} is shown and can be copied")
    fun jsonShown(version: String) {
        val text = screen.textOf("planner:jsonView").orEmpty()
        assertTrue(text.contains("\"version\": \"$version\""), "the JSON view shows: $text")
        assertIs<MigrationValidationResult.Valid>(MigrationParser.parse(d.state.migrationText))
        assertTrue(screen.isEnabled("planner:jsonCopyBtn"), "Copy is not offered")
    }

    // ---- what the graph shows ----

    @Then("it shows the exchange {string} as already on the broker")
    fun exchangeExisting(name: String) = assertMark("exchange", name, PlanMark.Existing, "already on the broker")

    @Then("it shows the queue {string} as already on the broker")
    fun queueExisting(name: String) = assertMark("queue", name, PlanMark.Existing, "already on the broker")

    @Then("it shows the queue {string} marked {string}")
    fun queueMarked(name: String, mark: String) {
        assertEquals("new", mark)
        assertMark("queue", name, PlanMark.New, "new")
    }

    @Then("the queue {string} is marked {string}")
    fun queueIsMarked(name: String, mark: String) = queueMarked(name, mark)

    @Then("the exchange {string} is drawn as {string}")
    fun exchangeDrawnAs(name: String, mark: String) {
        assertEquals("not in this plan", mark)
        assertMark("exchange", name, PlanMark.NotInPlan, "not in this plan")
    }

    @Then("it shows an edge from {string} to {string} labelled {string} marked {string}")
    fun edgeShown(from: String, to: String, label: String, mark: String) {
        assertEquals("new", mark)
        val edge = d.state.graph.graph.edges.map { it.edge }.firstOrNull { it.sourceId == "e:$from" && it.targetId == "q:$to" && it.routingKey == label }
            ?: error("no edge $from -> $to labelled $label in ${d.state.graph.graph.edges.map { it.edge }}")
        assertTrue(edge.id in d.state.graph.newEdgeIds, "the edge ${edge.id} is not marked new in the state")
        assertTrue(screen.isDrawn("edgePill:$label"), "the label $label is not drawn")
        assertTrue(screen.isDrawn("planGraphNew:${edge.id}"), "the edge ${edge.id} is not marked new on the screen")
    }

    @Then("it does not show the queue {string}")
    fun notShown(name: String) {
        assertTrue(d.state.graph.graph.nodes.none { it.node.id == "q:$name" }, "the state draws q:$name")
        assertTrue(!screen.isDrawn("planGraphNode:q:$name"), "the screen draws the queue $name")
    }

    @Then("the review graph shows the queue and the exchange without an edge")
    fun withoutEdge() {
        assertEquals(setOf("e:events", "q:orders"), d.state.graph.graph.nodes.map { it.node.id }.toSet())
        assertEquals(emptyList(), d.state.graph.graph.edges, "the state still has an edge")
        assertEquals(setOf("planGraphNode:e:events", "planGraphNode:q:orders"), screen.tagsWithPrefix("planGraphNode:").toSet())
        assertEquals(emptyList(), screen.tagsWithPrefix("edgePill:"), "the screen still draws an edge label")
        assertEquals(emptyList(), screen.tagsWithPrefix("planGraphNew:").filter { it.startsWith("planGraphNew:edge") })
    }

    @Then("no control on the graph adds, changes, moves or removes a step")
    fun readOnly() {
        assertEquals(emptyList(), screen.actionsUnder("planner:reviewGraph"), "the graph offers an action")
        val plan = d.plan
        val drafts = d.store.savedTexts.size
        (screen.tagsWithPrefix("planGraphNode:") + screen.tagsWithPrefix("edgePill:")).forEach { screen.click(it) }
        assertEquals(plan, d.plan, "a click on the graph changed the plan")
        assertEquals(drafts, d.store.savedTexts.size, "a click on the graph wrote the draft")
        assertEquals(emptyList(), d.executor.executedActions, "a click on the graph reached the broker")
    }

    // ---- notices ----

    @Then("it says {string}")
    fun says(title: String) {
        // M9-05: after a check, "it says" is a sentence of the check result (the step that needs something that is missing), not the graph's notice.
        if (CheckWorld.result != null) return CheckWorld.saysSentence(title)
        // M9-10: the sentence of the MQTT route form (the form is on show, not the graph).
        if (title == dev.caerbannog.core.planner.PlannerTexts.MQTT_EXCHANGE_ASSUMPTION) return PlannerMqttSteps.assumptionSaid(title)
        val text = screen.textOf("planner:graphNotice").orEmpty()
        assertTrue(text.contains(title), "the notice says: $text")
    }

    @Then("it says {string} and that the step list is the review")
    fun saysTooLarge(title: String) {
        val text = screen.textOf("planner:graphNotice").orEmpty()
        assertTrue(text.contains(title) && text.contains("The step list is the review."), "the notice says: $text")
        assertEquals(emptyList(), screen.tagsWithPrefix("planGraphNode:"), "nodes are drawn beside the too large notice")
    }

    @Then("it says {string} and that the broker could not be read")
    fun saysNotRead(title: String) {
        val text = screen.textOf("planner:graphNotice").orEmpty()
        assertTrue(text.contains(title) && text.contains("The broker could not be read"), "the notice says: $text")
    }

    @Then("the step list shows all {int} steps")
    fun allSteps(count: Int) {
        assertEquals(count, d.plan.steps.size)
        assertTrue(screen.isDrawn("planner:stepList") && screen.isDrawn("planStep:0"), "the step list is not drawn")
    }

    // ---- the keyboard ----

    private var announced: List<String> = emptyList()

    @When("I move through the review graph with the keyboard")
    fun keyboard() { announced = screen.focusInTurn("planGraphNode:") }

    @Then("the node {string} is announced as {string}")
    fun announcedAs(name: String, words: String) {
        assertTrue(announced.isNotEmpty(), "no node of the graph can be reached by the keyboard")
        assertTrue(words in announced, "announced: $announced")
        assertTrue(name in words)
    }
}
