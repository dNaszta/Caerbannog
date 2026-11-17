package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PatternMatchResult
import dev.caerbannog.core.planner.PatternMatches
import dev.caerbannog.core.planner.PlanCheckResult
import dev.caerbannog.core.planner.PolicyForm
import dev.caerbannog.core.planner.PolicyKeyCatalog
import dev.caerbannog.core.planner.PolicySetting
import dev.caerbannog.core.planner.PolicyTarget
import dev.caerbannog.core.planner.PolicyValueKind
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.datarabbit.ManagementBrokerPolicyReader
import io.cucumber.datatable.DataTable
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertIs

/**
 * What the policy scenarios share: whether a check scenario goes through the view model (it does as soon as a Given of the policy notes ran), the names the
 * broker "has" now and the stale copy the planner was opened with, and the form the table steps build up.
 */
internal object PolicyWorld {
    var active = false
    var fresh: KnownTopology? = null
    var refreshOnCheck: KnownTopology? = null
    var base: PolicyForm? = null
    var lastStream: StreamForm? = null

    fun reset() { active = false; fresh = null; refreshOnCheck = null; base = null; lastStream = null }

    /** The planner of this scenario is built with the REAL check and the REAL one-policy reader, over the honest broker of `CheckWorld`. */
    fun activate(d: PlannerDriver) {
        if (active) return
        active = true
        d.realDeclareCheck = CheckWorld.realCheck()
        d.policyReader = CheckWorld.realPolicyReader()
        d.restart()
    }

    /** "I check the plan against ...": the names are read again by the view model, the result is the one of the state. */
    fun check(d: PlannerDriver) {
        CheckWorld.snapshotBeforeCheck = CheckWorld.broker.snapshot()
        d.vm // the view model exists, so does its reader
        refreshOnCheck?.let { d.reader!!.answer = it }
        d.vm.check()
        // the real check reads over a client with its own dispatcher: wait on the condition, bounded, never a fixed sleep
        val deadline = System.nanoTime() + 20_000_000_000L
        while (d.state.check !is CheckState.Done && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
        val done = assertIs<CheckState.Done>(d.state.check, "the check did not finish: ${d.state.check}")
        CheckWorld.result = PlanCheckResult.Checked(done.results)
    }
}

/**
 * M9-08 steps for the `@m9-08` scenarios of `21_topology_planner_recipes.feature`: the Policy kind, the typed values, the match line, the notes in the plan and
 * in the check, the retention block of the stream form. The view model and the forms are the real ones over fakes ([PlannerDriver]); the check of the policy
 * notes runs the REAL `PlanCheck` and the REAL `ManagementBrokerPolicyReader` over the honest `FakeManagementBroker` (every request recorded in `CheckWorld.seen`).
 * What the screen draws (tags, error lines, the dropdowns) is pinned by the Compose tests `PolicyFormUiTest` and `StreamRetentionBlockUiTest`, not here.
 * Stand-ins until M9-12: "a plan opened from a file" puts the actions into the draft file and restarts the planner (`PendingStandIn`).
 */
class PlannerPolicySteps {
    private val d get() = PlannerWorld.driver

    @Before(value = "@m9-08", order = 20000)
    fun setUp() {
        PolicyWorld.reset()
        // a plan can only be saved with a folder, and some scenarios save
        if (d.migrationsFolder.isEmpty()) d.chooseMigrationsFolder("/work/migrations", "0004")
    }

    // ---- helpers ----

    private fun target(label: String): PolicyTarget =
        PolicyTarget.entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: error("no target \"$label\" among ${PolicyTarget.entries.map { it.label }}")

    private fun entryByLabel(label: String) = PolicyKeyCatalog.entries.firstOrNull { it.label == label } ?: error("no setting labelled \"$label\" in the catalog")

    /** What the user picks for a choice is its LABEL ("refuse new"), what is typed elsewhere is the value itself. */
    private fun valueFor(label: String, shown: String): String {
        val kind = entryByLabel(label).kind
        return if (kind is PolicyValueKind.Choice) kind.values.firstOrNull { it.label == shown }?.wire ?: shown else shown
    }

    private fun form(name: String, pattern: String, target: PolicyTarget = PolicyTarget.QUORUM_QUEUES, priority: String = "0", vararg settings: Pair<String, String>) =
        PolicyForm(name, pattern, target, priority, settings.map { PolicySetting(it.first, it.second) })

