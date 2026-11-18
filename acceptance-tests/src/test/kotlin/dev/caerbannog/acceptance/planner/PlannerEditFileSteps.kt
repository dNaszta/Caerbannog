package dev.caerbannog.acceptance.planner

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import dev.caerbannog.acceptance.migrations.ScreenDriver
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.planner.PlanOpening
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.ui.migrations.MigrationsScreen
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9-12: a pending migration file opened as steps. The REAL Migrations screen (`MigrationsScreen` over the real `MigrationsViewModel`, the fake migrations
 * folder `FakeMigrationFolder` and the fake history of every saved profile) and the REAL planner screen over the real `PlannerViewModel` in file mode.
 * Every Given that used to put actions into the draft file (the stand-in `PendingStandIn` of M9-07 and M9-10, now deleted) writes a real pending file into
 * the fake folder with [PlannerFiles.put]. One binding of `I choose {string} for {string}` and one of the one-argument forms, here.
 *
 * What the window does with the screen's events is done in [PlannerFiles] the way the composition root will: "Edit steps" restarts the planner in
 * `PlannerMode.EditFile(version, checksum of the listed file)`, "Copy into a new plan" saves `PlanOpening.planOf(file)` as the draft and restarts the planner
 * as a new plan. A hash file never reaches either: the screen draws no button for it, so no view model is created for it ([PlannerFiles.openedPlanners]).
 */
internal class EntryHolder {
    var entry: PlannerEntry by mutableStateOf(PlannerEntry.NewPlan)
}

@OptIn(ExperimentalTestApi::class)
internal class PlannerMigrationsScreen(vm: MigrationsViewModel, holder: EntryHolder, onEdit: (String) -> Unit, onCopy: (String) -> Unit) : ScreenDriver({
    val state by vm.state.collectAsState()
    MigrationsScreen(
        state = state,
        onSelectVersion = vm::selectVersion,
        onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
        onUpdateFile = { _, _ -> },
        onValidateAgain = vm::loadMigrations,
        onDismissError = vm::dismissError,
        plannerEntry = holder.entry,
        onEditSteps = onEdit,
        onCopyIntoNewPlan = onCopy,
    )
}) {
    fun clickTag(tag: String) = click(tag)
}

internal object PlannerFiles {
    private val d get() = PlannerWorld.driver

    /** The text of every file a Given put in the folder, by file name, as it was: "still has exactly its original bytes" compares with it. */
    val originals = mutableMapOf<String, String>()

    /** True once a Given of this task put a file: the steps that serve two scenario families (the M9-10b hand-built screen, this real one) look at it. */
    var active = false
        private set

    /** How many planners were opened on a file (or on a copy); a hash file must leave it at 0. */
    var openedPlanners = 0
        private set

    const val SENTINEL_HASH = "SENTINEL-HASH-12-3f9a"

    private var holder: EntryHolder? = null
    private var viewModel: MigrationsViewModel? = null
    private var screen: PlannerMigrationsScreen? = null

    fun endScenario() {
        screen?.dispose()
        screen = null
        viewModel = null
        holder = null
        originals.clear()
        active = false
        openedPlanners = 0
    }

    // ---- the folder ----

    private fun ensureFolder() {
        if (d.migrationsFolder.isEmpty()) d.chooseMigrationsFolder("/work/migrations", "0004")
    }

    /** Writes a pending file `<version>.json` exactly as the app would, into the fake folder; nothing is applied. */
    fun put(version: String, actions: List<MigrationAction>, description: String = "Orders") = putRaw(MigrationParser.exportToJson(version, description, actions))

    /** A file as someone wrote it by hand (key order, extra keys); it must be a valid migration. */
    fun putRaw(raw: String) {
        ensureFolder()
        active = true
        d.migrationFolder.addRaw(raw)
        val version = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration.version
        originals["$version.json"] = raw
        viewModel?.loadMigrations()
    }

