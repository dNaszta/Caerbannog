package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.migrate3x.CompatibilityReport
import dev.caerbannog.core.migrate3x.CompatibilityReportText
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.FindingClass
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.ReportView
import dev.caerbannog.core.migrate3x.RowView
import dev.caerbannog.core.migrate3x.SaveOutcome
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * M8-06a step definitions for the `@m8-06a` scenarios of 16_migrate_from_3x.feature. Givens set up the file text and the fakes, Whens call
 * the view model's events and let virtual time run, Thens read the state. No logic here: every decision lives in `core`.
 * The Compose tests in `ui` bind the same words to the screen.
 */
class Migrate3xScreenSteps {
    private val w = Migrate3xScreenWorld

    // ---- helpers -------------------------------------------------------------------------------------------------------------------

    private fun report(): MigrateFromThreeState.Report = w.state() as? MigrateFromThreeState.Report ?: fail("Expected the report to be shown but the screen is in ${w.state()}")

    private fun view(): ReportView = report().view

    private fun held(): CompatibilityReport = assertNotNull(w.holder.held.value, "nothing is held: no report was analysed").report

    private fun allRows(): List<RowView> {
        val vm = w.viewModel()
        (w.state() as? MigrateFromThreeState.Report)?.view?.groups?.filter { !it.open }?.forEach { vm.toggleGroup(it.key) }
        w.settle()
        val v = view()
        return v.groups.flatMap { g -> v.rows(g.key).let { rows -> (0 until rows.size).map { rows.at(it) } } }
    }

    private fun open() = w.open()

    private fun setFile(text: String, label: String = "legacy.json") = w.setFile(text, label)

    // ---- Given: files ----------------------------------------------------------------------------------------------------------------

    @Given("a definitions file {string} exported from RabbitMQ 3.13.7 with 3 classic queues, 2 exchanges and 4 bindings in {string}")
    fun fileWithCounts(label: String, vhost: String) {
        setFile(
            DefinitionsFiles.file(
                queues = listOf("q1", "q2", "q3").map { DefinitionsFiles.queue(it, vhost = vhost) },
                exchanges = listOf(DefinitionsFiles.exchange("events", vhost = vhost), DefinitionsFiles.exchange("audit", "fanout", vhost)),
                bindings = (1..4).map { DefinitionsFiles.binding("events", "q${(it % 3) + 1}", "k$it", vhost) },
                vhosts = listOf(vhost),
            ),
            label,
        )
    }

