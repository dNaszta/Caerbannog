package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.DecisionPill
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.GenerationResult
import dev.caerbannog.core.migrate3x.GenerationRequest
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.ItemSheet
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.ReviewItem
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.RowView
import dev.caerbannog.core.migrate3x.SKIPPED_GROUP_KEY
import dev.caerbannog.core.migrate3x.SaveOutcome
import dev.caerbannog.core.migrate3x.SaveProblem
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrations.ActionsParseResult
import dev.caerbannog.core.migrations.FOLDER_READ_ONLY_MESSAGE
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolder
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * M8-06b step definitions for the `@m8-06b` scenarios of 16_migrate_from_3x.feature (review, generate and save), through
 * `MigrateFromThreeViewModel` on virtual time with a fake migrations folder; the Migrations screen scenario reads the same folder with the
 * real `MigrationsViewModel`. Givens set up the file and the folder, Whens call the events, Thens read the state. No logic here.
 */
class Migrate3xReviewSteps {
    private val w = Migrate3xScreenWorld

    private companion object State {
        var item: ReviewItem? = null
        var pendingBefore = 0
        var firstPending: ReviewItem? = null
        var typed = ""
        var expectedReason: String? = null
        var oldFiles: Map<String, String> = emptyMap()
        var attemptsBefore = 0
        val mixedGroup = "classic-not-replicated:warning"
    }

    @io.cucumber.java.Before
    fun resetReviewState() {
        item = null; pendingBefore = 0; firstPending = null; typed = ""; expectedReason = null; oldFiles = emptyMap(); attemptsBefore = 0
    }

    // ---- helpers ----

    private fun report(): MigrateFromThreeState.Report = run { if (w.state() !is MigrateFromThreeState.Report) ensureReport(); w.state() } as? MigrateFromThreeState.Report ?: fail("Expected the report but the screen is in ${w.state()}")
    private fun review(): ReviewState = report().review
    private fun sheet(): ItemSheet = review().sheet ?: fail("No sheet is open. Review state: ${review()}")
    private fun core() = assertNotNull(w.holder.held.value, "nothing is held").review
    private fun q(name: String) = ReviewItem(ItemKind.Queue, "/", name)
    private fun folder(): FakeMigrationFolder = w.folders.folder("/migrations")
    private fun vm() = run { ensureReport(); w.viewModel() }

    /** Shows the document an older Given left in [Migrate3xWorld] as the held analysis of the screen, when no report is shown yet. */
    private fun ensureReport() {
        if (w.state() is MigrateFromThreeState.Report) return
        val document = Migrate3xWorld.document ?: fail("no file and no document was set up by a Given step")
        w.holder.hold(dev.caerbannog.core.migrate3x.HeldAnalysis("legacy.json", document, dev.caerbannog.core.migrate3x.CompatibilityAnalyzer.analyze(document)))
        w.newViewModel(); w.settle()
        check(w.state() is MigrateFromThreeState.Report) { "the held analysis was not shown: ${w.state()}" }
    }

    private fun showFile(text: String) { w.setFile(text); w.open(); report() }

    private fun tickAll() { sheet().ticks.forEach { vm().setTick(it.acknowledgement, true) }; w.settle() }

    private fun acceptWithTicks(target: ReviewItem) {
        vm().openItem(target); w.settle(); tickAll(); vm().accept(); w.settle()
        item = target
    }

    private fun decideEverything() {
        for (target in core().pending.toList()) {
            vm().openItem(target); w.settle(); tickAll()
            if (sheet().canAccept) vm().accept() else vm().skip()
            w.settle()
        }
        assertEquals(0, review().pendingCount, "the premise: every item is decided")
    }

    private fun rowOf(target: ReviewItem, groupKey: String): RowView? {
        val view = report().view
        val group = view.groups.firstOrNull { it.key == groupKey } ?: return null
        if (!group.open) { vm().toggleGroup(groupKey); w.settle() }
        val rows = report().view.rows(groupKey)
        return (0 until rows.size).map { rows.at(it) }.firstOrNull { it.item == target }
    }

