package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerEntry
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M9-02 steps for the `@m9-02` scenarios of `19_topology_planner.feature`: the naming style kept on the machine, the draft, "Save as migration",
 * Dismiss, and the unreadable draft. They drive [PlannerViewModel] through [PlannerDriver] and read the fakes the app would write: the draft file
 * ([PlannerDriver.store]) and the migrations folder. The check against the broker is `M9-05`: the step "I check the plan" does nothing until then.
 */
class PlannerDraftSteps {
    private val d get() = PlannerWorld.driver

    /** What a file or the draft held when a scenario noted it, to show it was not changed. */
    private val originalBytes = mutableMapOf<String, String>()

    private val declareQueueRow = Regex("^Declare (\\w+) queue (.+)$")
    private val bindRow = Regex("^Bind (.+) to (.+) with (.+)$")

    // ---- naming style on this machine ----

    @Given("I chose the naming style {string} in the planner")
    fun chose(label: String) = d.chooseStyle(style(label))

    @Given("no naming style was ever chosen on this machine")
    fun noStyleEver() = assertEquals(NamingStyle.KEBAB, d.settings.plannerNamingStyle, "a fresh machine has the default")

    @When("the app is restarted and I start a new plan")
    fun restartAndNewPlan() { d.restart(); d.newPlan() }

    @Then("the naming style is {string}")
    fun styleIs(label: String) = assertEquals(label, d.state.namingStyle.label)

    // ---- plans ----

    @Given("a plan described as {string} with the steps")
    fun describedWithSteps(description: String, table: DataTable) {
        d.newPlan()
        d.vm.setDescription(description)
        table.asLists().forEach { row -> d.addWithWizard(formOfRow(row.single())) }
    }

    @Given("a plan described as {string} that declares the quorum queue {string}")
    fun describedWithQueue(description: String, name: String) {
        d.newPlan()
        d.vm.setDescription(description)
        d.addWithWizard(d.queueForm(name, "quorum"))
    }

    @Given("a plan with {int} steps")
    fun planWithSteps(count: Int) {
        d.newPlan()
        val forms = listOf(
            d.queueForm("orders", "quorum"), d.exchangeForm("events", "direct"), d.bindingForm("events", "orders", "order.created"),
            d.queueForm("audit", "quorum"), d.queueForm("billing", "quorum"),
        )
        forms.take(count).forEach { d.addWithWizard(it) }
        assertEquals(count, d.plan.steps.size, "this step builds up to ${forms.size} steps")
    }

    @Given("a plan with a queue, an exchange and a binding")
    fun queueExchangeBinding() = planWithSteps(3)

    /** Every kind the wizard has today (queue, exchange, binding); later tasks add the others to the same list. */
    @Given("a plan built with every kind of the wizard")
    fun everyKind() = planWithSteps(3)