    /** The default setting of a plan's policy in these scenarios: offered for every target, honoured by quorum queues and streams alike. */
    private val limitBytes = "max-length-bytes" to "1000"

    private fun sampleValue(key: String): ArgumentValue = when (key) {
        "max-age" -> ArgumentValue.Text("7D")
        "overflow" -> ArgumentValue.Text("reject-publish")
        "dead-letter-exchange", "dead-letter-routing-key", "alternate-exchange" -> ArgumentValue.Text("x")
        "queue-leader-locator" -> ArgumentValue.Text("balanced")
        "dead-letter-strategy" -> ArgumentValue.Text("at-least-once")
        else -> ArgumentValue.Whole(10)
    }

    /** A plan the glue puts into the draft file, as a file would bring it: hand-written combinations the wizard does not build. */
    private fun planFromActions(vararg actions: MigrationAction) {
        d.store.putDraft("", actions.toList())
        d.restart()
        assertEquals(actions.size, d.plan.steps.size, "the plan was not opened: ${d.titles()}")
    }

    private fun policyStepTitled(title: String): Int {
        val i = d.plan.steps.indexOfFirst { it.action.description == title }
        assertTrue(i >= 0, "no step \"$title\" in ${d.titles()}")
        return i
    }

    private fun policySteps() = d.plan.steps.withIndex().filter { it.value.action is MigrationAction.DeclarePolicy }

    private fun notesOf(stepIndex: Int): List<String> = d.problems().filter { it.stepId == d.plan.steps[stepIndex].id }.map { it.message }

    // ---- building a policy through the form ----

    @When("I add a policy with the wizard")
    fun addPolicyBase(table: DataTable) {
        val row = table.asLists().associate { it[0] to it[1] }
        PolicyWorld.base = PolicyForm(
            name = row.getValue("name"), pattern = row.getValue("pattern"), target = target(row.getValue("applies to")), priority = row.getValue("priority"),
        )
    }

    @When("I give it the settings")
    fun giveSettings(table: DataTable) {
        val base = checkNotNull(PolicyWorld.base) { "no policy form was started" }
        val settings = table.asLists().map { (label, shown) -> PolicySetting(entryByLabel(label).key, valueFor(label, shown)) }
        d.addWithWizard(base.copy(settings = settings))
    }

    @Given("a plan with the policy {string} whose setting {string} is {int}")
    fun planWithNumberSetting(name: String, key: String, value: Int) {
        d.newPlan()
        d.addWithWizard(form(name, "^orders", PolicyTarget.QUORUM_QUEUES, "0", key to value.toString()))
        assertEquals(listOf("Declare policy $name"), d.titles(), "the form did not add the policy: ${d.formProblems()}")
    }

    @Given("a plan with the policy {string} that applies to streams and whose setting {string} is {string}")
    fun planWithTextSetting(name: String, key: String, value: String) {
        d.newPlan()
        d.addWithWizard(form(name, "^telemetry", PolicyTarget.STREAMS, "0", key to value))
        assertEquals(listOf("Declare policy $name"), d.titles(), "the form did not add the policy: ${d.formProblems()}")
    }

    // ---- what the form offers ----

    private fun openFormFor(label: String) {
        d.fill(PolicyForm(name = "", pattern = ""))
        d.vm.updateForm((d.form as PolicyForm).withTarget(target(label)))
        d.lastHints = (d.state.wizard as WizardState.Details).hints
    }

    @When("I open the policy form and choose that it applies to {string}")
    fun openAndChoose(label: String) = openFormFor(label)

    @When("I choose what the policy applies to: {string}")
    fun chooseTarget(label: String) = openFormFor(label)

    private fun offered(): List<dev.caerbannog.core.planner.PolicyKeyEntry> = PolicyKeyCatalog.offeredFor(assertIs<PolicyForm>(d.form).target)

    @Then("the settings offered include {string}")
    fun offeredInclude(label: String) = assertTrue(label in offered().map { it.label }, "offered: ${offered().map { it.label }}, expected \"$label\"")

    @Then("the settings offered do not include {string}")
    fun offeredExclude(label: String) = assertFalse(label in offered().map { it.label }, "offered: ${offered().map { it.label }} must not hold \"$label\"")

    @Then("the settings offered are exactly {string}")
    fun offeredExactly(list: String) = assertEquals(list.split(", "), offered().map { it.label }, "settings offered, in order")