    /** The two steps of the file most scenarios open: a quorum queue and a binding to it. */
    fun defaultActions(): List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("orders", "quorum", vhost = d.vhost),
        MigrationAction.DeclareBinding("events", "orders", "order.created", vhost = d.vhost),
    )

    fun fileOf(version: String) = d.migrationFolder.files.getValue(version)

    /** The actions of the file as it was put (before anything was saved), through the parser. */
    fun originalActions(version: String): List<MigrationAction> =
        (MigrationParser.parse(originals.getValue("$version.json")) as MigrationValidationResult.Valid).migration.up

    /** The actions of the file the planner is editing, as they were when it was opened. */
    fun openedActions(): List<MigrationAction> = originalActions((d.state.mode as PlannerMode.EditFile).version)

    // ---- the Migrations screen ----

    private fun host(): PlannerMigrationsScreen {
        screen?.let { s -> holder!!.entry = d.entry(); viewModel!!.loadMigrations(); return s }
        val vm = d.migrationsScreen()
        val h = EntryHolder().also { it.entry = d.entry() }
        holder = h
        viewModel = vm
        return PlannerMigrationsScreen(vm, h, onEdit = ::openInPlanner, onCopy = ::copyInto).also { screen = it }
    }

    val migrations: PlannerMigrationsScreen get() = host()

    fun select(version: String) {
        active = true
        val s = host()
        s.click("migrationCard:$version")
        assertEquals(version, viewModel!!.state.value.selectedVersion, "the Migrations screen did not select $version")
    }

    /** What the window does for "Edit steps": the planner is built in file mode for the file as listed now. */
    private fun openInPlanner(version: String) {
        d.mode = PlannerMode.EditFile(version, fileOf(version).checksum)
        d.restart()
        d.vm
        openedPlanners++
    }

    /** What the window does for "Copy into a new plan": the steps become the draft of a new plan, and the planner opens on it. */
    private fun copyInto(version: String) {
        val plan = PlanOpening.planOf(fileOf(version), d.vhost)
        assertTrue(runBlocking { d.store.save(plan) }, "the draft was not written")
        d.mode = PlannerMode.NewPlan
        d.restart()
        d.vm
        openedPlanners++
    }

    /** Bounded wait for the planner screen of the view model the window just built. */
    fun awaitPlanner() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!d.state.isLoaded && System.nanoTime() < deadline) LockSupport.parkNanos(5_000_000)
        assertTrue(d.state.isLoaded, "the planner did not load")
        val planner = PlannerScreens.forViewModel(d.vm)
        while (!planner.isDrawn("planner:title") && System.nanoTime() < deadline) LockSupport.parkNanos(5_000_000)
        assertTrue(planner.isDrawn("planner:title"), "the planner screen is not drawn")
    }

    fun choose(button: String, version: String) {
        select(version)
        when (button) {
            "Edit steps" -> { migrations.click("migrations:editStepsBtn"); awaitPlanner() }
            "Edit file" -> { migrations.click("migrations:editFileBtn") }
            "Copy into a new plan" -> chooseCopy()
            else -> error("the Migrations screen has no button \"$button\" for a file")
        }
    }

    /** "Copy into a new plan" on the file that is selected; a question about a draft with steps is answered by Confirm (Dismiss the current plan and copy). */
    fun chooseCopy() {
        val s = host()
        s.click("migrations:copyIntoPlanBtn")
        if (s.isDrawn("migrations:draftDismissDialog")) s.click("migrations:draftDismissConfirmBtn")
        awaitPlanner()
    }

    fun isOffered(tag: String): Boolean = migrations.isDrawn(tag)

    /** "Edit steps" opened: the planner of the file is the one the driver holds. */
    fun openInPlannerVia(version: String) = choose("Edit steps", version)
}

class PlannerEditFileSteps {
    private val d get() = PlannerWorld.driver

    // ---- Givens: real pending files in the fake folder ----

