package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.ExportNotice
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.VirtualHostAssignment
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-06a: the states 1 to 5 and 12 of `docs/ux/Migrate3x.dc.html`. State in, events out: the screen is given a [MigrateFromThreeState] and records
 * which callback was called. Texts are read from the semantics tree below the tagged node.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeStatesUiTest {
    private val stateTags = listOf("choose-source", "loading", "rejected", "needs-virtual-host", "report")

    private fun androidx.compose.ui.test.DesktopComposeUiTest.onlyState(name: String) {
        assertDrawn("migrate3x-state-$name")
        stateTags.filter { it != name }.forEach { assertNotDrawn("migrate3x-state-$it") }
    }

    @Test
    fun `state 1 - choose source offers only the definitions file, its help and the 64 MB limit`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls)
        onlyState("choose-source")
        assertTextHas("migrate3x-state-choose-source", "Definitions file", "Nothing is read until you choose a file", "64 MB")
        onNodeWithTag("migrate3x-choose-file").assertIsEnabled().performClick()
        assertEquals(listOf("chooseFile"), calls.events)
        assertNotDrawn("migrate3x-export")
        assertNotDrawn("migrate3x-loss-banner")
    }

    @Test
    fun `state 2 - loading shows the file name and Cancel`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.Loading("legacy-eu-definitions.json"), calls)
        onlyState("loading")
        assertTextHas("migrate3x-state-loading", "Reading and analysing", "legacy-eu-definitions.json")
        onNodeWithTag("migrate3x-cancel").performClick()
        assertEquals(listOf("cancel"), calls.events)
    }

    @Test
    fun `state 3 - a rejected file shows the fixed text, the file name and a way to choose another file`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.Rejected("notes.json", "The file is not a definitions file"), calls)
        onlyState("rejected")
        assertTextHas("migrate3x-state-rejected", "The file is not a definitions file", "notes.json", "Nothing was analysed")
        onNodeWithTag("migrate3x-choose-file").performClick()
        assertEquals(listOf("chooseFile"), calls.events)
        assertNotDrawn("migrate3x-loss-banner")
    }

    @Test
    fun `state 4 - the virtual host prompt starts with an empty field, no error and no default`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null), calls)
        onlyState("needs-virtual-host")
        assertTextHas("migrate3x-state-needs-virtual-host", "Which virtual host is this file from?")
        assertEquals("", textOf("migrate3x-vhost-field").replace("Virtual host name", "").trim(), "the field starts empty: no \"/\", no default")
        assertNotDrawn("migrate3x-vhost-error")
    }

    @Test
    fun `state 4 - the typed name goes out untouched with the button and with Enter`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null), calls)
        onNodeWithTag("migrate3x-vhost-field").performTextInput("  orders  ")
        onNodeWithTag("migrate3x-vhost-analyse").performClick()
        onNodeWithTag("migrate3x-vhost-field").requestFocus()
        onNodeWithTag("migrate3x-vhost-field").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("vhost:  orders  ", "vhost:  orders  "), calls.events, "the view model trims, the screen sends what was typed")
    }

    @Test
    fun `state 4 - a refused name shows the core refusal as returned, inline`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.NeedsVirtualHost("vhost.json", "The virtual host name cannot contain a tab"))
        assertEquals("The virtual host name cannot contain a tab", textOf("migrate3x-vhost-error").trim())
        onlyState("needs-virtual-host")
    }

    @Test
    fun `state 4 - Choose another file is available from the prompt`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null), calls)
        onNodeWithTag("migrate3x-choose-file").performClick()
        assertEquals(listOf("chooseFile"), calls.events)
    }

    @Test
    fun `state 5 - the report shows the header, the loss block, the chips, the summary and the groups`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report()
        show(state)
        onlyState("report")
        val view = state.view
        assertTextHas("migrate3x-state-report", "legacy.json", "3.13.7", "${view.counts.queues} queues", view.summaryLine)
        assertTextHas("migrate3x-loss-banner", "silently empty", "orders", "invoices", "Shovel", "cannot be detected")
        view.chips.forEach { chip ->
            assertTextHas("migrate3x-chip-${chip.severity.name.lowercase()}", "${chip.count}")
        }
        view.groups.forEach { g -> assertTextHas("migrate3x-group-${g.key}", "${g.count}") }
        assertNotDrawn("migrate3x-row-0")
    }

    @Test
    fun `state 5 - chips, groups and the export menu call their events`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report()
        val calls = MigrateCalls()
        show(state, calls, text = "TEXT OF THE REPORT")
        onNodeWithTag("migrate3x-chip-warning").performClick()
        val key = state.view.groups.first().key
        onNodeWithTag("migrate3x-group-$key").performClick()
        onNodeWithTag("migrate3x-export").performClick()
        assertEquals(listOf("severity:${Severity.Warning}", "group:$key", "export"), calls.events)
    }

    @Test
    fun `state 5 - Copy as text puts the text of the report on the clipboard`() = runDesktopComposeUiTest(1280, 800) {
        val clipboard = RecordingClipboard()
        show(MigrateScreenFiles.report(), text = "TEXT OF THE REPORT", clipboard = clipboard)
        onNodeWithTag("migrate3x-copy-text").performClick()
        waitForIdle()
        assertEquals(listOf<String?>("TEXT OF THE REPORT"), clipboard.copied)
    }

    @Test
    fun `state 5 - a deselected chip is shown as off and the loss block stays`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateScreenFiles.report(selected = setOf(Severity.Blocker)))
        assertDrawn("migrate3x-loss-banner")
        assertTrue(nodes("migrate3x-chip-blocker").isNotEmpty() && nodes("migrate3x-chip-warning").isNotEmpty() && nodes("migrate3x-chip-info").isNotEmpty())
    }

    @Test
    fun `state 12 - a saved report shows where it went, with a way to dismiss`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateScreenFiles.report(notice = ExportNotice("Report saved to /home/me/report.json", failed = false)), calls)
        assertTextHas("migrate3x-notice", "/home/me/report.json")
        onNodeWithTag("migrate3x-notice-dismiss").performClick()
        assertEquals(listOf("dismissNotice"), calls.events)
    }

    @Test
    fun `state 12 - a failed save says so and the report stays`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateScreenFiles.report(notice = ExportNotice("The report could not be saved: The place cannot be written", failed = true)))
        assertTextHas("migrate3x-notice", "The report could not be saved")
        assertDrawn("migrate3x-state-report")
        assertDrawn("migrate3x-loss-banner")
    }

    @Test
    fun `no notice is drawn when there is none`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateScreenFiles.report())
        assertNotDrawn("migrate3x-notice")
    }

    @Test
    fun `the report of a single virtual host file says the access migration is empty and why`() = runDesktopComposeUiTest(1280, 800) {
        val draft = (DefinitionsParser.parse(DefinitionsFixtures.read(DefinitionsFixtures.VHOST_V1)) as DefinitionsParseResult.NeedsVirtualHost).draft
        val document = (draft.assignVirtualHost("orders") as VirtualHostAssignment.Assigned).document
        val held = HeldAnalysis("vhost.json", document, CompatibilityAnalyzer.analyze(document))
        show(MigrateFromThreeState.Report(ReportViewBuilder.build(held), null))
        assertTextHas("migrate3x-access-note", "no users, virtual hosts or permissions", "access migration is empty")
        assertTextHas("migrate3x-state-report", "orders")
    }

    @Test
    fun `the access note is not drawn for a full export`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateScreenFiles.report())
        assertNotDrawn("migrate3x-access-note")
    }
}