    @Given("a plan that declares the quorum queue {string} was saved as {string}")
    fun savedAs(name: String, version: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(name, "quorum"))
        d.vm.saveAsMigration()
        assertEquals(version, d.state.saved?.version, "the plan was saved as ${d.state.saved}")
    }

    @Given("a file {string} appeared in the migrations folder after the planner was opened")
    fun fileAppeared(fileName: String) {
        val version = fileName.removeSuffix(".json")
        d.migrationFolder.addMigration(version)
        originalBytes[fileName] = d.migrationFolder.files.getValue(version).rawJson
    }

    @Given("the migrations folder refuses the write")
    fun folderRefuses() = d.migrationFolder.failWrite(1)

    @Given("no migrations folder has been chosen on this machine")
    fun noFolder() { d.folders.store.folder = null }

    @Given("the chosen folder can be read but not written")
    fun readOnlyFolder() { d.migrationFolder.readOnly = true }

    @When("I choose the folder {string} from the planner")
    fun chooseFolder(path: String) {
        d.folders.chooser.answerNext(path)
        d.vm.chooseFolder()
    }

    @When("I can still add a step")
    fun canStillAdd() {
        d.vm.openWizard()
        assertEquals("Kind", d.state.wizard::class.simpleName, "the wizard did not open: ${d.state.wizard}")
        d.vm.cancelWizard()
    }

    // ---- the draft is a draft ----

    @Then("the planner says the plan is a draft that is not saved as a migration")
    fun saysDraft() = assertEquals("Draft, not saved as a migration", d.state.draftLabel)

    @Then("the migrations folder holds no new file")
    fun noNewFile() {
        assertEquals(setOf(d.latestMigration), d.migrationFolder.files.keys, "files in the migrations folder")
        assertEquals(emptyList<String>(), d.migrationFolder.attempts, "writes the folder was asked to do")
    }

    @Then("the migrations list shows no new migration")
    fun listShowsNoNew() {
        val screen = d.migrationsScreen()
        assertEquals(listOf(d.latestMigration), screen.state.value.entries.map { it.file.version })
    }

    @Then("the draft is kept in the app's own data, not in {string}")
    fun draftIsOwnData(folder: String) {
        assertTrue(d.store.hasDraft, "there is no draft")
        assertFalse(d.store.path.startsWith(folder), "the draft file ${d.store.path} is inside $folder")
        assertEquals(emptyList<String>(), d.migrationFolder.attempts)
        assertEquals(setOf(d.latestMigration), d.migrationFolder.files.keys)
    }

    // ---- save as migration ----

    @Then("the file {string} is written to the migrations folder")
    fun fileWritten(fileName: String) {
        assertEquals(fileName, d.state.saved?.fileName, "saved: ${d.state.saved}, error: ${d.state.errorMessage}")
        assertNotNull(d.migrationFolder.files[fileName.removeSuffix(".json")], "the folder holds ${d.migrationFolder.files.keys}")
    }

    @Then("it is a valid migration {string} described as {string} with the actions {string} and {string}")
    fun validMigration(version: String, description: String, first: String, second: String) {
        val raw = d.migrationFolder.files.getValue(version).rawJson
        val migration = (MigrationParser.parse(raw) as? MigrationValidationResult.Valid)?.migration ?: error("0005 is not a valid migration: $raw")
        assertEquals(version, migration.version)
        assertEquals(description, migration.description)
        assertEquals(listOf(first, second), migration.up.map { it.actionName })
    }

    @Then("{string} appears in the migrations list as pending and is selected")
    fun appearsPending(version: String) {
        val screen = d.migrationsScreen()
        screen.selectVersion(version) // what the Migrations screen does with the SavedPlan (M9-03 wires it)
        val state = screen.state.value
        assertEquals(version, d.state.saved?.version)
        assertTrue(state.entries.any { it.file.version == version && it.status is MigrationStatus.Pending }, "entries: ${state.entries.map { it.file.version to it.status }}")
        assertEquals(version, state.selectedVersion)
    }

    @Then("I am told {string}")
    fun toldThat(text: String) = assertEquals(text, d.state.saved?.message ?: d.state.errorMessage, "saved=${d.state.saved} error=${d.state.errorMessage}")

    @Then("there is no draft any more")
    fun noDraft() = assertFalse(d.store.hasDraft, "the draft file still holds ${d.store.rawText}")

    @Then("the saved file declares the queue {string} exactly as typed")
    fun savedQueueAsTyped(name: String) {
        val up = d.migrationFolder.files.getValue(d.state.saved!!.version).up
        assertEquals(listOf(name), up.map { (it as MigrationAction.DeclareQueue).name })
    }

    @Then("the plan is saved as {string}")
    fun savedAsVersion(version: String) = assertEquals(version, d.state.saved?.version, "error: ${d.state.errorMessage}")

    @Then("{string} still has exactly its original bytes")
    fun originalBytesKept(fileName: String) {
        val original = originalBytes[fileName] ?: error("no original bytes were noted for $fileName")
        assertEquals(original, d.migrationFolder.files.getValue(fileName.removeSuffix(".json")).rawJson)
    }

    @When("I apply {string} on the Migrations screen")
    fun apply(version: String) {
        val screen = d.migrationsScreen()
        screen.selectVersion(version)
        val result = runBlocking { screen.applySingle(version) }
        assertTrue(result is dev.caerbannog.core.migrations.MigrationApplyResult.Success, "apply answered $result")
    }

    @Then("the broker is asked to create the quorum queue {string}")
    fun brokerAskedQueue(name: String) =
        assertEquals(listOf("Declare quorum queue $name"), d.executor.executedActions.map { it.description })

    @Then("{string} is recorded as applied for {string} on this machine")
    fun recorded(version: String, profile: String) =
        assertTrue(version in runBlocking { d.folders.history.getAppliedMigrations(profile) }.keys, "applied: ${runBlocking { d.folders.history.getAppliedMigrations(profile) }}")

    // ---- dismiss ----

    @Then("I am asked {string}")
    fun askedThat(text: String) = assertEquals(text, d.state.question?.text, "the question")

    @Then("the plan still has {int} steps")
    fun planStillHas(count: Int) = assertEquals(count, d.plan.steps.size)

    @When("I choose {string} and confirm")
    fun chooseAndConfirm(button: String) {
        assertEquals("Dismiss", button)
        d.vm.dismiss()
        d.vm.confirm()
    }

    @Then("there is no draft any more and no question was asked")
    fun noDraftNoQuestion() {
        noDraft()
        assertEquals(emptyList<Any>(), d.questions, "questions asked")
    }

    // ---- restart, one draft ----

    @When("the app is restarted")
    fun restarted() = d.restart()

    @When("I open the Migrations screen")
    fun openMigrations() { entryOfScreen = d.entry() }

    private var entryOfScreen: PlannerEntry? = null

    @Then("the Migrations screen offers {string} for a draft with {int} step")
    fun offers(label: String, steps: Int) {
        val entry = d.entry()
        assertEquals(label, entry.label)
        assertEquals(PlannerEntry.ContinuePlan(steps), entry)
    }

    @Then("the button reads {string}, not {string}")
    fun buttonReads(label: String, notLabel: String) {
        assertEquals(label, entryOfScreen?.label)
        assertTrue(entryOfScreen?.label != notLabel)
    }

    @Then("the plan is described as {string} and lists {string}")
    fun describedAndLists(description: String, title: String) {
        assertEquals(description, d.state.description)
        assertTrue(title in d.titles(), "steps: ${d.titles()}")
    }

    // ---- an unreadable draft ----

    @Given("the draft file on disk is not a valid plan")
    fun draftNotValid() {
        d.store.putUnreadable("{ \"version\": \"draft\", \"up\": [ { \"action\": \"declareNothing\" ")
        originalBytes["draft"] = d.store.rawText!!
        d.restart()
    }

    @When("I open the planner")
    fun openPlanner() { d.restart(); d.vm }

    @Then("I see the path of the draft file and that it could not be read")
    fun seePath() {
        val unreadable = assertNotNull(d.state.draftUnreadable, "the state: ${d.state}")
        assertEquals(d.store.path, unreadable.path)
        assertTrue(unreadable.reason.isNotBlank())
        val banner = d.state.banner as? PlannerBanner.DraftUnreadable ?: error("the banner is ${d.state.banner}")
        assertEquals("The saved draft cannot be read", banner.title)
        assertEquals(d.store.path, banner.path)
    }

    @Then("the draft file still has exactly its original bytes")
    fun draftBytes() = assertEquals(originalBytes["draft"], d.store.rawText)

    @Then("I am asked to move the draft file to a name ending in {string} and a timestamp, and told the old file is kept")
    fun askedToMove(infix: String) {
        val text = d.state.question?.text ?: error("no question was asked; the state is ${d.state}")
        val pattern = Regex("^Move planner-draft\\.json to planner-draft\\.json" + Regex.escape(infix) + "\\d{8}-\\d{6} and start a new plan\\? The old file is kept\\.$")
        assertTrue(pattern.matches(text), "the question is \"$text\"")
    }

    @When("I confirm")
    fun confirm() = d.vm.confirm()

    @Then("the old file exists under the new name with its original bytes and the plan is empty")
    fun movedAside() {
        val (name, bytes) = d.store.asideFiles.entries.single()
        assertTrue(Regex("^planner-draft\\.json\\.unreadable-\\d{8}-\\d{6}$").matches(name), "new name $name")
        assertEquals(originalBytes["draft"], bytes)
        assertEquals(emptyList<String>(), d.titles())
    }

    // ---- safety and security ----

    /** `M9-05` adds the check; until then there is nothing to press, and the planner has no way to reach a broker at all (PlannerArchitectureGuardTest). */
    @When("I check the plan, save it as a migration and dismiss a second plan")
    fun checkSaveDismiss() {
        d.vm.saveAsMigration()
        assertNotNull(d.state.saved, "the first plan was not saved: ${d.state.errorMessage}")
        d.addWithWizard(d.queueForm("second", "quorum"))
        d.vm.dismiss()
        d.vm.confirm()
    }

    @Then("no request that creates, changes or deletes anything was sent to the broker")
    fun noWrites() = assertEquals(emptyList<MigrationAction>(), d.executor.executedActions)

    @Then("the only requests sent were reads")
    fun onlyReads() = assertEquals(emptyList<MigrationAction>(), d.executor.executedActions, "the planner has no door but the executor, which recorded these")

    @Then("the saved file holds no user, no password and no password hash")
    fun noSecretsInFile() {
        val raw = d.migrationFolder.files.getValue(d.state.saved!!.version).rawJson
        assertNoSecretKeys(raw, "the saved file")
        assertEquals(emptyList<String>(), d.migrationFolder.files.getValue(d.state.saved!!.version).up.filter { it.isBrokerLevel }.map { it.description })
    }

    @Then("the draft file never held one")
    fun draftNeverHeld() = d.store.savedTexts.forEach { assertNoSecretKeys(it, "a draft") }

    private fun assertNoSecretKeys(json: String, what: String) {
        val forbidden = setOf("user", "users", "password", "passwordhash", "password_hash", "hash", "hashingalgorithm", "tags")
        fun keys(e: JsonElement): List<String> = when (e) {
            is JsonObject -> e.keys.toList() + e.values.flatMap { keys(it) }
            is JsonArray -> e.flatMap { keys(it) }
            else -> emptyList()
        }
        val found = keys(Json.parseToJsonElement(json)).filter { it.lowercase() in forbidden }
        assertEquals(emptyList<String>(), found, "$what holds secret fields")
        assertFalse(json.contains("password", ignoreCase = true), "$what mentions a password")
    }

    // ---- helpers ----

    private fun style(label: String) = NamingStyle.entries.firstOrNull { it.label == label } ?: error("unknown naming style \"$label\"")

    private fun formOfRow(row: String): dev.caerbannog.core.planner.StepForm {
        declareQueueRow.matchEntire(row)?.let { return d.queueForm(it.groupValues[2], it.groupValues[1]) }
        bindRow.matchEntire(row)?.let { return d.bindingForm(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        error("the table row \"$row\" is not a step kind this glue knows")
    }

    private fun <T : Any> assertNotNull(value: T?, message: String): T = value ?: error(message)
}