    @Given("a pending migration {string} that declares the quorum queue {string} and binds {string} to it with routing key {string}")
    fun pendingQueueAndBinding(version: String, queue: String, exchange: String, key: String) = PlannerFiles.put(
        version, listOf(MigrationAction.DeclareQueue(queue, "quorum", vhost = d.vhost), MigrationAction.DeclareBinding(exchange, queue, key, vhost = d.vhost)),
    )

    @Given("a pending migration {string} that declares the quorum queue {string}")
    fun pendingQueue(version: String, queue: String) = PlannerFiles.put(version, listOf(MigrationAction.DeclareQueue(queue, "quorum", vhost = d.vhost)))

    @Given("the applied history of this machine cannot be read")
    fun historyUnreadable() {
        d.folders.history.unreadable = true
    }

    // "the draft file on disk is not a valid plan" and "the draft file still has exactly its original bytes" are bound once, in PlannerDraftSteps.

    @Given("the pending migration {string} is open in the planner and I removed every step")
    fun pendingOpenEmpty(version: String) {
        pendingOpen(version)
        d.plan.steps.map { it.id }.forEach { d.vm.removeStep(it) }
        assertEquals(0, d.plan.steps.size)
    }

    @When("I open another screen from the menu")
    fun menu() = PlannerScreens.forViewModel(d.vm).click("navItem:Streams")

    @Then("{string} is disabled with the reason {string}")
    fun disabledWithReason(button: String, reason: String) {
        if (button.startsWith("Save ")) {
            assertEquals(dev.caerbannog.core.planner.SaveGate.Disabled(reason), d.state.saveGate, "the gate")
            val screen = PlannerScreens.forViewModel(d.vm)
            assertEquals(button, screen.textOf("planner:saveBtn")?.trim())
            assertFalse(screen.isEnabled("planner:saveBtn"), "$button is enabled on the screen")
            assertEquals(reason, screen.textOf("planner:saveReason")?.trim(), "the reason on the screen")
            assertEquals(emptyList(), d.migrationFolder.updates, "a file was written")
        } else {
            assertEquals("Copy into a new plan", button)
            val s = PlannerFiles.migrations
            assertTrue(s.isDrawn("migrations:copyIntoPlanBtn"), "the button is not drawn")
            assertFalse(s.isEnabled("migrations:copyIntoPlanBtn"), "the button is enabled")
            assertEquals(reason, s.textOf("migrations:copyIntoPlanBlocked")?.trim(), "the reason")
            s.click("migrations:copyIntoPlanBtn")
            assertFalse(s.isDrawn("migrations:draftDismissDialog"), "a question was asked")
            assertEquals(0, PlannerFiles.openedPlanners, "a planner was opened")
        }
    }

    @Given("a pending migration {string}")
    fun pendingDefault(version: String) = PlannerFiles.put(version, PlannerFiles.defaultActions())

    @Given("the pending migration {string} is open in the planner")
    fun pendingOpen(version: String) {
        PlannerFiles.put(version, PlannerFiles.defaultActions())
        PlannerFiles.openInPlannerVia(version)
    }

    @Given("the pending migration {string} is open in the planner and I removed a step")
    fun pendingOpenRemoved(version: String) {
        pendingOpen(version)
        d.vm.removeStep(d.plan.steps.first().id)
        assertEquals(1, d.plan.steps.size)
    }

    @Given("the pending migration {string} is open in the planner and I added a step")
    fun pendingOpenAdded(version: String) {
        pendingOpen(version)
        d.addWithWizard(d.queueForm("invoices", "quorum"))
        assertEquals(3, d.plan.steps.size, "the step was not added: ${d.formProblems()}")
    }

