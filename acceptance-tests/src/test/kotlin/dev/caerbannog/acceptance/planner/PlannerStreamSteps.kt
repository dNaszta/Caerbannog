package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.PlanLink
import dev.caerbannog.core.planner.PlanLinkKind
import dev.caerbannog.core.planner.PlanMark
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StreamForm
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07 steps for the `@m9-07` scenarios of `21_topology_planner_recipes.feature`: the Stream kind and its response stream. The view model is the
 * real one over fakes (the shared [PlannerDriver]); every claim about a screen is also read from the REAL planner screen ([PlannerScreens]): the
 * tick, the field of the response stream, the pair's second line and the dotted link. No broker: nothing here can send a request.
 *
 * Two stand-ins until M9-12 (open a pending file as steps) exists, both marked where they are used:
 *  - "a pending migration ... that declares the streams" puts those actions in the draft file, which is what a file opened as steps becomes;
 *  - "I choose "Edit steps" for ..." restarts the planner on that draft. M9-12 binds the same sentence (the 19 feature has it) to the real
 *    Migrations screen button and must replace this step, not add a second one.
 */
class PlannerStreamSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    /** The title of the summary page the last wizard run read before Add to plan (the wizard is closed by the time the scenario asks). */
    private var summaryTitleSeen: String? = null

    private fun currentStream(): StreamForm = d.form as? StreamForm ?: error("the open form is ${d.form}, not a stream form")

    private fun tickOn(): StreamForm {
        val open = currentStream()
        assertTrue(screen.isDrawn("planForm:responseTick"), "the tick is not drawn on the stream form")
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, "Also create its response stream (DLN)")
        assertTrue(screen.textOf("planForm:responseTick").orEmpty().contains(PlannerTexts.RESPONSE_STREAM_TICK), "the tick says: ${screen.textOf("planForm:responseTick")}")
        // Ticked on the screen, as a user does: the form keeps its own fields while the page is on show, so an event sent past it would not show.
        screen.click("planForm:responseTick")
        assertTrue(screen.isOn("planForm:responseTick"), "the tick is not on after it was ticked")
        assertTrue(currentStream().withResponse, "the state does not hold the tick (the form was ${open})")
        return currentStream()
    }

    private fun addPair(form: StreamForm) {
        d.fill(form)
        d.next()
        summaryTitleSeen = d.summaryTitle()
        d.addToPlan()
    }

    // ---- adding ----

    @When("I add a stream {string} with the wizard")
    fun addStream(name: String) { summaryTitleSeen = null; d.addWithWizard(d.streamForm(name)) }

    @When("I add a stream {string} with the wizard and tick {string}")
    fun addStreamTicked(name: String, tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick, "the tick of the scenario is the constant of the planner")
        addPair(d.streamForm(name, withResponse = true))
    }

    @When("I add a stream {string} with the response stream named {string}")
    fun addStreamNamed(name: String, response: String) = addPair(d.streamForm(name, withResponse = true, responseName = response))

    @Given("a plan with the stream {string} and its response stream {string}")
    fun planWithPair(name: String, response: String) {
        d.newPlan()
        d.addWithWizard(d.streamForm(name, withResponse = true, responseName = response))
        assertEquals(listOf("Declare stream $name", "Declare stream $response"), d.titles())
    }

    // ---- the form ----

    @When("I open the stream form")
    fun openStreamForm() = d.fill(d.streamForm(""))

    @Then("{string} is not ticked")
    fun notTicked(tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick)
        assertEquals(false, currentStream().withResponse, "the tick is on in the state")
        assertTrue(screen.isDrawn("planForm:responseTick"), "the tick is not drawn")
        assertEquals(false, screen.isOn("planForm:responseTick"), "the tick is on on the screen")
        assertTrue(!screen.isDrawn("planForm:responseName"), "the response stream's name field is drawn while the tick is off")
    }

    @When("I tick {string} in the stream form")
    fun tickInForm(tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick)
        d.fill(d.streamForm(""))
        tickOn()
    }

    @When("I enter the stream name {string} and tick {string}")
    fun enterAndTick(name: String, tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick)
        d.fill(d.streamForm(""))
        if (name.isNotEmpty()) screen.replaceText("planForm:name", name)
        assertEquals(name, currentStream().name)
        tickOn()
    }

    @When("I change the stream name to {string}")
    fun changeName(name: String) { screen.replaceText("planForm:name", name); assertEquals(name, currentStream().name) }

    @When("I change the response stream name to {string}")
    fun changeResponseName(name: String) { screen.replaceText("planForm:responseName", name); assertEquals(name, currentStream().responseName) }

    @Then("the response stream name is suggested as {string}")
    fun suggestedAs(expected: String) {
        val form = currentStream()
        assertEquals(null, form.responseName, "the user has not edited it, so the name is a suggestion")
        assertEquals(expected, form.effectiveResponseName(d.context()), "the suggestion in the state")
        assertEquals(expected, screen.fieldText("planForm:responseName"), "the suggestion on the screen")
    }

    @Then("the response stream name is still {string}")
    fun stillNamed(expected: String) {
        assertEquals(expected, currentStream().effectiveResponseName(d.context()), "the name in the state")
        assertEquals(expected, screen.fieldText("planForm:responseName"), "the name on the screen")
    }

    @Then("the response stream name field showed the hint {string}")
    fun fieldHint(hint: String) {
        assertEquals(listOf(hint), d.lastHints.filter { it.field == "responseName" }.map { it.message }, "hints of the response stream field")
        assertEquals(emptyList(), d.lastHints.filter { it.field == "name" }, "no hint on the stream's own name")
    }

    /** From `PlannerSteps.formSays`: the form's fixed note, in the state's texts and on the screen. */
    companion object {
        fun noteSaid(note: String) {
            val world = PlannerWorld.driver
            assertTrue((world.form as? StreamForm)?.withResponse == true, "the form is not ticked")
            val screen = PlannerScreens.forViewModel(world.vm)
            assertEquals(note, screen.textOf("planForm:responseNote"), "the note on the screen")
        }
    }

    // ---- the summary and the plan ----

    @Then("the summary says {string}")
    fun summarySays(title: String) = assertEquals(title, summaryTitleSeen, "the wizard's summary page said")

    @Then("the plan lists {string} and nothing else")
    fun listsAndNothingElse(title: String) = assertEquals(listOf(title), d.titles(), "the plan's steps")

    @Then("the plan lists {string} and {string}")
    fun listsBoth(first: String, second: String) = assertEquals(listOf(first, second), d.titles().filter { it == first || it == second }, "the plan's steps")

    @Then("the plan holds no exchange, no binding and no policy")
    fun noRouting() {
        val other = d.plan.steps.map { it.action }.filter { it !is MigrationAction.DeclareStream }
        assertEquals(emptyList(), other.map { it.description }, "steps that are not streams")
        assertEquals(emptyList(), d.state.graph.graph.edges, "an edge in the review graph")
        assertEquals(emptyList(), d.state.graph.notes, "a note in the review graph")
    }

    // ---- the pair ----

    private fun indexOf(title: String): Int {
        val i = d.plan.steps.indexOfFirst { it.title == title }
        assertTrue(i >= 0, "no step \"$title\" in ${d.titles()}")
        return i
    }

    // Cucumber expressions: a bare "(DLN)" would be optional text, so the parentheses are escaped.
    @Then("the step {string} is shown as the response stream \\(DLN\\) of {string}")
    fun shownAsResponse(title: String, of: String) {
        val i = indexOf(title)
        val line = "Response stream (DLN) of $of"
        assertEquals(line, d.state.responseStreamLine(d.plan.steps[i].id), "state: the second line of \"$title\"")
        assertEquals(line, screen.textOf("planStepPair:$i"), "screen: the second line of row $i")
    }

    @Then("neither step is shown as a response stream")
    fun neither() {
        assertTrue(d.plan.steps.size >= 2)
        assertEquals(emptyList(), d.plan.steps.filter { d.state.responseStreamLine(it.id) != null }.map { it.title }, "steps the state shows as a response stream")
        assertEquals(emptyList(), screen.tagsWithPrefix("planStepPair:"), "rows the screen shows as a response stream")
    }

    @Then("it is no longer shown as a response stream")
    fun noLonger() {
        val step = d.plan.steps.single()
        assertNull(d.state.responseStreamLine(step.id))
        assertEquals(emptyList(), screen.tagsWithPrefix("planStepPair:"))
        assertEquals(emptyList(), screen.tagsWithPrefix("planGraphLink:"), "a link is still drawn")
    }

    @Then("the review graph shows {string} and {string} marked {string} and linked as {string}")
    fun graphLinked(request: String, response: String, mark: String, label: String) {
        assertEquals("new", mark)
        assertEquals(PlannerTexts.RESPONSE_LINK_LABEL, label, "the label of the scenario is the constant of the planner")
        listOf(request, response).forEach { name ->
            val id = "q:$name"
            assertEquals(PlanMark.New, d.state.graph.markOf(id), "state: mark of $id")
            assertEquals("stream $name, new", screen.description("planGraphNode:$id"), "screen: what $id announces")
            assertTrue(screen.isDrawn("planGraphNew:$id"), "screen: the \"new\" pill of $id")
        }
        assertEquals(listOf(PlanLink("q:$request", "q:$response", PlanLinkKind.ResponseStream)), d.state.graph.links, "state: links")
        assertEquals(label, screen.textOf("planGraphLink:q:$request:q:$response"), "screen: the label of the link")
    }

    // ---- a saved file (stand-ins until M9-12) ----

    @Given("a pending migration {string} that declares the streams {string} and {string}")
    fun pendingStreams(version: String, first: String, second: String) {
        PendingStandIn.put(d, version, listOf(MigrationAction.DeclareStream(first, vhost = d.vhost), MigrationAction.DeclareStream(second, vhost = d.vhost)))
    }

    @When("I choose {string} for {string}")
    fun chooseFor(button: String, version: String) {
        assertEquals("Edit steps", button, "this stand-in knows one button; M9-12 binds the real ones")
        PendingStandIn.open(d, version)
    }
}

/**
 * The one stand-in for M9-12 ("Edit steps" opens a pending file as steps), shared by every step class of the planner glue: [put] writes the actions of
 * the pending file into the draft file, [open] restarts the planner on it. M9-12 binds the sentence `I choose {string} for {string}` to the real
 * Migrations screen and must replace [open] (and the Givens that call [put]), not add a second step.
 */
internal object PendingStandIn {
    private var version: String? = null
    private var count = 0

    fun put(d: PlannerDriver, version: String, actions: List<MigrationAction>) {
        this.version = version
        count = actions.size
        d.store.putDraft("", actions)
    }

    fun open(d: PlannerDriver, version: String) {
        assertEquals(this.version, version, "no pending migration \"$version\" was put by a Given")
        d.restart()
        assertNotNull(d.vm)
        assertTrue(d.plan.steps.size == count, "the file's steps were not opened: ${d.titles()}")
    }
}