    @Then("the offered settings do not include {string} or {string}")
    fun offeredKeysExclude(first: String, second: String) {
        val keys = offered().map { it.key }
        assertFalse(first in keys || second in keys, "offered keys: $keys")
    }

    // ---- problems ----

    @When("I fill the policy form with {}")
    fun fillPolicyForm(input: String) {
        val ok = form("orders-limits", "^orders", PolicyTarget.QUORUM_QUEUES, "0", "max-length" to "1000")
        val valued = Regex("^the value \"(.*)\" for \"(.*)\"$").matchEntire(input)
        val priority = Regex("^the priority \"(.*)\"$").matchEntire(input)
        val f = when {
            input == "an empty name" -> ok.copy(name = "")
            input == "an empty pattern" -> ok.copy(pattern = "")
            input == "no setting" -> ok.copy(settings = emptyList())
            valued != null -> ok.copy(settings = listOf(PolicySetting(entryByLabel(valued.groupValues[2]).key, valued.groupValues[1])))
            priority != null -> ok.copy(priority = priority.groupValues[1])
            else -> error("no input \"$input\" is known for the policy form")
        }
        d.fill(f)
    }

    // ---- the match line ----

    @Given("a plan that declares the quorum queues {string} and {string}")
    fun planWithTwoQueues(first: String, second: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(first, "quorum"))
        d.addWithWizard(d.queueForm(second, "quorum"))
    }

    @When("I enter the pattern {string} in the policy form")
    fun enterPattern(pattern: String) = d.fill(form("orders-limits", pattern, PolicyTarget.QUORUM_QUEUES, "0", "max-length" to "1000"))

    private fun patternHint() = d.lastHints.filter { it.field == "pattern" }.map { it.message }

    @Then("^the form says it matches (.*)$")
    fun formSaysItMatches(rest: String) = assertEquals(listOf("matches $rest"), patternHint(), "the match line")

    // ---- plan notes (from the plan alone) ----

