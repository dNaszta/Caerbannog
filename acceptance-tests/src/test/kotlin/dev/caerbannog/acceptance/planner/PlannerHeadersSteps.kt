package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.HeaderMatch
import dev.caerbannog.core.planner.HeaderValueType
import dev.caerbannog.core.planner.MatchRule
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9-11 steps for the `@m9-11` scenarios of `21_topology_planner_recipes.feature`: bindings on a headers exchange. The view model is the real one over
 * fakes (the shared [PlannerDriver]); the claims about the form are read from the state AND from the real planner screen ([PlannerScreens]), with the tags of
 * `BindingFormHeadersUiTest`. No broker: nothing here can send a request. A binding is built from the plan's own exchange and queue unless the
 * sentence names them.
 *
 * M9-12: the two Givens "a pending migration ... with the arguments ..." write a real pending file (`PlannerFiles.put`) like the M9-10 ones.
 */
class PlannerHeadersSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    /** Steps in the plan when the open form was filled: "no step is added" is a claim about what the wizard does after that. */
    private var stepsBefore = 0

    // ---- a plan ----

    private fun planWith(exchange: String, type: String, queue: String) {
        d.newPlan()
        d.addWithWizard(d.exchangeForm(exchange, type))
        d.addWithWizard(d.queueForm(queue, "quorum"))
        assertEquals(2, d.plan.steps.size, "the plan: ${d.titles()}")
    }

    @Given("a plan that declares the headers exchange {string} and the quorum queue {string}")
    fun headersPlan(exchange: String, queue: String) = planWith(exchange, "headers", queue)

    @Given("a plan that declares the fanout exchange {string} and the quorum queue {string}")
    fun fanoutPlan(exchange: String, queue: String) = planWith(exchange, "fanout", queue)

    @Given("a plan with a headers binding whose header {string} is the number {int} and whose header {string} is true")
    fun planWithTypedHeaders(number: String, value: Int, flag: String) {
        d.chooseMigrationsFolder("/work/migrations", "0004")
        planWith("documents", "headers", "reports")
        d.addWithWizard(
            BindingForm(
                exchange = "documents", destination = "reports",
                headers = listOf(HeaderMatch(number, HeaderValueType.WHOLE, value.toString()), HeaderMatch(flag, HeaderValueType.FLAG, "true")),
            ),
        )
    }

    private fun queueName(): String = d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclareQueue>().last().name
    private fun headersExchangeName(): String =
        d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclareExchange>().last { it.type == "headers" }.name

    // ---- adding a binding through the wizard ----

    private fun closeWizard() { if (d.state.wizard !is WizardState.Closed) d.cancelWizard() }

    private fun add(form: BindingForm) {
        closeWizard()
        d.addWithWizard(form)
    }

    private fun text(name: String, value: String) = HeaderMatch(name, HeaderValueType.TEXT, value)

    @When("I add a binding from {string} to the queue {string} that matches all of the headers")
    fun addAllOf(exchange: String, queue: String, table: DataTable) {
        closeWizard()
        d.fill(BindingForm(exchange = exchange, destination = queue, headers = table.asLists().map { text(it[0], it[1]) }, matchRule = MatchRule.ALL))
        // What the wizard page showed while it was open (it is gone after "Add to plan").
        routingKeyAsked = (d.state.wizard as WizardState.Details).asksRoutingKey
        routingKeyDrawn = screen.tagsWithPrefix("planForm:routingKey").isNotEmpty()
        d.finishWizard()
    }

    private var routingKeyAsked: Boolean? = null
    private var routingKeyDrawn: Boolean? = null

    @Then("the binding form showed no routing key field")
    fun showedNoKey() {
        assertEquals(false, routingKeyAsked, "state: the routing key was asked")
        assertEquals(false, routingKeyDrawn, "screen: the routing key field was drawn")
    }

    @When("I add a binding from {string} to {string} that matches the header {string} {string}")
    fun addFromTo(exchange: String, queue: String, name: String, value: String) = add(BindingForm(exchange, queue, headers = listOf(text(name, value))))

    @When("I add a binding that matches the header {string} {string}")
    fun addHeader(name: String, value: String) = add(BindingForm(headersExchangeName(), queueName(), headers = listOf(text(name, value))))

    @When("I add a binding that matches the header {string} with the number {int}")
    fun addNumber(name: String, value: Int) =
        add(BindingForm(headersExchangeName(), queueName(), headers = listOf(HeaderMatch(name, HeaderValueType.WHOLE, value.toString()))))

    @When("I add a binding that matches the header {string} with the text {string}")
    fun addTextHeader(name: String, value: String) = add(BindingForm(headersExchangeName(), queueName(), headers = listOf(text(name, value))))

    @Then("the plan lists {string} with the detail {string}")
    fun listsWithDetail(title: String, detail: String) {
        val row = d.titles().indexOf(title)
        assertTrue(row >= 0, "the plan's steps are ${d.titles()}, expected \"$title\"")
        assertEquals(detail, d.plan.steps[row].action.detail, "the detail of \"$title\"")
        assertTrue(screen.textOf("planStep:$row").orEmpty().contains(detail), "row $row says: ${screen.textOf("planStep:$row")}, expected the detail \"$detail\"")
    }

    @Then("the step detail ends with {string}")
    fun detailEndsWith(end: String) {
        val detail = d.plan.steps.last().action.detail.orEmpty()
        assertTrue(detail.endsWith(end), "the detail of the last step is \"$detail\", expected it to end with \"$end\"")
    }

    @Then("the plan has both bindings and no problem")
    fun bothBindings() {
        val bindings = d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclareBinding>()
        assertEquals(2, bindings.size, "the plan: ${d.titles()}")
        assertEquals(2, bindings.toSet().size, "the two bindings are two different steps")
        assertEquals(emptyList(), d.problems().map { it.message }, "plan problems")
    }

    @Then("the first is saved with {string} as the number {int} and the second with {string} as the text {string}")
    fun firstAndSecondSaved(first: String, number: Int, second: String, text: String) {
        val saved = savedBindings()
        assertEquals(ArgumentValue.Whole(number.toLong()), saved[0].arguments[first], "first binding in the saved file")
        assertEquals(ArgumentValue.Text(text), saved[1].arguments[second], "second binding in the saved file")
    }

    // ---- saving ----

    private fun savedFile(): String {
        if (d.state.saved == null) {
            d.chooseMigrationsFolder("/work/migrations", "0004")
            d.vm.saveAsMigration()
        }
        val version = d.state.saved?.version ?: error("the plan was not saved; error: ${d.state.errorMessage}")
        return d.migrationFolder.files.getValue(version).rawJson
    }

    private fun savedBindings(): List<MigrationAction.DeclareBinding> {
        val version = d.state.saved?.version ?: run { savedFile(); d.state.saved!!.version }
        return d.migrationFolder.files.getValue(version).up.filterIsInstance<MigrationAction.DeclareBinding>()
    }

    @Then("the saved file holds {string} as the number {int} and {string} as the boolean true")
    fun savedTyped(number: String, value: Int, flag: String) {
        val raw = savedFile()
        val binding = savedBindings().single()
        assertEquals(ArgumentValue.Whole(value.toLong()), binding.arguments[number], "parsed: $number")
        assertEquals(ArgumentValue.Flag(true), binding.arguments[flag], "parsed: $flag")
        assertTrue(Regex(""""$number"\s*:\s*$value(?![\d."])""").containsMatchIn(raw), "the file holds $number as a JSON number: $raw")
        assertTrue(Regex(""""$flag"\s*:\s*true\b""").containsMatchIn(raw), "the file holds $flag as a JSON boolean: $raw")
    }

    // ---- the form ----

    @When("I open the binding form for {string}")
    fun openFor(exchange: String) = open(BindingForm(exchange, queueName()))

    private fun open(form: BindingForm) {
        closeWizard()
        stepsBefore = d.plan.steps.size
        d.fill(form)
    }

    @When("I open the binding form for {string}, choose the match rule {string} and add no header")
    fun openWithRule(exchange: String, rule: String) {
        val chosen = MatchRule.entries.singleOrNull { it.wire == rule } ?: error("the form offers no rule \"$rule\"")
        open(BindingForm(exchange, queueName(), matchRule = chosen))
    }

    @When("I open the binding form for {string} and give it the header {string} with the {word} {string}")
    fun openWithOneHeader(exchange: String, name: String, type: String, value: String) {
        val kind = when (type) {
            "text" -> HeaderValueType.TEXT
            "number" -> HeaderValueType.WHOLE
            else -> error("this step knows the types text and number, not \"$type\"")
        }
        open(BindingForm(exchange, queueName(), headers = listOf(HeaderMatch(name, kind, value))))
    }

    @When("I open the binding form for {string} and give it the header {string} {string} and the header {string} {string}")
    fun openWithTwoHeaders(exchange: String, n1: String, v1: String, n2: String, v2: String) =
        open(BindingForm(exchange, queueName(), headers = listOf(text(n1, v1), text(n2, v2))))

    @When("I open the binding form and choose the exchange {string}")
    fun openAndChoose(exchange: String) = open(BindingForm(exchange, queueName()))

    @Then("the form shows no routing key field")
    fun noKeyField() {
        assertFalse((d.state.wizard as WizardState.Details).asksRoutingKey, "state: the routing key is asked")
        assertEquals(emptyList(), screen.tagsWithPrefix("planForm:routingKey"), "screen: the routing key field")
    }

    @Then("the match rules offered are {string} and {string}")
    fun rulesOffered(first: String, second: String) {
        assertEquals(listOf(first, second), MatchRule.entries.map { it.wire }, "state: the rules of the form")
        assertEquals(listOf("planForm:matchRule:$first", "planForm:matchRule:$second"), screen.tagsWithPrefix("planForm:matchRule:"), "screen: the choices drawn")
    }

    @Then("{string} is chosen at the start")
    fun chosenAtStart(rule: String) {
        val form = assertIs<BindingForm>(d.form)
        assertEquals(rule, form.matchRule.wire)
        assertEquals(null, form.heldRule)
    }

    @Then("no step is added")
    fun noStepAdded() {
        // What the buttons do: Next is off, and pressing the path to "Add to plan" anyway changes nothing.
        assertFalse(d.nextEnabled(), "Next should be disabled; problems=${d.formProblems()}")
        d.next()
        d.addToPlan()
        assertEquals(stepsBefore, d.plan.steps.size, "the plan after the refused form: ${d.titles()}")
    }

    companion object {
        private val headerTexts = setOf(
            PlannerTexts.HEADER_NAME_MISSING, PlannerTexts.HEADER_NAME_IS_MATCH_RULE, PlannerTexts.HEADER_LISTED_TWICE, PlannerTexts.HEADER_WHOLE_DIGITS_ONLY,
        )

        /** `the form says "<a headers warning>"`: a hint on the headers, never a problem; drawn on the screen too. */
        fun warningSaid(message: String) {
            val world = PlannerWorld.driver
            assertEquals(emptyList(), world.formProblems().map { it.message }, "a warning is no problem")
            assertTrue(world.nextEnabled(), "a warning never disables Next")
            assertEquals(listOf(message), world.lastHints.filter { it.field == "headers" }.map { it.message }, "hints on the headers")
            assertEquals(message, PlannerScreens.forViewModel(world.vm).textOf("planForm:hint:headers"), "the warning on the screen")
        }

        /** A header refusal is also under the rows on the screen. */
        fun problemOnScreen(message: String) {
            val world = PlannerWorld.driver
            if (message !in headerTexts || world.form !is BindingForm) return
            assertEquals(message, PlannerScreens.forViewModel(world.vm).textOf("planForm:error:headers"), "the refusal on the screen")
        }
    }

    // ---- a pending file: a real file in the fake folder, opened by "Edit steps" (M9-12) ----

    private fun pending(version: String, exchange: String, queue: String, arguments: Map<String, ArgumentValue>) {
        val action = MigrationAction.DeclareBinding(exchange = exchange, queue = queue, vhost = d.vhost, arguments = arguments)
        PlannerFiles.put(version, listOf(action))
    }

    @Given("a pending migration {string} with a binding from the headers exchange {string} to the queue {string} with the arguments {string} {string} and {string} {string}")
    fun pendingTextArguments(version: String, exchange: String, queue: String, k1: String, v1: String, k2: String, v2: String) =
        pending(version, exchange, queue, linkedMapOf(k1 to ArgumentValue.Text(v1), k2 to ArgumentValue.Text(v2)))

    @Given("a pending migration {string} with a binding from the headers exchange {string} to the queue {string} with the arguments {string} {string} and {string} {int}")
    fun pendingNumberArgument(version: String, exchange: String, queue: String, k1: String, v1: String, k2: String, v2: Int) =
        pending(version, exchange, queue, linkedMapOf(k1 to ArgumentValue.Text(v1), k2 to ArgumentValue.Whole(v2.toLong())))

    @Then("the step is shown as a binding with the detail {string}")
    fun shownAsBindingWithDetail(detail: String) {
        val step = d.plan.steps.single()
        val action = assertIs<MigrationAction.DeclareBinding>(step.action)
        assertEquals(detail, action.detail, "the detail of the step")
        assertTrue(step.isEditable, "the step is read-only; a binding of headers and x-match opens in the form")
        assertFalse(d.state.stepTitle(step.id).startsWith("MQTT route"), "worded as a route: ${d.state.stepTitle(step.id)}")
        assertTrue(screen.textOf("planStep:0").orEmpty().contains(detail), "the row says: ${screen.textOf("planStep:0")}")
    }

    @Then("its form holds the header {string} as the number {int} and the match rule {string}")
    fun formHolds(name: String, value: Int, rule: String) {
        d.vm.editStep(d.plan.steps.single().id)
        val form = assertIs<BindingForm>(d.form, "the form that holds the step")
        assertEquals(listOf(HeaderMatch(name, HeaderValueType.WHOLE, value.toString())), form.headers, "headers of the form")
        assertEquals(rule, form.matchRule.wire)
        assertEquals(null, form.heldRule)
        assertEquals(name, assertNotNull(screen.fieldText("planForm:header:0:name"), "the headers rows are not drawn"), "screen: the name field of row 0")
        assertEquals(value.toString(), screen.fieldText("planForm:header:0:value"), "screen: the value field of row 0")
        assertTrue(assertIs<WizardState.Details>(d.state.wizard).asksHeaders, "the section is shown for a form that holds headers")
    }
}