    @Given("a pending migration {string} that declares the quorum queue {string} with the argument {string} true, deletes the queue {string} and declares the operator policy {string}")
    fun pendingReadOnly(version: String, queue: String, argument: String, deleted: String, policy: String) = PlannerFiles.put(
        version,
        listOf(
            MigrationAction.DeclareQueue(queue, "quorum", vhost = d.vhost, arguments = mapOf(argument to ArgumentValue.Flag(true))),
            MigrationAction.DeleteQueue(deleted, vhost = d.vhost),
            MigrationAction.DeclareOperatorPolicy(policy, "^jobs$", mapOf("max-length" to ArgumentValue.Whole(100)), d.vhost, "queues", 0),
        ),
    )

    @Given("{string} is applied to {string} on this machine and pending for {string}")
    fun appliedElsewhere(version: String, other: String, current: String) {
        assertEquals(d.profile, current, "the scenario's own premise: the open connection is $current")
        PlannerFiles.put(version, listOf(MigrationAction.DeclareQueue("billing", "quorum", vhost = d.vhost)))
        d.otherProfiles = listOf(other)
        runBlocking { d.folders.history.recordApplied(other, version, PlannerFiles.fileOf(version).checksum) }
    }

    @Given("a pending migration {string} that declares the user {string} with a password hash")
    fun pendingUser(version: String, user: String) = PlannerFiles.put(
        version, listOf(MigrationAction.DeclareUser(user, SecretValue.of(PlannerFiles.SENTINEL_HASH), "rabbit_password_hashing_sha256")), "access",
    )

    @Given("a pending migration {string} that declares the virtual host {string} and sets permissions")
    fun pendingAccess(version: String, vhost: String) = PlannerFiles.put(
        version, listOf(MigrationAction.DeclareVhost(vhost), MigrationAction.SetPermissions(vhost, "svc", ".*", ".*", ".*")), "access",
    )

    @Given("a pending migration {string} whose queue action also holds the key {string}")
    fun pendingUnknownKey(version: String, key: String) = PlannerFiles.putRaw(
        """{ "version": "$version", "description": "Hand written", "up": [ { "action": "declareQueue", "name": "orders", "$key": "keep me" } ] }""",
    )

    @Given("a pending migration {string} that declares the policy {string} twice with different settings")
    fun pendingTwoPolicies(version: String, name: String) = PlannerFiles.put(
        version,
        listOf(
            MigrationAction.DeclarePolicy(name, "^orders$", mapOf("max-length" to ArgumentValue.Whole(1000)), d.vhost, "quorum_queues", 0),
            MigrationAction.DeclarePolicy(name, "^orders$", mapOf("max-length" to ArgumentValue.Whole(2000)), d.vhost, "quorum_queues", 0),
        ),
    )

    @Given("{string} was changed on disk by someone else")
    fun changedOnDisk(fileName: String) {
        val version = fileName.removeSuffix(".json")
        theirs = """{ "version": "$version", "description": "Theirs", "up": [ { "action": "declareQueue", "name": "theirs" } ] }"""
        d.migrationFolder.changeOnDisk(version, theirs)
    }

    private var theirs = ""

    // ---- the one binding of the sentence that chooses a button for a file (the stand-in's `chooseFor` is deleted) ----

    @When("I choose {string} for {string}")
    fun chooseFor(button: String, version: String) = PlannerFiles.choose(button, version)

    @When("I go back to the Migrations screen without a change")
    fun backWithoutChange() {
        assertFalse(d.state.hasChanges, "the premise: nothing was changed")
        goBack()
    }

    @When("I go back to the Migrations screen")
    fun back() = goBack()

    /** The back arrow of the real planner screen; the window leaves only when the state says so. */
    private fun goBack() = PlannerScreens.forViewModel(d.vm).click("planner:backBtn")

    @When("I add a quorum queue {string} with the wizard and choose {string}")
    fun addAndChoose(name: String, button: String) {
        d.addWithWizard(d.queueForm(name, "quorum"))
        chooseSave(button)
    }

