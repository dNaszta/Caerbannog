package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.topology.BindingDestinationType
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9-10 steps for the `@m9-10` scenarios of `21_topology_planner_recipes.feature`: the MQTT route step. The view model is the real one over fakes (the
 * shared [PlannerDriver]); the form is typed into on the REAL planner screen ([PlannerScreens]) and every claim about words is read from the screen too:
 * the error under the filter, the key line, the matching topic, the assumption sentence, the step rows, the labelled edge of the review graph.
 * No broker: nothing here can send a request.
 *
 * Stand-ins until M9-12: the three Givens "a pending migration ..." put the file's actions in the draft file (`PendingStandIn`, defined in
 * PlannerStreamSteps) and the existing "I choose "Edit steps" for ..." restarts the planner on it. M9-12 must replace those, not add a second step.
 */
class PlannerMqttSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    private fun kind(word: String) = if (word == "stream") MqttDestinationKind.Stream else MqttDestinationKind.Queue

    // ---- a plan ----

    @Given("a plan that declares the stream {string}")
    fun planWithStream(name: String) { d.newPlan(); d.addWithWizard(d.streamForm(name)); assertEquals(listOf("Declare stream $name"), d.titles()) }

    @Given("a plan that declares the quorum queue {string} and the stream {string}")
    fun planWithQueueAndStream(queue: String, stream: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(queue, "quorum"))
        d.addWithWizard(d.streamForm(stream))
        assertEquals(2, d.plan.steps.size)
    }

    // ---- adding a route ----

    @When("I add an MQTT route from {string} to the {word} {string} with the wizard")
    fun addRoute(filter: String, word: String, destination: String) {
        assertTrue(word == "stream" || word == "queue", "the destination is a stream or a queue, not \"$word\"")
        d.addWithWizard(d.mqttRouteForm(filter, destination, kind(word)))
    }

    @Then("the step is the action {string} from {string} to {string} with routing key {string}")
    fun stepIsAction(name: String, exchange: String, destination: String, key: String) {
        val action = assertIs<MigrationAction.DeclareBinding>(d.plan.steps.last().action)
        assertEquals(name, action.actionName)
        assertEquals(exchange, action.exchange)
        assertEquals(destination, action.queue)
        assertEquals(key, action.routingKey)
        assertEquals(BindingDestinationType.QUEUE, action.destinationType, "a stream is a queue for a binding")
        assertEquals(emptyMap(), action.arguments)
    }

    // ---- the form, on the real screen ----

    @When("I enter the topic filter {string} in the MQTT route form")
    fun enterFilter(filter: String) {
        // The destination has its own field: the scenarios are about the filter, so a destination is there and is not the problem.
        d.fill(d.mqttRouteForm("", "telemetry", MqttDestinationKind.Stream))
        if (filter.isNotEmpty()) screen.replaceText("planForm:filter", filter)
        assertEquals(filter, (d.form as? MqttRouteForm)?.filter, "the form in the state after typing")
    }

    @Then("the form says it is saved as a binding on amq.topic with the key {string}")
    fun formSaves(key: String) {
        assertEquals(emptyList(), d.formProblems(), "the form has a problem")
        assertEquals(PlannerTexts.mqttRouteSaves(key), screen.textOf("planForm:mqttKey"), "the key line on the screen")
        assertEquals(key, (d.form as MqttRouteForm).bindingKey(), "the key in the state")
    }

    @Then("it gives {string} as a topic that matches")
    fun givesTopic(topic: String) {
        assertEquals(topic, (d.form as MqttRouteForm).matchingTopic(), "the topic in the state")
        assertTrue(screen.textOf("planForm:mqttTopic").orEmpty().contains(topic), "the topic line says: ${screen.textOf("planForm:mqttTopic")}")
    }

    /** Called from `PlannerGraphSteps.says` for the assumption sentence (the one "it says" step of the glue). */
    companion object {
        fun filterErrorOnScreen(message: String) {
            val world = PlannerWorld.driver
            if (world.form !is MqttRouteForm) return
            assertEquals(message, PlannerScreens.forViewModel(world.vm).textOf("planForm:error:filter"), "the error under the filter field")
        }

        fun assumptionSaid(text: String) {
            val world = PlannerWorld.driver
            assertTrue(world.form is MqttRouteForm, "the open form is ${world.form}, not the MQTT route form")
            assertEquals(text, PlannerScreens.forViewModel(world.vm).textOf("planForm:mqttAssumption"), "the assumption sentence on the screen")
        }
    }

    // ---- a refusal is also on the screen ----


    // ---- a pending file (stand-ins until M9-12) ----

    @Given("a pending migration {string} that declares the stream {string} and binds {string} to it with routing key {string}")
    fun pendingStreamAndRoute(version: String, stream: String, exchange: String, key: String) = PendingStandIn.put(
        d, version, listOf(MigrationAction.DeclareStream(stream, vhost = d.vhost), MigrationAction.DeclareBinding(exchange, stream, key, vhost = d.vhost)),
    )

    @Given("a pending migration {string} that binds {string} to {string} with routing key {string}")
    fun pendingRoute(version: String, exchange: String, destination: String, key: String) =
        PendingStandIn.put(d, version, listOf(MigrationAction.DeclareBinding(exchange, destination, key, vhost = d.vhost)))

    private val bindingTail = Regex("""^to the (exchange|queue) "([^"]*)" with routing key "([^"]*)"(?: and the argument "([^"]*)")?$""")
    private var original: MigrationAction?
        get() = PendingStandIn.binding
        set(value) { PendingStandIn.binding = value }

    @Given("^a pending migration \"([^\"]*)\" with a binding from \"([^\"]*)\" (to the .*)$")
    fun pendingBinding(version: String, exchange: String, tail: String) {
        val m = bindingTail.matchEntire(tail) ?: error("cannot read the binding \"$tail\"")
        val (kindWord, destination, key, argument) = m.destructured
        val action = MigrationAction.DeclareBinding(
            exchange = exchange, queue = destination, routingKey = key, vhost = d.vhost,
            destinationType = if (kindWord == "exchange") BindingDestinationType.EXCHANGE else BindingDestinationType.QUEUE,
            arguments = if (argument.isEmpty()) emptyMap() else mapOf(argument to ArgumentValue.Text("note")),
        )
        original = action
        PendingStandIn.put(d, version, listOf(action))
    }

    // ---- what the planner shows ----

    @Then("the planner lists {string} and {string}")
    fun plannerListsTwo(first: String, second: String) {
        assertEquals(listOf(first, second), d.titles(), "the planner's steps")
        assertShownOnScreen(first, 0)
        assertShownOnScreen(second, 1)
    }

    @Then("the planner lists {string}")
    fun plannerListsOne(title: String) {
        // Means "among the steps" (M9-07b: a pair and its route are three steps). For the M9-10 scenarios it is the same assertion as before, because their plan has one step.
        val row = d.titles().indexOf(title)
        assertTrue(row >= 0, "the planner's steps are ${d.titles()}, expected \"$title\" among them")
        assertShownOnScreen(title, row)
    }

    private fun assertShownOnScreen(title: String, row: Int) =
        assertTrue(screen.textOf("planStep:$row").orEmpty().contains(title), "row $row says: ${screen.textOf("planStep:$row")}, expected \"$title\"")

    @Then("the step is shown as a binding, not as an MQTT route")
    fun shownAsBinding() {
        val step = d.plan.steps.single()
        assertEquals(original, step.action, "the step is the binding of the file")
        assertEquals(step.action.description, d.state.stepTitle(step.id), "the words of the list")
        assertFalse(d.state.stepTitle(step.id).startsWith("MQTT route"), "worded as a route: ${d.state.stepTitle(step.id)}")
        assertFalse(screen.textOf("planStep:0").orEmpty().contains("MQTT route"), "the row says: ${screen.textOf("planStep:0")}")
        // The form that edits it is the binding form, which keeps arguments' steps read-only (no form at all) and the others as typed.
        if (step.isEditable) assertIs<BindingForm>(dev.caerbannog.core.planner.StepForms.formOf(step.action))
    }

    @Then("saving writes the binding back unchanged")
    fun savingWritesUnchanged() {
        val expected = assertNotNull(original, "no binding was put by a Given")
        // M9-11: a binding that holds arguments goes through its form first (Edit, change nothing, Add to plan), so "unchanged" is a claim about the form too.
        if (expected is MigrationAction.DeclareBinding && expected.arguments.isNotEmpty() && dev.caerbannog.core.planner.StepForms.formOf(expected) is BindingForm) {
            d.editStep(1) { it }
        }
        d.chooseMigrationsFolder("/work/migrations", "0004")
        d.vm.saveAsMigration()
        val version = d.state.saved?.version ?: error("the plan was not saved; error: ${d.state.errorMessage}")
        val file = d.migrationFolder.files.getValue(version)
        assertEquals(listOf<MigrationAction>(expected), file.up, "the saved file's actions")
        assertTrue(MigrationParser.parse(file.rawJson) is dev.caerbannog.core.migrations.MigrationValidationResult.Valid, "the saved file is a valid migration")
    }

    // ---- the review graph ----

    @Then("the review graph shows an edge from {string} to {string} labelled {string} and {string}")
    fun graphEdge(source: String, target: String, label: String, tag: String) {
        assertEquals(PlannerTexts.MQTT_ROUTE_LABEL, tag, "the second label of the scenario is the constant of the planner")
        val edge = d.state.graph.graph.edges.map { it.edge }.single { it.sourceId == "e:$source" && it.targetId == "q:$target" }
        assertEquals(label, d.state.graph.edgeLabels[edge.id], "state: the label of ${edge.id}")
        assertTrue(edge.id in d.state.graph.mqttRouteEdgeIds, "state: ${edge.id} is not marked as a route")
        assertEquals(label, screen.textOf("planGraphEdgeLabel:${edge.id}"), "screen: the edge's label")
        assertEquals(tag, screen.textOf("planGraphEdgeTag:${edge.id}"), "screen: the edge's words")
    }
}
