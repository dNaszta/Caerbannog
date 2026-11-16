package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.SuggestionSource
import dev.caerbannog.core.topology.BindingDestinationType
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** The driver of the running scenario, shared by the step classes of this package (Cucumber has no dependency injection here). */
object PlannerWorld {
    lateinit var driver: PlannerDriver
}

/** M9-01 steps for `19_topology_planner.feature`; the draft, save and dismiss steps of M9-02 are in [PlannerDraftSteps]. Both use the one driver of [PlannerWorld]. */
class PlannerSteps {
    private var d = PlannerDriver()

    /** The plan's own steps by description: only the three kinds of this task are written in a table. */
    private val declareQueueRow = Regex("^Declare (\\w+) queue (.+)$")
    private val declareExchangeRow = Regex("^Declare (\\w+) exchange (.+)$")
    private val bindRow = Regex("^Bind (.+) to (.+) with (.+)$")

    @Before
    fun setUp() { d = PlannerDriver(); PlannerWorld.driver = d }

    @After
    fun tearDown() { PlannerScreens.endScenario(); d.close() }

    // ---- Background ----

    @Given("I am connected to {string} with the virtual host {string}")
    fun connected(profile: String, vhost: String) { d.profile = profile; d.vhost = vhost; d.restart() }

    @Given("the user has configure permission on {string}")
    fun configure(vhost: String) { assertEquals(d.vhost, vhost); d.configurePermission = true }