    @Given("a plan that declares the quorum queue {string} and the policy {string} on {string}")
    fun planQueueAndPolicy(queue: String, policy: String, pattern: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(queue, "quorum"))
        d.addWithWizard(form(policy, pattern, PolicyTarget.ALL, "0", limitBytes))
    }

    @Given("a plan that declares the quorum queue {string} and the policy {string} on {string} that applies to {}")
    fun planQueueAndPolicyFor(queue: String, policy: String, pattern: String, label: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(queue, "quorum"))
        d.addWithWizard(form(policy, pattern, target(label), "0", limitBytes))
    }

    @Given("a plan that declares the policy {string} on {string} that applies to streams")
    fun planPolicyForStreams(name: String, pattern: String) {
        d.newPlan()
        d.addWithWizard(form(name, pattern, PolicyTarget.STREAMS, "0", limitBytes))
        assertEquals(listOf("Declare policy $name"), d.titles(), "the form did not add the policy: ${d.formProblems()}")
    }

    @When("I add the policy {string} on {string} with the wizard")
    fun addPolicy(name: String, pattern: String) = d.addWithWizard(form(name, pattern, PolicyTarget.ALL, "0", limitBytes))

    @When("I add the policy {string} on {string} that applies to {} with the wizard")
    fun addPolicyFor(name: String, pattern: String, label: String) = d.addWithWizard(form(name, pattern, target(label), "0", limitBytes))

    @Then("both policy steps show the note {string}")
    fun bothShow(note: String) {
        val policies = policySteps()
        assertEquals(2, policies.size, "the plan: ${d.titles()}")
        policies.forEach { assertEquals(listOf(note), notesOf(it.index).filter { n -> "olic" in n }, "notes of \"${it.value.action.description}\"") }
    }

    @Then("no step shows a note about two policies")
    fun noOverlapNote() = d.plan.steps.indices.forEach { assertEquals(emptyList<String>(), notesOf(it).filter { n -> "both match" in n }, "notes of step ${it + 1}") }

    @Given("a plan that declares the quorum queue {string} and the policy {string} on {string} with the settings {string} and {string}")
    fun planWithIgnoredKey(queue: String, policy: String, pattern: String, first: String, second: String) =
        planFromActions(
            MigrationAction.DeclareQueue(queue, "quorum", vhost = d.vhost),
            MigrationAction.DeclarePolicy(policy, pattern, linkedMapOf(first to sampleValue(first), second to sampleValue(second)), vhost = d.vhost),
        )

    @Then("the policy step shows the note {string}")
    fun policyStepShows(note: String) {
        val step = policySteps().single()
        assertEquals(listOf(note), notesOf(step.index).filter { "olic" in it }, "notes of the policy step")
    }

    // ---- plans with a policy, from a file's hand ----

    @Given("a plan that declares the policy {string} on {string} with {string} {string} and {string} {string} and {string} {string}")
    fun planWithThreeTexts(name: String, pattern: String, k1: String, v1: String, k2: String, v2: String, k3: String, v3: String) = planFromActions(
        MigrationAction.DeclarePolicy(name, pattern, linkedMapOf(k1 to ArgumentValue.Text(v1), k2 to ArgumentValue.Text(v2), k3 to ArgumentValue.Text(v3)), vhost = d.vhost),
    )

    @Given("a plan that declares the exchange {string} and then the policy {string} on {string} with {string} {string}")
    fun planExchangeThenPolicy(exchange: String, name: String, pattern: String, key: String, value: String) = planFromActions(
        MigrationAction.DeclareExchange(exchange, vhost = d.vhost),
        MigrationAction.DeclarePolicy(name, pattern, mapOf(key to ArgumentValue.Text(value)), vhost = d.vhost),
    )

    // ---- the check notes ----

    private fun brokerHas(queue: String, policy: String?, priority: Int = 0) {
        PolicyWorld.activate(d)
        CheckWorld.seedQueue(queue)
        if (policy != null) CheckWorld.broker.addPolicy("/", policy, "^$queue", mapOf("max-length" to JsonPrimitive(5)), priority = priority)
        val known = KnownTopology(queues = setOf(queue), queueDetails = mapOf(queue to KnownQueue("quorum", policy)))
        PolicyWorld.fresh = known
        d.known = known
    }

    @Given("the broker has the quorum queue {string} with the policy {string} applied with priority {int}")
    fun brokerQueueWithPolicy(queue: String, policy: String, priority: Int) = brokerHas(queue, policy, priority)

    @Given("the broker has the quorum queue {string} with no policy")
    fun brokerQueueNoPolicy(queue: String) = brokerHas(queue, null)

    @Given("the broker has the quorum queue {string} with a policy that cannot be read")
    fun brokerQueueUnreadablePolicy(queue: String) {
        brokerHas(queue, null)
        // the queue row names the policy; the item read of it is refused (403), as for a user without the right
        val known = KnownTopology(queues = setOf(queue), queueDetails = mapOf(queue to KnownQueue("quorum", "limits")))
        PolicyWorld.fresh = known
        d.known = known
        CheckWorld.broker.answer("GET", "/api/policies/%2F/limits", 403, """{"error":"not_authorised","reason":"refused"}""")
    }

    @Given("the planner was opened before the broker applied {string} to {string}")
    fun plannerOpenedBefore(policy: String, queue: String) {
        val fresh = checkNotNull(PolicyWorld.fresh) { "the broker has no queue yet" }
        assertEquals(policy, fresh.queueDetails.getValue(queue).policy)
        PolicyWorld.refreshOnCheck = fresh
        d.known = KnownTopology(queues = setOf(queue), queueDetails = mapOf(queue to KnownQueue("quorum", null))) // the copy the planner was opened with
    }

    @Given("a plan that declares the policy {string} on {string} with priority {int}")
    fun planWithPolicyPriority(name: String, pattern: String, priority: Int) {
        PolicyWorld.activate(d)
        d.newPlan()
        d.addWithWizard(form(name, pattern, PolicyTarget.ALL, priority.toString(), limitBytes))
        assertEquals(listOf("Declare policy $name"), d.titles(), "the form did not add the policy: ${d.formProblems()}")
    }

    @Given("a plan that declares the policy {string} on {string}")
    fun planWithPolicy(name: String, pattern: String) = planWithPolicyPriority(name, pattern, 0)

    private fun checkedStep(title: String) = CheckWorld.steps()[policyStepTitled(title)]

    @Then("it shows the note {string}")
    fun itShowsTheNote(note: String) {
        val notes = CheckWorld.steps().flatMap { it.notes }
        assertTrue(note in notes, "the notes of the check are $notes, expected \"$note\"")
    }

    @Then("^the step \"([^\"]*)\" shows no note about (another policy|at least once|a missing exchange)$")
    fun showsNoNoteAbout(title: String, topic: String) {
        when (topic) {
            "another policy" -> assertEquals(emptyList<String>(), checkedStep(title).notes.filter { "applies the policy" in it }, "check notes of \"$title\"")
            "at least once" -> assertEquals(emptyList<String>(), notesOf(policyStepTitled(title)).filter { "at least once" in it }, "plan notes of \"$title\"")
            else -> assertEquals(emptyList<String>(), notesOf(policyStepTitled(title)).filter { "does not exist" in it }, "plan notes of \"$title\"")
        }
    }

    @Then("the check still gives every step its result")
    fun everyStepHasResult() {
        assertEquals(d.plan.steps.size, CheckWorld.steps().size, "one result per step")
        assertEquals(d.plan.steps.map { it.id }, CheckWorld.steps().map { it.stepId })
    }

    @Then("the check only read from the broker")
    fun onlyRead() {
        assertTrue(CheckWorld.seen.isNotEmpty(), "the check sent nothing, so there is nothing to prove")
        assertEquals(emptyList<String>(), CheckWorld.seen.filter { it.method != "GET" }.map { "${it.method} ${it.path}" }, "a request that is not a read")
        assertTrue(CheckWorld.seen.none { "/api/policies/" !in it.path && "/api/queues/" !in it.path && "/api/exchanges/" !in it.path }, "paths: ${CheckWorld.seen.map { it.path }}")
        assertEquals(emptyList<Any>(), CheckWorld.broker.writes, "the broker saw a write")
        assertEquals(0, CheckWorld.broker.policyWrites)
        assertEquals(emptyList<MigrationAction>(), d.executor.executedActions, "the check used the executor")
    }

    // ---- the retention block of the stream form ----

    private fun streamForm(name: String, label: String?, value: String?, withResponse: Boolean = false, same: Boolean = true): StreamForm {
        val base = StreamForm(name = name, withResponse = withResponse, sameRetentionForResponse = same)
        return when (label) {
            null -> base
            "Keep messages for" -> base.copy(keepFor = value.orEmpty())
            "Keep at most (bytes)" -> base.copy(keepAtMost = value.orEmpty())
            else -> error("the stream form has no retention field \"$label\"")
        }
    }

    private fun addStream(form: StreamForm) { PolicyWorld.lastStream = form; d.addWithWizard(form) }

    @When("I add a stream {string} with the wizard and set {string} to {string}")
    fun addStreamWithRetention(name: String, label: String, value: String) = addStream(streamForm(name, label, value))

    @When("I add a stream {string} with the wizard and leave both retention fields empty")
    fun addStreamNoRetention(name: String) = addStream(streamForm(name, null, null))

    @When("I add a stream {string} with its response stream and set {string} to {string}")
    fun addPairWithRetention(name: String, label: String, value: String) = addStream(streamForm(name, label, value, withResponse = true))

    @When("I untick {string} before adding")
    fun untickBeforeAdding(tick: String) {
        assertEquals("Same retention for the response stream", tick)
        val earlier = checkNotNull(PolicyWorld.lastStream) { "no stream was added before" }
        kotlinx.coroutines.runBlocking { d.store.clear() } // the first attempt is gone: a new plan, not a second policy of the same name
        d.newPlan()
        d.fill(earlier)
        d.vm.updateForm(earlier.copy(sameRetentionForResponse = false))
        d.finishWizard()
    }

    private fun policyNamed(name: String): MigrationAction.DeclarePolicy =
        d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclarePolicy>().single { it.name == name }

    /** What a pattern matches among the names of the plan and some near misses: exactly the names the scenario says, nothing else. */
    private fun matched(pattern: String): List<String> {
        val candidates = d.plan.steps.mapNotNull { (it.action as? MigrationAction.DeclareStream)?.name } + listOf("telemetry-x", "xtelemetry", "telemetry-dlnx", "Telemetry", "telemetry-dl")
        return (PatternMatches.of(pattern, candidates) as PatternMatchResult.Matches).names
    }

    @Then("the policy applies to streams, matches exactly {string} and holds {string} {string}")
    fun policyHolds(name: String, key: String, value: String) {
        val policy = d.plan.steps.map { it.action }.filterIsInstance<MigrationAction.DeclarePolicy>().single()
        assertEquals("streams", policy.applyTo)
        assertEquals(listOf(name), matched(policy.pattern))
        assertEquals(ArgumentValue.Text(value), policy.definition[key])
    }

    @Then("the policy {string} matches {string} and {string} and nothing else")
    fun policyMatchesBoth(policy: String, first: String, second: String) = assertEquals(listOf(first, second), matched(policyNamed(policy).pattern))

    @Then("the policy {string} matches {string} only")
    fun policyMatchesOne(policy: String, only: String) = assertEquals(listOf(only), matched(policyNamed(policy).pattern))

    @Then("the policy {string} holds {string} as the number {int}")
    fun policyHoldsNumber(policy: String, key: String, value: Int) = assertEquals(ArgumentValue.Whole(value.toLong()), policyNamed(policy).definition[key])

    // ---- the saved file ----

    private fun savedRaw(): String {
        if (d.state.saved == null) {
            if (d.migrationsFolder.isEmpty()) d.chooseMigrationsFolder("/work/migrations", "0004")
            d.vm.saveAsMigration()
        }
        val version = d.state.saved?.version ?: error("the plan was not saved; error: ${d.state.errorMessage}")
        return d.migrationFolder.files.getValue(version).rawJson
    }

    private fun savedPolicies(): List<MigrationAction.DeclarePolicy> {
        savedRaw()
        return d.migrationFolder.files.getValue(d.state.saved!!.version).up.filterIsInstance<MigrationAction.DeclarePolicy>()
    }

    @Then("the saved file holds {string} as the number {int}, not as text")
    fun savedAsNumber(key: String, value: Int) {
        val raw = savedRaw()
        assertEquals(ArgumentValue.Whole(value.toLong()), savedPolicies().single().definition[key], "parsed: $key")
        assertTrue(Regex(""""$key"\s*:\s*$value(?![\d."])""").containsMatchIn(raw), "the file holds $key as a JSON number: $raw")
    }

    @Then("the saved file holds {string} as the text {string}, never as a number")
    fun savedAsText(key: String, value: String) {
        val raw = savedRaw()
        assertEquals(ArgumentValue.Text(value), savedPolicies().single().definition[key], "parsed: $key")
        assertTrue(Regex(""""$key"\s*:\s*"$value"""").containsMatchIn(raw), "the file holds $key as a JSON string: $raw")
    }

    // ---- operator policies, read-only steps ----

    @Then("there is no way to add an operator policy")
    fun noOperatorPolicy() {
        assertTrue(PlannerTexts.WIZARD_KINDS.none { it.contains("operator", ignoreCase = true) }, "kinds: ${PlannerTexts.WIZARD_KINDS}")
        PolicyTarget.entries.forEach { target ->
            val entry = PolicyKeyCatalog.offeredFor(target).first()
            val ctx = dev.caerbannog.core.planner.FormContext(d.plan, d.state.namingStyle)
            val actions = PolicyForm("p", "^x", target, "0", listOf(PolicySetting(entry.key, "1"))).actions(ctx)
            assertTrue(actions.none { it is MigrationAction.DeclareOperatorPolicy }, "$target built an operator policy")
        }
    }

    private var original: MigrationAction.DeclarePolicy? = null

    @Given("a plan opened from a file with the policy {string} that holds {string} {string}")
    fun planFromFile(name: String, key: String, value: String) {
        val action = MigrationAction.DeclarePolicy(name, "^x", mapOf(key to ArgumentValue.Text(value)), vhost = d.vhost)
        original = action
        PendingStandIn.put(d, "0005", listOf(action))
        PendingStandIn.open(d, "0005")
    }

    @Then("the step {string} is shown read-only")
    fun shownReadOnly(title: String) {
        val step = d.plan.steps[policyStepTitled(title)]
        assertFalse(step.isEditable, "the step opens in a form")
        d.vm.editStep(step.id)
        assertEquals(WizardState.Closed, d.state.wizard, "a read-only step is not opened")
    }

    @Then("the file keeps the policy as it was written")
    fun fileKeepsPolicy() = assertEquals(original, savedPolicies().single())

    companion object {
        private val hintTexts = setOf(
            "Only settings that every kind of queue honours are offered here", "The app cannot tell which names this pattern matches", "Matches no name yet",
        )

        fun isPolicyHint(message: String) = message in hintTexts && PlannerWorld.driver.form is PolicyForm

        /** `the form says "<a policy hint>"`: a hint on the pattern or the target, never a problem. */
        fun hintSaid(message: String) {
            val world = PlannerWorld.driver
            val hints = world.lastHints.filter { it.field == "pattern" || it.field == "target" }.map { it.message }
            assertTrue(message in hints, "the form's hints are $hints, expected \"$message\"")
            assertEquals(emptyList<String>(), world.formProblems().map { it.message }.filter { it == message }, "a hint is no problem")
        }
    }
}