    /** "Save 0005": the button of the real planner screen, with the label the state says. */
    fun chooseSave(button: String) {
        val screen = PlannerScreens.forViewModel(d.vm)
        assertEquals(button, screen.textOf("planner:saveBtn")?.trim(), "the Save button reads ${screen.textOf("planner:saveBtn")}")
        screen.click("planner:saveBtn")
    }

    @When("I save {string} and open the Migrations screen")
    fun saveAndOpen(version: String) {
        // Save is enabled only after a change: the smallest one is a word after the description, the steps stay as they are
        if (!d.state.hasChanges) d.vm.setDescription(d.state.description + " (edited)")
        chooseSave("Save $version")
        assertEquals(version, d.state.saved?.version, "the file was not saved: ${d.state.errorMessage}")
    }

    // ---- Thens ----

    @Then("the planner lists these steps in this order")
    fun plannerLists(table: DataTable) {
        val expected = table.asLists().map { it.single() }
        assertEquals(expected, d.titles(), "the planner's steps")
        val screen = PlannerScreens.forViewModel(d.vm)
        expected.forEachIndexed { row, title -> assertTrue(screen.textOf("planStep:$row").orEmpty().contains(title), "row $row says ${screen.textOf("planStep:$row")}, expected \"$title\"") }
    }

    @Then("the planner says it is editing {string}")
    fun editing(version: String) {
        assertEquals(version, (d.state.mode as? PlannerMode.EditFile)?.version, "the planner's mode is ${d.state.mode}")
        assertEquals("Editing $version.json", PlannerScreens.forViewModel(d.vm).textOf("planner:editPill")?.trim(), "the pill on the screen")
    }

    @Then("the file {string} holds the actions of the three steps")
    fun fileHoldsThree(fileName: String) {
        val version = fileName.removeSuffix(".json")
        val saved = PlannerFiles.fileOf(version).up
        val expected = PlannerFiles.originalActions(version) + MigrationAction.DeclareQueue("invoices", "quorum", vhost = d.vhost)
        assertEquals(3, saved.size, "the file holds ${saved.map { it.description }}")
        assertEquals(expected, saved, "the two old actions where they were, then the new queue")
    }

    @Then("no other file was written")
    fun noOtherFile() {
        val saved = d.state.saved?.version
        if (saved == null) { // nothing was saved (a discard, a refusal): the folder was not asked to write anything
            assertEquals(emptyList(), d.migrationFolder.written, "files the folder wrote")
            assertEquals(emptyList(), d.migrationFolder.attempts, "the import or export path was used")
            assertEquals(emptyList(), d.migrationFolder.updates, "files the folder was asked to rewrite")
            return
        }
        val version = saved
        assertEquals(emptyList(), d.migrationFolder.written, "files the folder wrote")
        assertEquals(emptyList(), d.migrationFolder.attempts, "the import or export path was used")
        assertEquals(listOf(version), d.migrationFolder.updated, "files the folder rewrote")
        assertEquals(setOf(d.latestMigration, version), d.migrationFolder.files.keys, "files in the folder")
    }

    @Then("{string} is still pending for {string}")
    fun stillPending(version: String, profile: String) =
        assertFalse(version in runBlocking { d.folders.history.getAppliedMigrations(profile) }.keys, "$version was recorded as applied")

    @Then("no question was asked")
    fun noQuestion() = assertEquals(emptyList(), d.questions, "questions asked")

    @Then("choosing to keep editing leaves the planner open with my change")
    fun keepEditing() {
        PlannerScreens.forViewModel(d.vm).click("planner:questionCancelBtn")
        assertFalse(d.state.dismissed, "the planner closed")
        assertNotNull(d.state.mode as? PlannerMode.EditFile)
        assertEquals(1, d.plan.steps.size, "my change is gone")
        assertTrue(d.state.hasChanges)
    }

    @Then("the three steps are shown in plain words and marked read only")
    fun threeReadOnly() {
        assertEquals(listOf("Declare quorum queue jobs", "Delete queue legacy-orders", "Declare operator policy op-limit"), d.titles())
        assertEquals(listOf(false, false, false), d.plan.steps.map { it.isEditable }, "read-only steps")
    }

