package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.DeadLetterForm
import dev.caerbannog.core.planner.DeadLetterStrategy
import dev.caerbannog.core.planner.DeadLetterTarget
import dev.caerbannog.core.planner.FormContext
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PatternMatchResult
import dev.caerbannog.core.planner.PatternMatches
import dev.caerbannog.core.planner.PlanLink
import dev.caerbannog.core.planner.PlanLinkKind
import dev.caerbannog.core.planner.PlanMark
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.SuggestionSource
import dev.caerbannog.core.planner.WizardState
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.locks.LockSupport

/** What the `@m9-09` scenarios remember between steps: the summary page the wizard showed before "Add to plan" closed it. */
internal object DeadLetterWorld {
    var active = false
    var summaryTitle: String? = null
    var summaryNote: String? = null

    fun reset() { active = true; summaryTitle = null; summaryNote = null }
}

/**
 * M9-09 steps for the `@m9-09` scenarios of `21_topology_planner_recipes.feature`: the dead-lettering recipe. The view model and the form are the real ones over the
 * fakes of [PlannerDriver]; the broker the scenario describes is `KnownTopology` values (names, types, policy names, the queue's own dead-letter exchange). The policy
 * warning runs the REAL `ManagementBrokerPolicyReader` over the honest `FakeManagementBroker` of `CheckWorld` (GET only, as `PlannerPolicySteps` does), so the note
 * arrives from the view model's own read job: the step waits for it on a bounded condition, never a fixed sleep. What the screen draws is pinned by
 * `DeadLetterFormUiTest`; the review graph label is also read on the real screen here. The dashed line itself is a drawing detail no semantics tree carries: it is
 * looked at in step 4 (UI checks), not asserted.
 */
class PlannerDeadLetterSteps {
    private val d get() = PlannerWorld.driver

    @Before(value = "@m9-09", order = 20000)
    fun setUp() {
        DeadLetterWorld.reset()
        PolicyWorld.reset()
    }

    /** The flag routes two shared sentences ("the summary says", the classic queue) to this class; it must not outlive the scenario. */
    @After(value = "@m9-09")
    fun tearDown() { DeadLetterWorld.active = false }

    // ---- helpers ----

    private val tickLabel = "Publishers are refused when the queue is full"

    private fun strategy(label: String) = when (label) {
        "At most once" -> DeadLetterStrategy.AtMostOnce
        "At least once" -> DeadLetterStrategy.AtLeastOnce
        else -> error("no strategy \"$label\"")
    }

    private fun form(): DeadLetterForm = d.form as? DeadLetterForm ?: error("the open form is ${d.form}, not the dead-lettering form")

    /** The context the wizard judges the form in: this plan and what the planner read from the broker (nothing without a connection). */
    private fun ctx() = FormContext(d.plan, d.state.namingStyle, d.state.known ?: KnownTopology.None, d.state.connectionName)

    private fun details() = d.state.wizard as? WizardState.Details ?: error("the wizard is not on a form: ${d.state.wizard}")

    private fun add(form: DeadLetterForm) {
        DeadLetterWorld.summaryTitle = null
        DeadLetterWorld.summaryNote = null
        d.fill(form)
        assertTrue(d.nextEnabled(), "Next is disabled for $form; problems=${d.formProblems()}")
        d.next()
        readSummary()
        d.addToPlan()
    }

    private fun readSummary() {
        DeadLetterWorld.summaryTitle = d.summaryTitle()
        DeadLetterWorld.summaryNote = (d.state.wizard as? WizardState.Summary)?.note
    }

    private fun policy(): MigrationAction.DeclarePolicy = d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclarePolicy>().single()

    // ---- the broker the scenario describes ----

    @Given("the broker has the quorum queue {string} with the policy {string} applied")
    fun brokerQueueWithPolicy(queue: String, policy: String) {
        PolicyWorld.activate(d)
        CheckWorld.seedQueue(queue)
        // lower than the recipe's priority 0, so the recipe's policy would take the place of this one (the sentence of the scenario)
        CheckWorld.broker.addPolicy("/", policy, "^$queue", mapOf("max-length" to JsonPrimitive(5)), priority = -1)
        d.known = KnownTopology(queues = setOf(queue), queueDetails = mapOf(queue to KnownQueue("quorum", policy)))
    }

    @Given("the broker has the quorum queue {string} declared with the argument {string} {string}")
    fun brokerQueueWithArgument(queue: String, key: String, value: String) {
        assertEquals("x-dead-letter-exchange", key, "the glue knows the one argument the form reads")
        CheckWorld.seedQueue(queue)
        d.known = d.known.copy(
            queues = d.known.queues + queue,
            queueDetails = d.known.queueDetails + (queue to KnownQueue("quorum", null, ownDeadLetterExchange = value)),
        )
    }

