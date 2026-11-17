package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.PlanLink
import dev.caerbannog.core.planner.PlanLinkKind
import dev.caerbannog.core.planner.PlanMark
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.PlanGraphNote
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
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
        readSummary()
        d.addToPlan()
    }

    /** What the summary page said (title and note, in the state and on the screen) before Add to plan closes the wizard. */
    private fun readSummary() {
        summaryTitleSeen = d.summaryTitle()
        summaryNoteSeen = (d.state.wizard as? WizardState.Summary)?.note
        summaryNoteOnScreen = screen.textOf("planWizard:summaryNote")
    }

    private var summaryNoteSeen: String? = null
    private var summaryNoteOnScreen: String? = null

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

        /** M9-07b: the problem of a tick is also under its field on the screen. */
        fun tickErrorOnScreen(message: String) {
            val world = PlannerWorld.driver
            val form = world.form as? StreamForm ?: return
            val screen = PlannerScreens.forViewModel(world.vm)
            val tagged = listOf("answersTopic" to form.answersTopic, "requestsTopic" to form.requestsTopic).filter { it.second != null }
            val problem = world.formProblems().firstOrNull { p -> tagged.any { it.first == p.field } } ?: return
            assertEquals(message, screen.textOf("planForm:error:${problem.field}"), "the error under ${problem.field}")
        }
    }

    // ---- the summary and the plan ----

    @Then("the summary says {string}")
    fun summarySays(title: String) {
        // M9-07b: the sentence of the summary's note (what the planner does not plan) is "said" by the summary too, in the state and on the page.
        if (title == PlannerTexts.SERVICE_NOT_PLANNED) {
            assertEquals(title, summaryNoteSeen, "state: the note of the summary page")
            assertEquals(title, summaryNoteOnScreen, "screen: the text of planWizard:summaryNote")
            return
        }
        assertEquals(title, summaryTitleSeen, "the wizard's summary page said")
    }

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

    // ---- M9-07b: the MQTT ticks (the form is driven on the REAL planner screen: the ticks are clicked and the filters typed there) ----

    private val answersLabel = "Answers arrive over MQTT"
    private val requestsLabel = "Requests are also recorded from an MQTT topic"

    private fun which(tick: String): String = when (tick) {
        answersLabel -> "answers"
        requestsLabel -> "requests"
        else -> error("\"$tick\" is not one of the two MQTT ticks")
    }

    private fun tickMqtt(tick: String, filter: String) {
        val side = which(tick)
        assertTrue(screen.isDrawn("planForm:${side}OverMqtt"), "the tick \"$tick\" is not drawn")
        assertTrue(screen.textOf("planForm:${side}OverMqtt").orEmpty().contains(tick), "the tick says: ${screen.textOf("planForm:${side}OverMqtt")}")
        screen.click("planForm:${side}OverMqtt")
        assertTrue(screen.isOn("planForm:${side}OverMqtt"), "the tick \"$tick\" is not on after it was ticked")
        if (filter.isNotEmpty()) screen.replaceText("planForm:${side}Topic", filter)
        val form = currentStream()
        assertEquals(filter, if (side == "answers") form.answersTopic else form.requestsTopic, "the filter in the state of the form")
    }

    private fun pairWithTicks(name: String, vararg ticks: Pair<String, String>) {
        d.fill(d.streamForm(name))
        screen.click("planForm:responseTick")
        assertTrue(currentStream().withResponse, "the pair tick is not on")
        ticks.forEach { (tick, filter) -> tickMqtt(tick, filter) }
    }

    private fun finishPair() {
        d.next()
        readSummary()
        d.addToPlan()
    }

    @When("I add a stream {string} with its response stream")
    fun addPairPlain(name: String) { pairWithTicks(name); finishPair() }

    @When("I add a stream {string} with its response stream and tick {string} with the topic filter {string}")
    fun addPairOneTick(name: String, tick: String, filter: String) { pairWithTicks(name, tick to filter); finishPair() }

    @When("I add a stream {string} with its response stream, tick {string} with the topic filter {string} and tick {string} with the topic filter {string}")
    fun addPairTwoTicks(name: String, first: String, firstFilter: String, second: String, secondFilter: String) {
        pairWithTicks(name, first to firstFilter, second to secondFilter)
        finishPair()
    }

    @Then("{string} and {string} are not offered")
    fun ticksNotOffered(first: String, second: String) {
        which(first); which(second)
        assertTrue(!screen.isDrawn("planForm:answersOverMqtt"), "the answers tick is drawn while the pair tick is off")
        assertTrue(!screen.isDrawn("planForm:requestsOverMqtt"), "the requests tick is drawn while the pair tick is off")
        assertTrue(screen.isDrawn("planForm:responseTick"), "the form is not on show: the pair tick is not drawn")
    }

    @When("I tick {string}")
    fun tickPair(tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick)
        tickOn()
    }

    @When("I untick {string}")
    fun untickPair(tick: String) {
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, tick)
        screen.click("planForm:responseTick")
        assertEquals(false, screen.isOn("planForm:responseTick"), "the pair tick is still on")
        assertEquals(false, currentStream().withResponse, "the state still holds the pair")
    }

    @Then("both are offered and neither is ticked")
    fun bothOffered() {
        listOf("answers" to answersLabel, "requests" to requestsLabel).forEach { (side, label) ->
            assertTrue(screen.isDrawn("planForm:${side}OverMqtt"), "the $side tick is not drawn")
            assertTrue(screen.textOf("planForm:${side}OverMqtt").orEmpty().contains(label), "the $side tick says: ${screen.textOf("planForm:${side}OverMqtt")}")
            assertEquals(false, screen.isOn("planForm:${side}OverMqtt"), "the $side tick is on on the screen")
            assertTrue(!screen.isDrawn("planForm:${side}Topic"), "the $side filter field is drawn while its tick is off")
        }
        assertEquals(null, currentStream().answersTopic, "state: answers tick")
        assertEquals(null, currentStream().requestsTopic, "state: requests tick")
    }

    @Then("the route is the action {string} from {string} to {string} with routing key {string}")
    fun routeIsAction(name: String, exchange: String, destination: String, key: String) {
        val route = d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclareBinding>().single()
        assertEquals(name, route.actionName)
        assertEquals(exchange, route.exchange)
        assertEquals(destination, route.queue)
        assertEquals(key, route.routingKey)
        assertEquals(emptyMap(), route.arguments)
    }

    @When("I tick {string} in the stream form and enter the topic filter {string}")
    fun tickAndEnter(tick: String, filter: String) {
        // The stream has a valid name, so the only problem of the form is the one of the filter.
        pairWithTicks("device-commands", tick to filter)
    }

    @Given("the stream form with the response stream and {string} ticked")
    fun formWithTick(tick: String) = pairWithTicks("device-commands", tick to "devices/+/answer")

    @Then("the MQTT ticks are not offered any more")
    fun ticksGone() {
        listOf("answersOverMqtt", "requestsOverMqtt", "answersTopic", "requestsTopic", "mqttAssumption").forEach {
            assertTrue(!screen.isDrawn("planForm:$it"), "planForm:$it is still drawn")
        }
        assertEquals(StreamForm(name = "device-commands"), currentStream(), "the state of the form: both ticks are cleared with the pair")
    }

    @Then("the summary holds the stream only")
    fun summaryStreamOnly() {
        d.next()
        readSummary()
        assertEquals(listOf(MigrationAction.DeclareStream("device-commands", vhost = d.vhost)), d.summaryActions())
        assertEquals("This adds 1 step", summaryTitleSeen)
        assertEquals(null, summaryNoteSeen, "no sentence about a service without a route")
        assertEquals(null, summaryNoteOnScreen, "no planWizard:summaryNote without a route")
    }

    @Given("a plan with the stream pair {string} and the answers route {string} added by the wizard")
    fun planWithPairAndRoute(name: String, filter: String) {
        d.newPlan()
        pairWithTicks(name, answersLabel to filter)
        finishPair()
        assertEquals(3, d.plan.steps.size, "the pair and its route: ${d.titles()}")
    }

    @Then("the review graph shows no note about a service")
    fun noNote() {
        assertTrue(screen.isDrawn("planner:reviewGraph"), "the review graph is not drawn, so \"no note\" proves nothing")
        assertEquals(emptyList(), d.state.graph.notes, "state: notes")
        assertEquals(emptyList(), screen.tagsWithPrefix("planGraphNote:"), "screen: notes")
    }

    @Then("the review graph still shows no note about a service")
    fun stillNoNote() = noNote()

    @Then("it shows the note {string} between {string} and {string}")
    fun showsNote(text: String, request: String, exchange: String) {
        assertEquals(listOf(PlanGraphNote("q:$request", "e:$exchange", text)), d.state.graph.notes, "state: the notes of the graph")
        assertEquals(listOf("planGraphNote:0"), screen.tagsWithPrefix("planGraphNote:"), "screen: the notes drawn")
        assertEquals(text, screen.textOf("planGraphNote:0"), "screen: the text of the note")
        listOf("q:$request", "e:$exchange").forEach { assertTrue(screen.isDrawn("planGraphNode:$it"), "the node $it is not drawn") }
    }

    @Then("the note is not a step and cannot be changed")
    fun noteIsNotAStep() {
        assertEquals(3, d.plan.steps.size, "the plan holds the two streams and the route: ${d.titles()}")
        assertTrue(d.titles().none { it.contains(PlannerTexts.SERVICE_NOT_PLANNED) }, "the note is a step: ${d.titles()}")
        assertTrue(d.store.savedTexts.none { it.contains(PlannerTexts.SERVICE_NOT_PLANNED) }, "the note is in the draft file")
        assertEquals(emptyList(), screen.actionsUnder("planGraphNote:0"), "the note offers an action")
        val before = d.plan
        screen.click("planGraphNote:0")
        assertEquals(before, d.plan, "a click on the note changed the plan")
        assertEquals(emptyList(), d.executor.executedActions, "a click on the note reached the broker")
    }

    // ---- a saved file (stand-ins until M9-12) ----

    // Stand-in until M9-12 (open a pending file as steps), like the two Givens of M9-07 and the three of M9-10: puts the file's actions in the draft file.
    @Given("a pending migration {string} that declares the streams {string} and {string} and binds {string} to {string} with routing key {string}")
    fun pendingPairWithRoute(version: String, first: String, second: String, exchange: String, destination: String, key: String) {
        PendingStandIn.put(
            d, version,
            listOf(
                MigrationAction.DeclareStream(first, vhost = d.vhost), MigrationAction.DeclareStream(second, vhost = d.vhost),
                MigrationAction.DeclareBinding(exchange, destination, key, vhost = d.vhost),
            ),
        )
    }

    @Then("the review graph shows the note about the owner's service")
    fun graphShowsNote() {
        assertEquals(listOf(PlanGraphNote("q:device-commands", "e:amq.topic", PlannerTexts.SERVICE_NOT_PLANNED)), d.state.graph.notes, "state: the notes of the graph")
        assertEquals(PlannerTexts.SERVICE_NOT_PLANNED, screen.textOf("planGraphNote:0"), "screen: the note")
    }

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

    /** The one binding a Given put (M9-10, M9-11): "saving writes the binding back unchanged" compares the saved file with it. */
    var binding: MigrationAction? = null

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