    @Given("a definitions file with 2 classic queues without \"x-queue-version\"")
    fun twoClassicQueues() = setFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("invoices"))))

    @Given("^a definitions file with (12|60,000) classic queues without \"x-queue-version\"$")
    fun manyClassicQueues(count: String) = setFile(DefinitionsFiles.classicQueues(count.replace(",", "").toInt()))

    @Given("^a file that (is not valid JSON|is a JSON array|is larger than the limit|cannot be read from the disk)$")
    fun aFileThat(problem: String) {
        w.path = "/data/definitions.json"
        w.files[w.path] = when (problem) {
            "is not valid JSON" -> FileLoadResult.Loaded("{ not json")
            "is a JSON array" -> FileLoadResult.Loaded("[1, 2]")
            "is larger than the limit" -> FileLoadResult.TooLarge(100L * 1024 * 1024, 64L * 1024 * 1024)
            else -> FileLoadResult.Unreadable("EACCES: /home/someone/definitions.json (SENTINEL-PASSWORD)")
        }
    }

    @Given("the report of a file with blockers, warnings and infos is shown")
    fun mixedReportShown() {
        setFile(
            DefinitionsFiles.file(
                queues = listOf(
                    DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("temp", durable = false),
                    DefinitionsFiles.queue("jobs", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
                ),
                policies = listOf(
                    DefinitionsFiles.policy("ha-all", "^orders", mapOf("ha-mode" to JsonPrimitive("all"))),
                    DefinitionsFiles.policy("lookahead", "^(?!amq\\.).*", mapOf("max-length" to JsonPrimitive(10))),
                ),
            ),
        )
        open()
        val s = report().view.summary
        assertTrue(s.blockers > 0 && s.warnings > 0 && s.infos > 0, "the premise: blockers, warnings and infos exist, got $s")
    }

    @Given("a definitions file with 60,000 classic queues")
    fun sixtyThousand() = setFile(DefinitionsFiles.classicQueues(60_000))

    @Given("a definitions file with a policy whose pattern is {string}")
    fun policyWithPattern(pattern: String) = setFile(
        DefinitionsFiles.file(
            queues = listOf(DefinitionsFiles.queue("orders", type = "quorum")),
            policies = listOf(DefinitionsFiles.policy("lookahead", pattern, mapOf("max-length" to JsonPrimitive(10)))),
        ),
    )

    @Given("a definitions file with a queue named {string} followed by a control character")
    fun queueWithControl(name: String) = setFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(name + "\u0007"))))

    @Given("a definitions file with a queue named {string}, the right-to-left override U+202E and {string}")
    fun queueWithOverride(before: String, after: String) = setFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(before + "\u202E" + after, durable = false), DefinitionsFiles.queue(before + "\u202E" + after))))

    @Given("a definitions file exported from RabbitMQ 3.13.7")
    fun anyFile() = setFile(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("temp", durable = false))))

    @Given("the chosen place cannot be written")
    fun placeCannotBeWritten() {
        w.saver.outcome = SaveOutcome.Failed("The place cannot be written")
    }

    @Given("I analysed the file {string} and the report is shown")
    fun analysedAndShown(label: String) = w.reportForFileIsShown(label)

    @Given("I opened that file on the Migrate from 3.x screen")
    fun openedThatFile() = open()

    // ---- When ------------------------------------------------------------------------------------------------------------------------

    @When("I open that file on the Migrate from 3.x screen")
    fun openThatFile() = open()

    @When("I open that file on the Migrate from 3.x screen and export the report")
    fun openAndExport() {
        open()
        exportReport()
    }

    private fun exportReport() = w.exportReport()

    private fun giveVirtualHost(name: String) = w.giveVirtualHost(name)

    @When("I turn off the chips {string} and {string}")
    fun turnOffChips(first: String, second: String) {
        listOf(first, second).forEach { w.viewModel().toggleSeverity(Severity.valueOf(it)) }
        w.settle()
    }

    @When("I open that file on the Migrate from 3.x screen and export the report as text")
    fun openAndExportText() {
        open()
        w.clipboard = w.viewModel().reportText()
    }

    @When("I copy the report as text")
    fun copyAsText() {
        w.clipboard = w.viewModel().reportText()
        w.viewModel().reportCopied(); w.settle()
    }

    @When("I connect to {string}, a broker running RabbitMQ 4")
    fun connectTo(name: String) {
        w.connectedTo = name
    }

    @When("I open the Migrate from 3.x screen again")
    fun openScreenAgain() {
        // The window builds a new view model when its inputs change; the held analysis lives in the AnalysisHolder.
        w.newViewModel()
        w.settle()
    }

    // ---- Then: the report ------------------------------------------------------------------------------------------------------------

    @Then("the screen shows the report for {string} from RabbitMQ 3.13.7")
    fun showsReportFor(label: String) {
        assertEquals(label, view().fileLabel)
        assertEquals("3.13.7", view().sourceVersion)
        assertTrue(view().groups.isNotEmpty(), "the report lists the findings as groups")
    }

    @Then("the severity chips show the counts of blockers, warnings and infos")
    fun chipsShowCounts() {
        val s = held().summary
        assertEquals(listOf(Severity.Blocker to s.blockers, Severity.Warning to s.warnings, Severity.Info to s.infos), view().chips.map { it.severity to it.count })
        assertTrue(view().chips.all { it.selected }, "all chips start on")
    }

    @Then("the screen says how many items are compatible as they are and how many need a decision")
    fun saysHowMany() {
        val s = held().summary
        val line = view().summaryLine
        assertTrue(line.contains("${s.compatibleItems}") && line.contains("compatible as they are"), "summary line: \"$line\"")
        assertTrue(line.contains("${s.needDecision}") && line.contains("need a decision"), "summary line: \"$line\"")
        assertTrue(s.compatibleItems > 0, "the premise: some items need no change")
    }

    @Then("the compatible items are not listed as problems")
    fun compatibleNotListed() {
        val rows = allRows()
        assertEquals(held().findings.size, rows.size, "one row per finding and nothing else")
        assertTrue(rows.none { it.kind == ItemKind.Exchange || it.kind == ItemKind.Binding }, "exchanges and bindings are compatible, but rows exist for them: $rows")
    }

    // ---- Then: message-loss block ----------------------------------------------------------------------------------------------------

    @Then("the first block of the report says an in-place upgrade to RabbitMQ 4.3 or later would silently empty classic queues")
    fun lossBlockTitle() {
        val loss = assertNotNull(view().lossBlock, "the message-loss block is missing")
        assertTrue(loss.title.contains("in-place upgrade to RabbitMQ 4.3 or later would silently empty 2 classic queues"), "title: ${loss.title}")
    }

    @Then("it lists both queues and says the loss cannot be detected after the upgrade")
    fun lossBlockQueues() {
        val loss = assertNotNull(view().lossBlock)
        assertEquals(2, loss.queueCount)
        assertEquals(setOf("orders", "invoices"), loss.preview.toSet())
        assertTrue(loss.detection.contains("cannot be detected after the upgrade"), "detection text: ${loss.detection}")
    }

    @Then("it recommends a new 4.x broker with this migration and a Shovel")
    fun lossBlockRecommends() {
        val fixes = assertNotNull(view().lossBlock).fixes
        val recommended = fixes.filter { it.recommended }
        assertEquals(1, recommended.size, "exactly one recommended fix: $fixes")
        val text = recommended.single().let { "${it.title} ${it.text}" }
        assertTrue(text.contains("new 4.x broker", ignoreCase = true) && text.contains("Shovel"), "recommended: $text")
    }

    @Then("\"upgrade in place\" is never shown as a green option")
    fun upgradeNeverGreen() {
        val fixes = assertNotNull(view().lossBlock).fixes
        assertTrue(fixes.filter { it.recommended }.none { "${it.title} ${it.text}".contains("upgrade in place", ignoreCase = true) && !it.title.contains("do not", ignoreCase = true) && !it.text.contains("do not upgrade in place", ignoreCase = true) }, "a recommended fix offers an in-place upgrade: $fixes")
        assertTrue(fixes.none { it.recommended && it.title.contains("upgrade", ignoreCase = true) && !it.title.contains("do not", ignoreCase = true) }, "$fixes")
    }

    // ---- Then: errors and the prompt -------------------------------------------------------------------------------------------------

    @Then("the screen shows the error {string} and no report")
    fun showsError(reason: String) {
        val state = w.state() as? MigrateFromThreeState.Rejected ?: fail("Expected a rejected file but the screen is in ${w.state()}")
        assertEquals(reason, state.text)
        assertNull(w.holder.held.value, "no report is held after a rejected file")
    }

    @Then("I can choose another file")
    fun canChooseAnother() {
        val other = "/data/other.json"
        w.files[other] = FileLoadResult.Loaded(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("ok", type = "quorum"))))
        w.chooser.next = other
        w.viewModel().chooseFile(); w.settle()
        assertEquals(1, w.chooser.calls, "the file dialog opened")
        assertEquals("other.json", report().view.fileLabel, "the second file is read")
    }

    @Then("the screen asks for the virtual host name with an empty field and no default")
    fun asksForName() {
        val state = w.state() as? MigrateFromThreeState.NeedsVirtualHost ?: fail("Expected the virtual host prompt but the screen is in ${w.state()}")
        assertNull(state.error, "no error before a name was given")
    }

    @Then("the report is shown for the virtual host {string}")
    fun reportForVhost(name: String) {
        assertEquals(name, view().singleVirtualHost)
        assertEquals(name, w.holder.held.value?.document?.queues?.firstOrNull()?.vhost)
    }

    @Then("the report says the file holds no users, virtual hosts or permissions, so the access migration is empty")
    fun accessNote() {
        val note = assertNotNull(view().accessNote, "the access note is missing")
        assertTrue(note.contains("no users, virtual hosts or permissions") && note.contains("access migration is empty"), note)
    }

    @Then("the field shows the refusal of the name as an inline error")
    fun inlineError() {
        val state = w.state() as? MigrateFromThreeState.NeedsVirtualHost ?: fail("Expected the prompt to stay but the screen is in ${w.state()}")
        assertEquals("The virtual host name must not be empty", state.error)
    }

    @Then("I can try another name or choose another file")
    fun tryAnotherName() {
        giveVirtualHost("orders")
        assertEquals("orders", view().singleVirtualHost)
    }

    // ---- Then: filter, scale, secrets, text ------------------------------------------------------------------------------------------

    @Then("only blockers are listed")
    fun onlyBlockers() {
        val groups = view().groups
        assertTrue(groups.isNotEmpty() && groups.all { it.severity == Severity.Blocker }, "groups: $groups")
        assertEquals(listOf(true, false, false), view().chips.map { it.selected })
    }

    @Then("the message-loss block is still first")
    fun lossStillFirst() {
        assertNotNull(view().lossBlock, "the filter removed the message-loss block")
    }

    @Then("each chip still shows its own count")
    fun chipsKeepCounts() {
        val s = held().summary
        assertEquals(listOf(s.blockers, s.warnings, s.infos), view().chips.map { it.count })
    }

    @Then("the report shows groups with the number of findings in each")
    fun groupsWithCounts() {
        val v = view()
        assertTrue(v.groups.isNotEmpty() && v.groups.size < 20, "groups: ${v.groups.size}")
        assertEquals(held().findings.size, v.groups.sumOf { it.count })
    }

    @Then("the screen state holds no finding rows for a group that is closed")
    fun closedHoldsNoRows() {
        val v = view()
        val closed = v.groups.filter { !it.open }
        assertTrue(closed.isNotEmpty(), "the premise: some group is closed")
        closed.forEach { assertEquals(0, v.rows(it.key).size, "closed group ${it.key} holds rows") }
    }

    @Then("opening a group offers its findings as a lazy list")
    fun openingOffersLazyList() {
        val biggest = view().groups.maxBy { it.count }
        w.viewModel().toggleGroup(biggest.key); w.settle()
        val rows = view().rows(biggest.key)
        assertEquals(biggest.count, rows.size)
        assertNotNull(rows.at(0)); assertNotNull(rows.at(rows.size - 1))
    }

    @Then("the row for the user {string} shows its tags, its hashing algorithm and the text {string}")
    fun userRow(user: String, hashText: String) {
        val row = allRows().firstOrNull { it.kind == ItemKind.User && it.name == user } ?: fail("no row for the user $user")
        val u = assertNotNull(row.user, "the user row has no user part")
        assertEquals(listOf("management", "policymaker"), u.tags)
        assertEquals("rabbit_password_hashing_sha256", u.algorithm)
        assertEquals(hashText, u.hashText)
    }

    @Then("no text, no semantics description and no screenshot of the screen contains the password hash or the password")
    fun noSecrets() {
        val rows = allRows()
        val everything = listOf(w.state().toString(), rows.joinToString("\n"), w.viewModel().reportText(), w.holder.held.value.toString())
        everything.forEach { text -> listOf("SENTINEL-HASH", "SENTINEL-PASSWORD").forEach { assertFalse(text.contains(it), "found $it in: ${text.take(200)}") } }
    }

    @Then("the finding says the app could not evaluate the pattern and did not use it to decide which queues are mirrored")
    fun patternSays() {
        val row = allRows().single { it.name == "lookahead" }
        assertTrue(row.message.contains("could not evaluate"), row.message)
        assertTrue(row.message.contains("did not use it to decide which queues are mirrored"), row.message)
        assertFalse(row.message.contains("amq"), "the pattern text is not shown")
    }

    @Then("it asks the user to check by hand")
    fun asksCheckByHand() {
        assertTrue(allRows().single { it.name == "lookahead" }.message.contains("by hand"))
    }

    @Then("the row shows the name as plain text with the control character escaped")
    fun plainName() {
        val row = allRows().firstOrNull { it.name?.startsWith("<b>x</b>") == true } ?: fail("no row for the queue: ${allRows()}")
        assertEquals("<b>x</b>\\u0007", row.name)
    }

    @Then("nothing is interpreted as markup")
    fun noMarkup() {
        allRows().forEach { r ->
            listOfNotNull(r.name, r.message, r.why, r.proposal).forEach { s -> assertTrue(s.none { it.code < 0x20 || it.code == 0x7F }, "control character in a row text: $s") }
        }
        val loss = assertNotNull(view().lossBlock)
        assertTrue(loss.preview.none { name -> name.any { it.code < 0x20 } }, "control character in the loss block")
    }

    @Then("the row and the text show the name as {string} with the override escaped")
    fun overrideEscaped(shown: String) {
        assertEquals("a\\u202Eb", shown, "the scenario names the escaped form")
        val rows = allRows().filter { it.name?.startsWith("a") == true }
        assertTrue(rows.isNotEmpty(), "no row for the queue")
        rows.forEach { assertEquals(shown, it.name) }
        assertTrue(assertNotNull(w.clipboard, "no text export").contains(shown), "the text export does not show $shown")
    }

    @Then("neither contains the override character itself")
    fun noRawOverride() {
        val everything = allRows().joinToString("\n") + "\n" + w.clipboard + "\n" + assertNotNull(view().lossBlock).copyText
        assertFalse(everything.contains('\u202E'), "a raw U+202E reached the screen text")
    }

    // ---- loss preview and copy ---------------------------------------------------------------------------------------------------

    @Then("the message-loss block names the first 10 queues and says there are 60,000")
    fun lossPreview() {
        val loss = assertNotNull(view().lossBlock)
        assertEquals(60_000, loss.queueCount)
        assertEquals(10, loss.preview.size)
        assertEquals((0 until 10).map { loss.queueAt(it) }, loss.preview)
    }

    /** "Show all N" only changes how the screen draws the list (the Compose tests click it); the view must offer every name. */
    @When("I choose \"Show all 60,000\"")
    fun chooseShowAll() {
        assertEquals(60_000, assertNotNull(view().lossBlock).queueCount)
    }

    @Then("the block offers every queue name as a lazy list")
    fun everyNameByIndex() {
        val loss = assertNotNull(view().lossBlock)
        assertEquals("q-00001", loss.queueAt(0))
        assertEquals("q-60000", loss.queueAt(59_999))
    }

    @When("I copy the name of the first row of a group")
    fun copyFirstRow() {
        val group = view().groups.firstOrNull { it.findingClass != FindingClass.MessageLoss } ?: view().groups.first()
        if (!group.open) w.viewModel().toggleGroup(group.key)
        w.settle()
        val rows = view().rows(group.key)
        assertTrue(rows.size > 0, "the group ${group.key} has no rows")
        w.copiedName = rows.at(0).name
        w.copiedExpected = rows.at(0).name
    }

    @Then("the clipboard holds that name, with control characters escaped as shown")
    fun clipboardHoldsName() {
        val copied = assertNotNull(w.copiedName, "nothing was copied")
        assertEquals(w.copiedExpected, copied)
        assertTrue(copied.none { it.code < 0x20 }, "a control character was copied: $copied")
    }

    @When("I copy the queue names of the message-loss block")
    fun copyLossNames() {
        w.copiedLoss = assertNotNull(view().lossBlock).copyText
    }

    @Then("the clipboard holds all 12 names, one per line")
    fun clipboardHoldsAll() {
        val loss = assertNotNull(view().lossBlock)
        assertEquals((0 until 12).map { loss.queueAt(it) }, assertNotNull(w.copiedLoss, "nothing was copied").lines())
        assertEquals(12, loss.queueCount)
    }

    @Then("the clipboard holds no password hash and no password")
    fun clipboardClean() {
        listOfNotNull(w.copiedName, w.copiedLoss).forEach { c -> listOf("SENTINEL-HASH", "SENTINEL-PASSWORD").forEach { assertFalse(c.contains(it)) } }
    }

    // ---- Then: export ----------------------------------------------------------------------------------------------------------------

    @Then("the app asks where to save a JSON file with the schema {string}")
    fun savedJson(schema: String) {
        val save = w.saver.saves.singleOrNull() ?: fail("expected exactly one save, got ${w.saver.saves.size}")
        assertTrue(save.suggestedName.endsWith(".json"), save.suggestedName)
        assertTrue(save.text.contains("\"schema\": \"$schema\""), "no schema in: ${save.text.take(120)}")
    }

    @Then("the saved file has no password, no password hash and no shovel URI")
    fun savedFileClean() {
        val text = w.saver.saves.single().text
        listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://").forEach { assertFalse(text.contains(it), "the saved file holds $it") }
    }

    @Then("the clipboard holds the text form of the report")
    fun clipboardHoldsText() {
        assertEquals("Report copied as text", report().notice?.text, "the notice after a copy")
        assertEquals(CompatibilityReportText.render(held()), assertNotNull(w.clipboard, "nothing was copied"))
    }

    @Then("the screen says the report could not be saved, with a fixed reason")
    fun saveFailed() {
        val notice = assertNotNull(report().notice, "no notice after a failed save")
        assertTrue(notice.failed)
        assertTrue(notice.text.startsWith("The report could not be saved") && notice.text.contains("The place cannot be written"), notice.text)
    }

    @Then("the report is still shown")
    fun stillShown() {
        assertTrue(w.state() is MigrateFromThreeState.Report, "the report is gone: ${w.state()}")
    }

    @Then("the report for {string} is still shown")
    fun reportStillShown(label: String) {
        assertEquals("new-eu", w.connectedTo, "the premise: the user connected to the 4.x broker")
        assertEquals(label, view().fileLabel)
    }
}