    // ---- adding ----

    @When("I add dead-lettering for the queue {string} with the wizard")
    fun addFor(queue: String) = add(DeadLetterForm(queue))

    @When("I add dead-lettering for the queue {string} with the wizard and choose a stream as the target")
    fun addForStream(queue: String) = add(DeadLetterForm(queue, target = DeadLetterTarget.Stream))

    @Given("a plan with dead-lettering for the queue {string}")
    fun planWith(queue: String) {
        d.newPlan()
        add(DeadLetterForm(queue))
        assertEquals(4, d.plan.steps.size, "the recipe did not add its steps: ${d.titles()}")
    }

    // ---- the form ----

    @When("I open the dead-lettering form")
    fun openForm() = d.fill(DeadLetterForm(""))

    @Then("the queues offered are {string} from this plan and {string} from the broker")
    fun queuesOffered(fromPlan: String, fromBroker: String) {
        assertEquals(
            listOf(fromPlan to SuggestionSource.PLAN, fromBroker to SuggestionSource.BROKER),
            ctx().queueSuggestions().map { it.name to it.source },
            "queues offered, this plan first, no stream",
        )
    }

    @When("I choose the queue {string} in the dead-lettering form")
    fun chooseQueue(queue: String) = d.fill(DeadLetterForm(queue))

    @When("I choose the queue {string} in the dead-lettering form without a connection")
    fun chooseQueueUnconnected(queue: String) {
        d.connected = false
        d.restart()
        d.fill(DeadLetterForm(queue))
    }

    @When("I choose {string} in the dead-lettering form for {string}")
    fun chooseStrategy(label: String, queue: String) {
        val chosen = DeadLetterForm(queue)
        d.fill(chosen)
        d.vm.updateForm(chosen.withStrategy(strategy(label)))
    }

    /** Dispatched from the one `I tick {string}` step of the stream glue. */
    fun tick(label: String) {
        assertEquals(tickLabel, label)
        d.vm.updateForm(form().copy(publishersRefusedAccepted = true))
    }

    @When("I tick it and finish the wizard")
    fun tickAndFinish() {
        tick(tickLabel)
        assertTrue(d.nextEnabled(), "Next is still disabled after the tick: ${d.formProblems()}")
        d.next(); readSummary(); d.addToPlan()
    }

    @When("I choose {string} and finish the wizard")
    fun chooseAndFinish(label: String) {
        d.vm.updateForm(form().withStrategy(strategy(label)))
        assertTrue(d.nextEnabled(), "Next is disabled: ${d.formProblems()}")
        d.next(); readSummary(); d.addToPlan()
    }

    @Then("{string} is disabled until I tick {string}")
    fun disabledUntilTick(button: String, tick: String) {
        assertEquals("Next", button)
        assertEquals(tickLabel, tick)
        assertFalse(d.nextEnabled(), "Next must be disabled until the tick")
        val problems = d.formProblems().map { it.field to it.message }
        assertEquals(listOf("refusedTick" to PlannerTexts.DEAD_LETTER_TICK_REQUIRED), problems, "the one reason Next is disabled")
    }

    /** Dispatched from the one `{string} is not offered` step of the test message glue. */
    fun atLeastOnceNotOffered() {
        assertFalse(form().offersAtLeastOnce(ctx()), "At least once is offered for ${form().queue}")
    }

    @Then("the dead-letter exchange is suggested as {string} and the dead-letter queue as {string}")
    fun suggested(exchange: String, queue: String) {
        assertEquals(exchange, form().suggestedExchangeName(ctx()), "suggested exchange")
        assertEquals(queue, form().suggestedTargetName(ctx()), "suggested dead-letter queue")
    }

    /** `the form says "<a note of the dead-lettering form>"`: a hint of the pure form or a note the view model read; never a problem. Waits (bounded) for the read. */
    fun saidByForm(message: String) {
        val deadline = System.nanoTime() + 20_000_000_000L
        fun said(): List<String> = details().hints.map { it.message } + details().formNotes
        while (message !in said() && System.nanoTime() < deadline) LockSupport.parkNanos(5_000_000)
        // a field problem is also "said" by the form (the stream source, the empty queue); a note is never a problem
        if (message in details().problems.map { it.message }) return
        assertTrue(message in said(), "the form says ${said()}, expected \"$message\"")
    }

    // ---- the plan the wizard built ----