    @Given("the connection {string} has the topology control level {string}")
    fun level(profile: String, label: String) {
        d.controlLevel = when (label) {
            "Read only", "Read-only" -> TopologyControlLevel.READ_ONLY
            "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            else -> error("unknown topology control level \"$label\"")
        }
    }

    @Given("the migrations folder is {string} and its latest migration file is {string}")
    fun folder(path: String, latest: String) = d.chooseMigrationsFolder(path, latest)

    // ---- Plans ----

    @When("I start a new plan")
    fun startPlan() = d.newPlan()

    @Given("a new plan")
    fun aNewPlan() = d.newPlan()

    @Given("a new plan with the naming style {string}")
    fun aNewPlanWithStyle(label: String) { d.style = style(label); d.newPlan() }

    @Given("a plan that declares the quorum queue {string}")
    fun planWithQueue(name: String) { d.newPlan(); d.addWithWizard(d.queueForm(name, "quorum")) }

    @Given("a plan that declares the quorum queue {string} and the direct exchange {string}")
    fun planWithQueueAndExchange(queue: String, exchange: String) {
        d.newPlan(); d.addWithWizard(d.queueForm(queue, "quorum")); d.addWithWizard(d.exchangeForm(exchange, "direct"))
    }

    @Given("a plan with the naming style {string} that declares the quorum queue {string}")
    fun planWithStyleAndQueue(label: String, name: String) {
        d.style = style(label); d.newPlan(); d.addWithWizard(d.queueForm(name, "quorum"))
    }

    @Given("a plan with the steps")
    fun planWithSteps(table: DataTable) {
        d.newPlan()
        table.asLists().forEach { d.addWithWizard(formOfRow(it.single())) }
    }

    @Given("the broker has the quorum queue {string}")
    fun brokerQueue(name: String) { d.known = d.known.copy(queues = d.known.queues + name) }

    @Given("the broker has the direct exchange {string}")
    fun brokerExchange(name: String) { d.known = d.known.copy(exchanges = d.known.exchanges + (name to "direct")) }

    @When("I choose the naming style {string}")
    fun chooseStyle(label: String) = d.chooseStyle(style(label))

    // ---- The wizard ----

    @When("I add a {word} queue {string} with the wizard")
    fun addQueue(type: String, name: String) = d.addWithWizard(d.queueForm(name, type))

    @When("I add a queue {string} with the wizard without choosing a type")
    fun addQueueDefault(name: String) = d.addWithWizard(d.queueForm(name, null))

    @When("I add a {word} exchange {string} with the wizard")
    fun addExchange(type: String, name: String) = d.addWithWizard(d.exchangeForm(name, type))

    @When("I add a binding from {string} to the queue {string} with routing key {string} with the wizard")
    fun addBinding(exchange: String, queue: String, key: String) = d.addWithWizard(d.bindingForm(exchange, queue, key))

    @When("I open the wizard")
    fun openWizard() = d.openWizard()

    @When("I choose {string} in the wizard and enter the name {string}")
    fun chooseAndEnter(kind: String, name: String) { d.fill(formFor(kind, name)); d.next() }

    @When("I choose {string} in the wizard, enter the name {string} and cancel")
    fun chooseEnterCancel(kind: String, name: String) { d.fill(formFor(kind, name)); d.next(); d.cancelWizard() }

    @When("I enter the queue name {string} in the wizard")
    fun enterQueueName(name: String) = d.fill(d.queueForm(name, null))

    @When("I finish the wizard")
    fun finish() = d.finishWizard()

    @When("I choose {string}")
    fun choose(button: String) = when (button) {
        "Add to plan" -> d.addToPlan()
        "Save as migration" -> d.vm.saveAsMigration()
        "Dismiss" -> d.vm.dismiss()
        "New plan" -> {
            assertTrue(d.entry() is PlannerEntry.NewPlan, "the Migrations screen offers ${d.entry()}, not \"New plan\"")
            d.newPlan() // the planner is opened on the screen of the real composable: PlannerScreenSteps looks at it
        }
        "Keep the plan" -> d.vm.cancelQuestion()
        "Start a new plan" -> d.vm.startNewPlan()
        "Continue plan" -> {
            assertTrue(d.entry() is PlannerEntry.ContinuePlan, "the Migrations screen offers ${d.entry()}, not \"Continue plan\"")
            d.restart()
            d.vm
            Unit
        }
        else -> error("no button \"$button\" is known to the steps")
    }

    @When("I fill the {string} form with {}")
    fun fillForm(kind: String, input: String) {
        val f = when (kind to input) {
            "Queue" to "an empty name" -> d.queueForm("", null)
            "Queue" to "the name \"..\"" -> d.queueForm("..", null)
            "Exchange" to "an empty name" -> d.exchangeForm("", "direct")
            "Binding" to "no exchange" -> d.bindingForm("", "orders", "k")
            "Binding" to "no destination" -> d.bindingForm("events", "", "k")
            else -> error("no input \"$input\" is known for the \"$kind\" form")
        }
        d.fill(f)
    }

    @When("I open the binding form")
    fun openBindingForm() = d.fill(d.bindingForm("", "", ""))

    /** Two uses of one sentence: the kinds of the wizard (a list), and the one entry button of the Migrations screen (`@m9-03`). */
    @Then("I can choose {}")
    fun canChoose(list: String) {
        val expected = Regex("\"([^\"]*)\"").findAll(list).map { it.groupValues[1] }.toList()
        if (expected.size == 1 && expected.single() in listOf("New plan", "Continue plan")) {
            assertEquals(expected.single(), d.entry().label, "the button of the Migrations screen")
            return
        }
        assertEquals(expected, PlannerTexts.WIZARD_KINDS, "the wizard kinds, in order")
    }

    @Then("there is no kind that deletes anything")
    fun noDeleteKind() = assertTrue(PlannerTexts.WIZARD_KINDS.none { it.contains("delete", true) || it.contains("remove", true) || it.contains("purge", true) })

    @Then("there is no kind for users, virtual hosts or permissions")
    fun noAccessKind() = assertTrue(
        PlannerTexts.WIZARD_KINDS.none { k -> listOf("user", "virtual host", "vhost", "permission").any { k.contains(it, true) } },
        "kinds: ${PlannerTexts.WIZARD_KINDS}",
    )

    @Then("there is no kind that reads, moves or purges messages")
    fun noMessageKind() = assertTrue(
        PlannerTexts.WIZARD_KINDS.none { k -> listOf("read", "move", "purge", "shovel", "consume", "get").any { k.contains(it, true) } },
        "kinds: ${PlannerTexts.WIZARD_KINDS}",
    )

    @Then("the summary says {string} and lists {string}")
    fun summary(title: String, line: String) {
        assertEquals(title, d.summaryTitle())
        assertTrue(d.summaryActions().any { it.description == line }, "summary lists ${d.summaryActions().map { it.description }}, expected \"$line\"")
    }

    @Then("the plan still has no step")
    fun stillNoStep() = assertEquals(emptyList<String>(), d.titles())

    @Then("the plan has no step")
    fun noStep() = assertEquals(emptyList<String>(), d.titles())

    /** A regular expression, not {string}: the parser's message holds quotes of its own (a name must not be "." or ".."). */
    @Then("^the form says \"(.*)\"$")
    fun formSays(message: String) = assertEquals(listOf(message), d.formProblems().map { it.message }, "form problems")

    @Then("{string} is disabled")
    fun disabled(button: String) = when (button) {
        "Next" -> assertFalse(d.nextEnabled(), "Next should be disabled; problems=${d.formProblems()}")
        "Check" -> {
            assertFalse(d.state.checkEnabled, "Check should be disabled")
            assertFalse(PlannerScreens.forViewModel(d.vm).isEnabled("planner:checkBtn"), "the Check button is enabled on the screen")
        }
        else -> error("no button \"$button\" is known to the steps")
    }

    @Then("{string} is enabled")
    fun enabled(button: String) = when (button) {
        "Next" -> assertTrue(d.nextEnabled(), "Next should be enabled; problems=${d.formProblems()}")
        "Save as migration" -> assertTrue(d.saveEnabled(), "Save as migration should be enabled; the gate says ${d.saveGate()}")
        else -> error("no button \"$button\" is known to the steps")
    }

    @Then("the name field shows {}")
    fun nameFieldShows(expected: String) {
        val hints = d.lastHints.filter { it.field == "name" }.map { it.message }
        if (expected == "no hint") {
            assertEquals(emptyList<String>(), hints, "hint on the name field")
        } else {
            val quoted = Regex("^the hint \"(.*)\"$").matchEntire(expected)?.groupValues?.get(1) ?: error("cannot read \"$expected\"")
            assertEquals(listOf(quoted), hints, "hint on the name field")
        }
    }

    @Then("no hint is shown for {string} and none for the routing key")
    fun noHintFor(name: String) {
        assertEquals(emptyList<String>(), d.lastHints.map { "${it.field}: ${it.message}" }, "hints of the binding form for $name")
    }

    @Then("the destinations offered are {string} from this plan and {string} from the broker")
    fun destinations(fromPlan: String, fromBroker: String) {
        val offered = d.context().destinationSuggestions(BindingDestinationType.QUEUE)
        assertEquals(
            listOf(fromPlan to SuggestionSource.PLAN, fromBroker to SuggestionSource.BROKER),
            offered.map { it.name to it.source },
            "destinations offered, plan first",
        )
    }

    // ---- The plan ----

    @Then("the plan lists these steps in this order")
    fun planLists(table: DataTable) = assertEquals(table.asLists().map { it.single() }, d.titles(), "the plan's steps")

    @Then("the plan lists {string}")
    fun planListsOne(title: String) = assertTrue(title in d.titles(), "expected \"$title\" among ${d.titles()}")

    @Then("the plan still lists {string}")
    fun planStillLists(title: String) = planListsOne(title)

    @Then("nothing was sent to the broker")
    fun nothingSent() = assertEquals(emptyList<MigrationAction>(), d.executor.executedActions, "actions sent to the broker")

    @Then("the plan shows the problem {string}")
    fun planShowsProblem(message: String) = assertTrue(message in d.problems().map { it.message }, "problems: ${d.problems().map { it.message }}")

    @Then("{string} is disabled and says {string}")
    fun saveDisabled(button: String, text: String) {
        assertEquals("Save as migration", button)
        assertFalse(d.saveEnabled(), "Save as migration should be disabled")
        assertEquals(text, d.saveHint())
    }

    @Then("step {int} shows the problem {string}")
    fun stepShowsProblem(position: Int, message: String) {
        val id = d.stepAt(position).id
        assertEquals(listOf(message), d.problems().filter { it.stepId == id }.map { it.message }, "problems of step $position")
    }

    @Then("the plan has no problem")
    fun noProblem() = assertEquals(emptyList<String>(), d.problems().map { it.message }, "plan problems")

    @When("I move step {int} down")
    fun moveDown(position: Int) = d.moveDown(position)

    @When("I move step {int} up")
    fun moveUp(position: Int) = d.moveUp(position)

    @When("I edit step {int} and change the routing key to {string}")
    fun editKey(position: Int, key: String) = d.editStep(position) { old ->
        (old as BindingForm).copy(routingKey = key)
    }

    @When("I remove the step {string}")
    fun remove(title: String) = d.removeByTitle(title)

    @Then("the binding is the last step")
    fun bindingLast() = assertTrue(d.plan.steps.last().action is MigrationAction.DeclareBinding, "last step: ${d.titles().last()}")

    @Then("every step of the plan belongs to the virtual host {string}")
    fun everyStepVhost(vhost: String) {
        assertEquals(vhost, d.plan.vhost)
        assertTrue(d.plan.steps.isNotEmpty())
        assertEquals(emptyList<String>(), d.plan.steps.filter { it.action.vhost != vhost }.map { it.action.description }, "steps on another virtual host")
    }

    // ---- helpers ----

    private fun style(label: String) = NamingStyle.entries.firstOrNull { it.label == label } ?: error("unknown naming style \"$label\"")

    private fun formFor(kind: String, name: String): dev.caerbannog.core.planner.StepForm = when (kind) {
        "Queue" -> d.queueForm(name, null)
        "Exchange" -> d.exchangeForm(name, "direct")
        else -> error("this task's steps know the kinds Queue and Exchange, not \"$kind\"")
    }

    /** A row of a step table is the form that builds it, so a plan from a table goes through the wizard like any other. */
    private fun formOfRow(row: String): dev.caerbannog.core.planner.StepForm {
        declareQueueRow.matchEntire(row)?.let { return d.queueForm(it.groupValues[2], it.groupValues[1]) }
        declareExchangeRow.matchEntire(row)?.let { return d.exchangeForm(it.groupValues[2], it.groupValues[1]) }
        bindRow.matchEntire(row)?.let { return d.bindingForm(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        error("the table row \"$row\" is not one of the three step kinds of this task")
    }
}