    private fun anyRowOf(target: ReviewItem): RowView? = report().view.groups.firstNotNullOfOrNull { rowOf(target, it.key) }

    private fun pillOf(target: ReviewItem): DecisionPill? = anyRowOf(target)?.decision

    // ---- Given ----

    @Given("the report of a file with {int} items that need a decision is shown")
    fun threeItems(count: Int) {
        showFile(DefinitionsFiles.file(queues = (1..count).map { DefinitionsFiles.queue("q$it") }))
        assertEquals(count, review().itemCount)
    }

    @Given("the report of a file with {int} items that need a decision and {int} of them are decided")
    fun threeItemsTwoDecided(count: Int, decided: Int) {
        threeItems(count)
        core().pending.take(decided).forEach { acceptWithTicks(it) }
        assertEquals(count - decided, review().pendingCount)
    }

    // "the report proposes to declare the classic queue {string} as a quorum queue", "a definitions file with the classic queue {string} with {string} {int}" and "... the non-durable queue {string}" are the M8-05 steps
    // (Migrate3xConversionSteps): they leave a document in Migrate3xWorld, and [ensureReport] puts it on the screen.

    @Given("the report of a file with blockers and warnings is shown and nothing is skipped")
    fun blockersAndWarnings() {
        showFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("temp", durable = false))))
        assertTrue(report().view.summary.blockers > 0 && report().view.summary.warnings > 0, "the premise: blockers and warnings, got ${report().view.summary}")
        assertTrue(report().view.groups.none { it.key == SKIPPED_GROUP_KEY })
    }

    @Given("I opened the replace sheet of {string}")
    fun openedReplace(name: String) {
        vm().openItem(q(name)); w.settle(); vm().startReplace(); w.settle()
        item = q(name)
        assertNotNull(sheet().replace)
    }

    @Given("every item of the report is decided")
    fun everyItemDecided() {
        showFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("prio", arguments = mapOf("x-max-priority" to JsonPrimitive(5))), DefinitionsFiles.queue("temp", durable = false))))
        decideEverything()
        assertEquals(1, review().skippedCount, "the premise: temp has no automatic fix by default and is skipped")
    }

    @Given("a migrations folder is chosen that holds the versions {int} and {int}")
    fun folderWithVersions(a: Int, b: Int) {
        listOf(a, b).forEach { folder().addMigration(it.toString().padStart(4, '0')) }
        assertEquals("/migrations", w.folders.store.folder)
    }

    @Given("every item of the report is decided and no migrations folder is chosen")
    fun decidedNoFolder() {
        w.folders = FakeMigrationFolders(null)   // the chooser answers "cancelled" until a scenario says otherwise
        everyItemDecided()
    }

    @Given("every item of the report is decided and the migrations folder is read-only")
    fun decidedReadOnly() {
        everyItemDecided()
        folder().readOnly = true
    }

    @Given("every item of the report is decided and the file holds two virtual hosts")
    fun decidedTwoVhosts() {
        showFile(
            DefinitionsFiles.file(
                queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("billing", vhost = "other")),
                vhosts = listOf("/", "other"),
            ),
        )
        decideEverything()
    }

    @Given("the second file cannot be saved")
    fun secondFileFails() = folder().failWrite(2, "the file could not be written")

    @Given("the migrations {string} and {string} were generated and saved from this report")
    fun generatedAndSaved(a: String, b: String) {
        folder().addMigration("0001"); folder().addMigration("0002")
        decidedTwoVhosts()
        w.viewModel().generate(); w.settle()
        val files = (review().generation as? GenerationState.Done)?.report?.files ?: fail("the premise: the first generation saved, got ${review().generation}")
        assertEquals(listOf(a, b), files.map { it.version })
        oldFiles = listOf(a, b).associateWith { folder().files.getValue(it).rawJson }
    }

    @Given("the migration {string} was generated and saved for the virtual host {string}")
    fun generatedForVhost(version: String, vhost: String) {
        folder().addMigration("0001"); folder().addMigration("0002")
        showFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
        decideEverything()
        vm().generate(); w.settle()
        val file = (review().generation as? GenerationState.Done)?.report?.files?.single() ?: fail("the premise: saved, got ${review().generation}")
        assertEquals(listOf(version, vhost), listOf(file.version, file.vhost))
    }

    @Given("I skipped the queue {string}, which has a binding, and the file holds a message-loss queue")
    fun skippedWithBinding(name: String) {
        showFile(
            DefinitionsFiles.file(
                queues = listOf(DefinitionsFiles.queue(name)),
                exchanges = listOf(DefinitionsFiles.exchange("events")),
                bindings = listOf(DefinitionsFiles.binding("events", name, "k")),
            ),
        )
        assertTrue(w.holder.held.value!!.report.findings.any { it.findingClass == dev.caerbannog.core.migrate3x.FindingClass.MessageLoss }, "the premise: a message-loss finding")
        vm().openItem(q(name)); w.settle(); vm().skip(); w.settle()
    }

    // ---- When ----

    @When("I accept the default proposal of the first item")
    fun acceptFirst() { pendingBefore = review().pendingCount; acceptWithTicks(core().items.first()) }

    @When("I open the review of {string}")
    fun openReview(name: String) { item = q(name); vm().openItem(q(name)); w.settle() }

    @When("I tick it and accept")
    fun tickAndAccept() { tickAll(); vm().accept(); w.settle() }

    @When("I choose the quorum alternative and tick {string}")
    fun chooseQuorum(tickText: String) {
        val finding = sheet().findings.first { f -> f.options.any { it.title.contains("quorum") && !it.isDefault } }
        vm().chooseAlternative(finding.findingId, finding.options.first { it.title.contains("quorum") }.index); w.settle()
        val tick = sheet().ticks.single { it.text == tickText }
        vm().setTick(tick.acknowledgement, true); w.settle()
    }

    @When("I accept")
    fun acceptNow() { vm().accept(); w.settle() }

    @When("I skip {string}")
    fun skipNamed(name: String) {
        pendingBefore = review().pendingCount; item = q(name)
        vm().openItem(q(name)); w.settle(); vm().skip(); w.settle()
    }

    @When("I skip one item")
    fun skipOne() { item = core().items.first(); vm().openItem(item!!); w.settle(); vm().skip(); w.settle() }

    @When("I un-skip {string}")
    fun unskip(name: String) { pendingBefore = review().pendingCount; vm().unskip(q(name)); w.settle() }

    @When("I skip {string} and open its review from the group {string} and accept it with the tick")
    fun skipThenAcceptFromGroup(name: String, group: String) {
        skipNamed(name)
        assertEquals(group, report().view.groups.last().title)
        vm().toggleGroup(SKIPPED_GROUP_KEY); w.settle()
        val row = rowOf(q(name), SKIPPED_GROUP_KEY) ?: fail("no row for $name in the skipped group")
        vm().openItem(row.item!!); w.settle(); tickAll(); vm().accept(); w.settle()
    }

    @When("I open the review of {string} and choose {string}")
    fun openAndReplace(name: String, choice: String) {
        assertEquals("Replace with my own actions", choice)
        openReview(name); vm().startReplace(); w.settle()
    }

    @When("I change the queue to have the argument {string} {int} and save")
    fun changeAndSave(argument: String, value: Int) {
        val parsed = (MigrationParser.parseActions(sheet().replace!!.text) as? ActionsParseResult.Parsed)?.actions ?: fail("the prefilled text does not parse")
        val queue = parsed.single() as MigrationAction.DeclareQueue
        val changed = queue.copy(arguments = queue.arguments + (argument to dev.caerbannog.core.json.ArgumentValue.Whole(value.toLong())))
        vm().submitReplace(MigrationParser.actionsToJson(listOf(changed))); w.settle()
    }

    @When("^I enter (.+)$")
    fun enter(what: String) {
        typed = when (what) {
            "an action that deletes a queue" -> """[{"action":"deleteQueue","name":"orders"}]"""
            "a queue named \"..\"" -> """[{"action":"declareQueue","name":".."}]"""
            "an action for another virtual host" -> """[{"action":"declareQueue","name":"orders","vhost":"other"}]"""
            "text that is not JSON" -> "this is { not json"
            else -> fail("no text is defined for \"$what\"")
        }
        expectedReason = (MigrationParser.parseActions(typed) as? ActionsParseResult.Invalid)?.reason
        vm().submitReplace(typed); w.settle()
    }

    @When("I generate the migration")
    fun generate() { attemptsBefore = folder().attempts.size; vm().generate(); w.settle() }

    @When("I change one decision and generate the migration again")
    fun changeAndGenerateAgain() {
        val orders = q("orders")
        vm().openItem(orders); w.settle()
        val finding = sheet().findings.first { it.options.size > 1 }
        vm().chooseAlternative(finding.findingId, 1); w.settle()
        vm().accept(); w.settle()
        assertEquals(0, review().pendingCount, "the premise: the changed item is decided again")
        attemptsBefore = folder().attempts.size
        vm().generate(); w.settle()
    }

    @When("I generate again and confirm")
    fun generateAgainConfirm() {
        vm().generate(); w.settle()
        assertEquals(GenerationState.ConfirmAgain, review().generation)
        vm().generateAgain(); w.settle()
    }

    @When("I turn off every severity chip")
    fun chipsOff() { Severity.entries.forEach { vm().toggleSeverity(it); w.settle() } }

    private var migrationsScreen: MigrationsViewModel? = null
    private var otherScreen: MigrationsViewModel? = null

    @When("I connect to {string}, a 4.x broker on the virtual host {string}, and open the Migrations screen")
    fun openMigrationsScreen(profile: String, vhost: String) {
        w.connectedTo = profile
        fun screen(host: String) = MigrationsViewModel(
            fileRepository = null,
            runner = MigrationRunner(w.folders.history, FakeTopologyMigrationExecutor()),
            profileName = profile,
            vhost = host,
            folderPorts = w.folders.ports,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        migrationsScreen = screen(vhost)
        otherScreen = screen("other-vhost")
    }

    // ---- Then: counts and sheets ----

    // A regular expression on purpose: the M5-08c1 step "the screen says {string}" (migrations glue) has the same words, and the conversion suite
    // loads both glue packages. The two never run in one scenario (this one is only reached by @m8-06b).
    @Then("^the screen says \"(.+)\"$")
    fun screenSays(line: String) = assertEquals(line, review().pendingLine)

    @Then("\"Generate migration\" is disabled")
    fun generateDisabled() = assertFalse(review().canGenerate, "Generate migration is enabled with ${review().pendingCount} undecided")

    @Then("{string} is disabled and the screen says {string}")
    fun disabledAndSays(button: String, line: String) {
        assertEquals("Generate migration", button)
        generateDisabled(); screenSays(line)
    }

    @Then("choosing {string} opens the review of that item")
    fun showNext(button: String) {
        assertEquals("Show next undecided item", button)
        val expected = core().pending.first()
        vm().showNextUndecided(); w.settle()
        assertEquals(expected, sheet().item)
    }

    @Then("the review shows the finding, why it matters, the default proposal and the alternatives")
    fun reviewShows() {
        val finding = sheet().findings.firstOrNull { it.options.size > 1 } ?: fail("no finding with alternatives: ${sheet().findings}")
        assertTrue(finding.message.isNotBlank() && finding.why.isNotBlank(), "message and why are shown: $finding")
        assertTrue(finding.options.first().isDefault && finding.options.drop(1).none { it.isDefault }, "the default proposal comes first: ${finding.options}")
    }

    @Then("the tick {string} is unticked and {string} is disabled")
    fun tickUnticked(tick: String, button: String) {
        assertEquals("Accept", button)
        val view = sheet().ticks.singleOrNull { it.text == tick } ?: fail("the tick \"$tick\" is not shown: ${sheet().ticks}")
        assertFalse(view.ticked)
        assertFalse(sheet().canAccept, "Accept is enabled without the tick")
    }

    @Then("the item shows as accepted with the tick")
    fun acceptedWithTick() = assertEquals(DecisionPill.Accepted(ticked = true), pillOf(item!!), "pill of $item")

    @Then("the default is to keep it classic and needs no tick")
    fun keepClassicDefault() {
        val finding = sheet().findings.first { it.options.size > 1 }
        assertTrue(finding.options.single { it.isDefault }.title.startsWith("Keep the queue classic"), "default: ${finding.options}")
        assertEquals(emptyList<Any>(), sheet().ticks)
        assertTrue(sheet().canAccept)
    }

    @Then("the item shows as accepted as a quorum queue")
    fun acceptedAsQuorum() {
        val decision = core().decisionOf(item!!) as? ItemDecision.Accept ?: fail("not accepted: ${core().decisionOf(item!!)}")
        assertEquals("quorum", (core().previewActions(item!!, decision).single() as MigrationAction.DeclareQueue).type)
        assertEquals(DecisionPill.Accepted(ticked = true), pillOf(item!!))
    }

    @Then("the review shows the manual steps and {string} is disabled until an automatic alternative is chosen")
    fun manualSteps(button: String) {
        assertEquals("Accept", button)
        assertTrue(sheet().manualSteps.isNotEmpty(), "no manual step is shown")
        assertFalse(sheet().canAccept)
        val finding = sheet().findings.first { it.options.size > 1 }
        vm().chooseAlternative(finding.findingId, 1); w.settle()
        assertTrue(sheet().canAccept, "the automatic alternative can be accepted")
        vm().chooseAlternative(finding.findingId, 0); w.settle()
        assertFalse(sheet().canAccept)
    }

    @Then("{string} and {string} are offered")
    fun skipAndReplaceOffered(skip: String, replace: String) {
        assertEquals(listOf("Skip", "Replace with my own actions"), listOf(skip, replace))
        assertNull(core().refusalOf(item!!, ItemDecision.Skip), "Skip is refused")
        vm().startReplace(); w.settle()
        assertNotNull(sheet().replace, "the Replace editor does not open")
        vm().backToProposal(); w.settle()
        assertNull(sheet().replace)
    }

    // ---- Then: skip ----

    @Then("{string} is listed in the group {string} and no longer in its rule group")
    fun listedInSkipped(name: String, title: String) {
        val group = report().view.groups.lastOrNull() ?: fail("no group")
        assertEquals(SKIPPED_GROUP_KEY, group.key); assertEquals(title, group.title)
        assertEquals(q(name), rowOf(q(name), SKIPPED_GROUP_KEY)?.item)
        report().view.groups.filter { it.key != SKIPPED_GROUP_KEY }.forEach { assertNull(rowOf(q(name), it.key), "$name is still in ${it.key}") }
    }

    @Then("the count of undecided items goes down")
    fun countDown() = assertEquals(pendingBefore - 1, review().pendingCount)

    @Then("the count of undecided items goes up")
    fun countUp() = assertEquals(pendingBefore + 1, review().pendingCount)

    @Then("{string} is undecided again, is back in its rule group and is no longer in the group {string}")
    fun undecidedAgain(name: String, title: String) {
        assertNull(core().decisionOf(q(name)))
        assertEquals(DecisionPill.Undecided, rowOf(q(name), mixedGroup)?.decision, "orders is not back in its rule group")
        assertTrue(report().view.groups.none { it.title == title }, "the group $title is still there")
    }

    @Then("the item shows as accepted and the group {string} is gone")
    fun acceptedAndGone(title: String) {
        assertEquals(DecisionPill.Accepted(ticked = true), pillOf(q("orders")))
        assertTrue(report().view.groups.none { it.title == title })
    }

    @Then("there is no group {string}")
    fun noGroup(title: String) = assertTrue(report().view.groups.none { it.title == title }, "the group $title exists")

    @Then("the group {string} is the last group, shows {int} item and is closed")
    fun lastGroup(title: String, count: Int) {
        val last = report().view.groups.last()
        assertEquals(listOf(title, count, false), listOf(last.title, last.count, last.open))
    }

    @Then("the group {string} is still shown")
    fun stillShown(title: String) {
        assertEquals(title, report().view.groups.lastOrNull()?.title)
        assertTrue(report().view.chips.none { it.selected }, "the premise: every chip is off")
    }

    // ---- Then: replace ----

    @Then("the sheet shows the actions the app would write, as editable migration actions")
    fun sheetShowsActions() {
        val editor = assertNotNull(sheet().replace)
        assertEquals(MigrationParser.actionsToJson(core().previewActions(item!!)), editor.text)
        assertIs<ActionsParseResult.Parsed>(MigrationParser.parseActions(editor.text))
    }

    @Then("the item shows as replaced and the generated migration carries {string} {int}")
    fun replacedAndCarries(argument: String, value: Int) {
        assertEquals(DecisionPill.Replaced(1), pillOf(item!!))
        val raw = (core().generate(GenerationRequest(emptySet(), "legacy.json")) as GenerationResult.Generated).migrations.single().file.rawJson
        assertTrue(Regex("\"${Regex.escape(argument)}\"\\s*:\\s*$value\\b").containsMatchIn(raw), "the file does not carry $argument $value: $raw")
    }

    @Then("the sheet shows the error {string} and keeps what I typed")
    fun errorKept(error: String) {
        val editor = assertNotNull(sheet().replace, "the replace sheet is gone")
        val shown = assertNotNull(editor.error, "no error is shown")
        assertEquals(typed, editor.text, "what the user typed is not kept")
        when (error) {
            "the migration parser's own reason" -> assertTrue(shown.contains(assertNotNull(expectedReason, "the premise: the parser refuses it")), shown)
            // The core names both virtual hosts when the echo rule allows it ("an action is for virtual host 'other', but the item belongs to '/'").
            "an action is for another virtual host than the item's" -> assertTrue(shown.contains("an action is for") && shown.contains("virtual host"), shown)
            else -> assertTrue(shown.contains(error), "expected \"$error\" but the sheet says \"$shown\"")
        }
    }

    @Then("the item is still undecided")
    fun stillUndecided() = assertNull(core().decisionOf(item!!), "the item was stored")

    // ---- Then: generate and save ----

    private fun done() = (review().generation as? GenerationState.Done)?.report ?: fail("expected a saved result but generation is ${review().generation}")
    private fun trouble() = review().generation as? GenerationState.Trouble ?: fail("expected a trouble but generation is ${review().generation}")

    @Then("the screen lists one file per source virtual host with its version {int}, description, action count and the skipped items and notes")
    fun listsFiles(version: Int) {
        val report = done()
        assertEquals(How.Saved, report.how)
        val file = report.files.single()
        assertEquals(version.toString().padStart(4, '0'), file.version)
        assertEquals("Migrated from RabbitMQ 3.x legacy.json vhost /", file.description)
        assertTrue(file.actionCount > 0)
        assertTrue(report.leftOut.any { it.label == "temp" && it.text == "skipped by you" }, "temp is not listed as skipped: ${report.leftOut}")
        assertTrue(report.notes.isNotEmpty(), "no note is listed")
    }

    @Then("the files are saved into the migrations folder")
    fun savedIntoFolder() {
        assertEquals(done().files.map { it.version }, folder().written)
        assertEquals(done().files.map { "/migrations/${it.version}.json" }, done().files.map { it.path })
    }

    @Then("nothing was sent to any broker")
    fun nothingSent() {
        assertTrue(review().generation is GenerationState.Done, "nothing was generated, so \"nothing was sent\" proves nothing: ${review().generation}")
        assertEquals(emptyList<String>(), w.brokerRequests)
    }

    @Then("the app asks for the migrations folder")
    fun asksForFolder() = assertEquals(listOf<String?>(null), w.folders.chooser.startedIn)

    @Then("when I cancel nothing is saved and the screen says no folder was chosen")
    fun noFolderChosen() {
        assertEquals(SaveProblem.NoFolderChosen, trouble().problem)
        assertEquals(emptyList<String>(), folder().attempts)
        assertNull(w.folders.store.folder)
    }

    @Then("the screen says the folder is read-only and no file was saved")
    fun readOnlyReported() {
        assertEquals(SaveProblem.Folder("/migrations", FOLDER_READ_ONLY_MESSAGE), trouble().problem)
        assertEquals(emptyList<String>(), folder().attempts, "a read-only folder was written to")
        assertEquals(emptyList<Any>(), trouble().saved)
    }

    @Then("I can choose another folder or export the files instead")
    fun chooseOrExport() {
        vm().chooseFolder(); w.settle()
        assertEquals(listOf<String?>("/migrations"), w.folders.chooser.startedIn, "the dialog starts in the current folder")
        assertIs<GenerationState.Trouble>(review().generation, "cancelling the dialog changes nothing")
        w.saver.outcome = SaveOutcome.Saved("/home/me/0001.json")
        vm().exportFiles(); w.settle()
        assertEquals(How.Exported, done().how)
        assertEquals(emptyList<String>(), folder().attempts, "export never touches the migrations folder")
    }

    @Then("the screen says which file was saved and which was not")
    fun whichSaved() {
        val t = trouble()
        assertIs<SaveProblem.FileRefused>(t.problem)
        assertEquals("0002", (t.problem as SaveProblem.FileRefused).version)
        assertEquals(listOf("0001"), t.saved.map { it.version })
        assertEquals(listOf("0002"), t.unsaved.map { it.version })
    }

    @Then("the saved file stays in the migrations folder")
    fun savedStays() = assertEquals(setOf("0001"), folder().files.keys)

    @Then("I can try again, choose another folder or export the file that was not saved")
    fun tryChooseExport() {
        w.saver.outcome = SaveOutcome.Cancelled
        vm().exportFiles(); w.settle()
        assertEquals(listOf("0002.json"), w.saver.saves.map { it.suggestedName }, "only the file that was not saved is offered")
        vm().chooseFolder(); w.settle()
        assertEquals(1, w.folders.chooser.startedIn.size)
        vm().retrySave(); w.settle()
        assertEquals(listOf("0001", "0002"), done().files.map { it.version })
        assertEquals(listOf("0001", "0002"), folder().written)
    }

    @Then("the screen first says that files were already saved and asks {string}")
    fun asksAgain(question: String) {
        assertEquals("Generate again?", question)
        assertEquals(GenerationState.ConfirmAgain, review().generation)
    }

    @Then("when I cancel nothing is written")
    fun cancelWritesNothing() {
        vm().cancelGenerateAgain(); w.settle()
        assertEquals(GenerationState.Idle, review().generation)
        assertEquals(attemptsBefore, folder().attempts.size)
    }

    @Then("new files with the versions {int} and {int} are saved and {string} and {string} are unchanged")
    fun newVersions(a: Int, b: Int, oldA: String, oldB: String) {
        assertEquals(listOf(a, b).map { it.toString().padStart(4, '0') }, done().files.map { it.version })
        assertEquals(oldFiles, listOf(oldA, oldB).associateWith { folder().files.getValue(it).rawJson })
    }

    @Then("{string} is listed as Pending")
    fun listedPending(label: String) {
        val entry = migrationsScreen!!.state.value.entries.firstOrNull { "${it.file.version} · ${it.file.description}" == label }
            ?: fail("\"$label\" is not listed: ${migrationsScreen!!.state.value.entries.map { it.file.version + " · " + it.file.description }}")
        assertEquals(MigrationStatus.Pending, entry.status)
    }

    @Then("a connection on another virtual host does not list it")
    fun otherVhostNoList() {
        assertTrue(migrationsScreen!!.state.value.entries.isNotEmpty(), "the premise: the matching connection lists the files")
        assertTrue(otherScreen!!.state.value.entries.none { it.file.version == "0003" }, "0003 is listed on another virtual host")
    }

    @Then("the result lists {string} as skipped by me and the binding as left out")
    fun leftOutBoth(name: String) {
        val report = done()
        assertTrue(report.leftOut.any { it.label == name && it.text == "skipped by you" }, "${report.leftOut}")
        assertTrue(report.leftOut.any { it.text.contains("The binding from \"events\" to \"$name\" is left out") }, "${report.leftOut}")
    }

    @Then("the notes repeat the message-loss recommendation of a new broker with a Shovel")
    fun notesShovel() = assertTrue(done().notes.any { it.contains("new 4.x broker") && it.contains("Shovel") }, "${done().notes}")
}