    @Then("the plan holds no step that declares {string}")
    fun holdsNoStepFor(name: String) {
        val declared = d.plan.steps.map { it.action }.mapNotNull {
            when (it) {
                is MigrationAction.DeclareQueue -> it.name
                is MigrationAction.DeclareStream -> it.name
                is MigrationAction.DeclareExchange -> it.name
                else -> null
            }
        }
        assertFalse(name in declared, "a step declares $name: ${d.titles()}")
    }

    fun summarySays(sentence: String) {
        val title = DeadLetterWorld.summaryTitle
        val note = DeadLetterWorld.summaryNote.orEmpty()
        assertTrue(sentence == title || sentence in note, "the summary said title <$title> and note <$note>, expected \"$sentence\"")
    }

    @Then("the policy applies to quorum queues and holds {string} {string}")
    fun policyQuorumHolds(key: String, value: String) {
        assertEquals("quorum_queues", policy().applyTo)
        assertEquals(ArgumentValue.Text(value), policy().definition[key])
    }

    @Then("the policy pattern is {string}")
    fun patternIs(pattern: String) = assertEquals(pattern, policy().pattern)

    @Then("the pattern matches {string} and does not match {string}")
    fun patternMatches(name: String, other: String) {
        val matched = (PatternMatches.of(policy().pattern, listOf(name, other)) as PatternMatchResult.Matches).names
        assertEquals(listOf(name), matched, "names matched by ${policy().pattern} among [$name, $other]")
    }

    @Then("the four dead-lettering steps come after {string}")
    fun comeAfter(title: String) {
        val titles = d.titles()
        val at = titles.indexOf(title)
        assertTrue(at >= 0, "no step \"$title\" in $titles")
        assertEquals(
            listOf("Declare fanout exchange orders-dlx", "Declare quorum queue orders-dlq", "Bind orders-dlx to orders-dlq", "Declare policy orders-dlx"),
            titles.drop(at + 1), "the steps after \"$title\"",
        )
        assertEquals(at + 5, titles.size, "nothing else after, nothing reordered: $titles")
    }

    @Then("the policy holds {string} {string} and {string} {string}")
    fun policyHoldsTwo(k1: String, v1: String, k2: String, v2: String) {
        val definition = policy().definition
        assertEquals(ArgumentValue.Text(v1), definition[k1], "definition: $definition")
        assertEquals(ArgumentValue.Text(v2), definition[k2], "definition: $definition")
        assertTrue("dead-letter-exchange" in definition, "the exchange stays: $definition")
    }

    @Then("the policy holds {string} and no {string} and no {string}")
    fun policyHoldsOnly(key: String, absent1: String, absent2: String) {
        val definition = policy().definition
        assertTrue(key in definition, "definition: $definition")
        assertFalse(absent1 in definition || absent2 in definition, "definition: $definition")
        assertEquals(setOf(key), definition.keys, "nothing else in the definition")
    }

    @Then("the other three steps stay")
    fun otherThreeStay() {
        assertEquals(
            listOf("Declare fanout exchange orders-dlx", "Declare quorum queue orders-dlq", "Declare policy orders-dlx"),
            d.titles(), "the steps after the binding was removed",
        )
    }

    // ---- the review graph ----

    @Then("the review graph shows a dashed {string} edge from {string} to {string} and an edge from {string} to {string}")
    fun graphShows(label: String, from: String, to: String, edgeFrom: String, edgeTo: String) {
        assertEquals(PlannerTexts.DEAD_LETTER_EDGE_LABEL, label, "the label of the scenario is the constant of the planner")
        val graph = d.state.graph
        assertEquals(listOf(PlanLink("q:$from", "e:$to", PlanLinkKind.DeadLetter)), graph.links.filter { it.kind == PlanLinkKind.DeadLetter }, "state: dead-letter links")
        assertTrue(graph.graph.edges.any { it.edge.sourceId == "e:$edgeFrom" && it.edge.targetId == "q:$edgeTo" }, "state: no edge $edgeFrom to $edgeTo in ${graph.graph.edges.map { it.edge.sourceId to it.edge.targetId }}")
        val screen = PlannerScreens.forViewModel(d.vm)
        assertEquals(label, screen.textOf("planGraphLink:q:$from:e:$to"), "screen: the label of the dead-letter link")
    }

    @Then("{string} is drawn as already on the broker")
    fun drawnAsExisting(queue: String) {
        assertEquals(PlanMark.Existing, d.state.graph.markOf("q:$queue"), "state: mark of q:$queue")
        assertEquals("queue $queue, already on the broker", PlannerScreens.forViewModel(d.vm).description("planGraphNode:q:$queue"), "screen: what q:$queue announces")
    }
}