    @Then("I can move and remove them but not edit them")
    fun moveRemoveNotEdit() {
        val before = d.plan
        d.plan.steps.forEach { step ->
            d.vm.editStep(step.id)
            assertEquals(dev.caerbannog.core.planner.WizardState.Closed, d.state.wizard, "a read-only step opened a form")
        }
        d.moveDown(1)
        assertEquals(before.steps.map { it.action }.let { listOf(it[1], it[0], it[2]) }, d.plan.steps.map { it.action }, "the step did not move down")
        d.moveUp(2)
        assertEquals(before.steps.map { it.action }, d.plan.steps.map { it.action }, "the step did not move back up")
        d.vm.removeStep(d.plan.steps.last().id)
        assertEquals(2, d.plan.steps.size, "the step was not removed")
        // what is on the screen is the file as it was written: opening the planner again drops these changes, so the next step starts from the three steps
        d.restart()
        assertEquals(3, d.plan.steps.size, "the file's three steps are back")
        assertFalse(d.state.hasChanges)
    }

    @Then("the file still declares {string} with {string} as the boolean true, deletes {string} and declares the operator policy {string}")
    fun fileStillDeclares(queue: String, argument: String, deleted: String, policy: String) {
        val saved = PlannerFiles.fileOf("0006")
        val up = saved.up
        val declared = up.filterIsInstance<MigrationAction.DeclareQueue>().first { it.name == queue }
        assertEquals(ArgumentValue.Flag(true), declared.arguments[argument], "parsed: $argument")
        assertTrue(Regex(""""$argument"\s*:\s*true\b""").containsMatchIn(saved.rawJson), "the file holds $argument as a JSON boolean: ${saved.rawJson}")
        assertTrue(up.any { it == MigrationAction.DeleteQueue(deleted, d.vhost) }, "the delete of $deleted is gone: ${up.map { it.description }}")
        assertTrue(up.any { it is MigrationAction.DeclareOperatorPolicy && it.name == policy }, "the operator policy $policy is gone: ${up.map { it.description }}")
        assertEquals(PlannerFiles.originalActions("0006"), up.take(3), "the three old steps are as they were, in place")
    }

    // ---- the Migrations screen offers, or says why not ----

    private fun planNotOfferedText(): String? =
        listOf("migrations:hashFileNoEdit", "migrations:planNotOffered").firstNotNullOfOrNull { PlannerFiles.migrations.textOf(it)?.trim() }

    @Then("{string} is not offered and I am told {string}")
    fun notOfferedToldThat(button: String, text: String) = notOfferedWithText(button, text)

    @Then("{string} is not offered and the text reads {string}")
    fun notOfferedTextReads(button: String, text: String) = notOfferedWithText(button, text)

    private fun notOfferedWithText(button: String, text: String) {
        assertEquals("Edit steps", button)
        assertFalse(PlannerFiles.isOffered("migrations:editStepsBtn"), "\"Edit steps\" is offered")
        assertEquals(text, planNotOfferedText(), "the text in its place")
        assertEquals(0, PlannerFiles.openedPlanners, "a planner was opened for a file that is not offered")
    }

    @Then("{string} is still offered")
    fun stillOffered(button: String) {
        assertEquals("Edit file", button)
        assertTrue(PlannerFiles.isOffered("migrations:editFileBtn"), "\"Edit file\" is not offered")
    }

    @Then("no draft was written")
    fun noDraftWritten() {
        assertFalse(d.store.hasDraft, "a draft exists: ${d.store.rawText?.length} characters")
        assertEquals(emptyList(), d.store.savedTexts, "draft writes")
        assertTrue(d.store.rawText?.contains(PlannerFiles.SENTINEL_HASH) != true, "the draft holds the hash")
        assertTrue(d.store.savedTexts.none { it.contains(PlannerFiles.SENTINEL_HASH) }, "a draft write held the hash")
        assertEquals(0, PlannerFiles.openedPlanners, "a planner was created for a file with password hashes")
        assertTrue(d.mode is PlannerMode.NewPlan)
    }

    @Then("a new plan holds the steps of {string}")
    fun newPlanHolds(version: String) {
        assertTrue(d.state.mode is PlannerMode.NewPlan, "the planner is in ${d.state.mode}")
        assertEquals(PlannerFiles.originalActions(version).map { it.description }, d.titles(), "the new plan's steps")
        assertTrue(d.store.hasDraft, "the steps are not the draft")
    }

    @When("I choose {string} and am asked {string}")
    fun chooseCopyAsked(button: String, question: String) {
        assertEquals("Copy into a new plan", button)
        val s = PlannerFiles.migrations
        s.click("migrations:copyIntoPlanBtn")
        assertTrue(s.isDrawn("migrations:draftDismissDialog"), "no question was asked")
        assertTrue(s.textOf("migrations:draftDismissDialog").orEmpty().contains(question), "the question reads: ${s.textOf("migrations:draftDismissDialog")}")
        assertEquals(0, PlannerFiles.openedPlanners, "the plan was copied before the answer")
    }

    @When("I answer {string}")
    fun answer(label: String) {
        val s = PlannerFiles.migrations
        when (label) {
            "Keep the plan" -> s.click("migrations:draftDismissCancelBtn")
            "Dismiss and copy" -> { s.click("migrations:draftDismissConfirmBtn"); PlannerFiles.awaitPlanner() }
            else -> error("no answer \"$label\"")
        }
    }

    @Then("the planner is closed")
    fun plannerClosed() = assertTrue(d.state.dismissed, "the planner is still open")

    /** `"Copy into a new plan" is not offered` (called from the one `{string} is not offered` step). */
    fun notDrawn(button: String) {
        val tag = if (button == "Copy into a new plan") "migrations:copyIntoPlanBtn" else "migrations:editStepsBtn"
        assertFalse(PlannerFiles.isOffered(tag), "\"$button\" is offered")
    }

    @Then("{string} holds the other person's content")
    fun holdsTheirs(fileName: String) = assertEquals(theirs, PlannerFiles.fileOf(fileName.removeSuffix(".json")).rawJson)

    @Then("I am told {string} and that it was not overwritten")
    fun toldNotOverwritten(text: String) {
        assertEquals(text, d.state.errorMessage, "the planner's error")
        val shown = PlannerScreens.forViewModel(d.vm).textOf("planner:error").orEmpty()
        assertTrue(shown.contains(text) && shown.contains("not overwritten"), "the screen says: $shown")
        assertEquals(emptyList(), d.migrationFolder.updates, "the folder was asked to write")
    }

    @Then("{string} still offers the draft with {int} steps")
    fun stillOffersDraft(label: String, steps: Int) {
        val entry = d.entry()
        assertEquals(label, entry.label)
        assertEquals(steps, (entry as PlannerEntry.ContinuePlan).stepCount, "the draft")
    }

    /** `I can choose "Edit steps" and "Edit file"` and `I can choose "Copy into a new plan"` (called from the one `I can choose {}` step). */
    fun canChoose(buttons: List<String>) {
        buttons.forEach { b ->
            val tag = when (b) {
                "Edit steps" -> "migrations:editStepsBtn"
                "Edit file" -> "migrations:editFileBtn"
                "Copy into a new plan" -> "migrations:copyIntoPlanBtn"
                else -> error("no button \"$b\"")
            }
            assertTrue(PlannerFiles.isOffered(tag), "\"$b\" is not drawn")
            assertTrue(PlannerFiles.migrations.isEnabled(tag), "\"$b\" is drawn but disabled")
            assertEquals(b, PlannerFiles.migrations.textOf(tag)?.trim(), "the label of $tag")
        }
    }
}
