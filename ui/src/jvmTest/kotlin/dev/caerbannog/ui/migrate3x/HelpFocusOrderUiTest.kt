package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.ui.migrations.MigrationsScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-14b (architect follow-up of the 14a review): the keyboard order of the three screens, from the first Tab. The "How to" link sits at the top right of the top line, so it must be the
 * FIRST stop, not the last. Every tip and link is reachable. Red while the link comes last.
 */
@OptIn(ExperimentalTestApi::class)
class HelpFocusOrderUiTest {
    private fun DesktopComposeUiTest.stops(count: Int): List<String> {
        val seen = mutableListOf<String>()
        repeat(count) {
            onRoot().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
            val tag = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.firstOrNull()
            seen += tag ?: "(untagged)"
        }
        return seen
    }

    @Test
    fun `Migrate from 3x - the first Tab stop is the How to link, then the source options and their tips`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live("legacy-eu"))))
        val stops = stops(8)
        assertEquals("howto-analyse-generate", stops.first(), "stops: $stops")
        listOf("migrate3x-source-file", "help-tip-m3x-3", "migrate3x-source-live", "help-tip-m3x-4", "migrate3x-choose-file").forEach { assertTrue(it in stops, "Tab never reached $it; stops: $stops") }
    }

    @Test
    fun `Migrations - the first Tab stop is the How to link`() = runDesktopComposeUiTest(1280, 900) {
        val f = MigrationFile("0003", "Desc", listOf(MigrationAction.DeclareQueue("orders", "quorum")), """{ "version": "0003" }""", "abc")
        val state = MigrationsScreenState(
            entries = listOf(MigrationEntry(f, MigrationStatus.Pending)), selectedVersion = "0003", selectedFile = f,
            dryRun = MigrationDryRun("0003", f.up.map { ActionDryRun(it, allowed = true) }, canApply = true), isApplyPendingEnabled = true, profileName = "uat-eu",
        )
        setContent {
            MigrationsScreen(state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {}, onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {})
        }
        val stops = stops(12)
        // the app frame (navigation items) and the app-bar buttons (New plan, Migrate from 3.x, Import, Export, Apply pending) come first by design; the first stop of the screen's own content is the link
        val appBar = setOf("migrations:newPlanBtn", "migrations:migrate3xBtn", "migrations:importBtn", "migrations:exportBtn", "migrations:applyPendingBtn")
        val content = stops.filterNot { it.startsWith("navItem:") || it in appBar }
        assertEquals("howto-apply-migrations", content.firstOrNull(), "stops: $stops")
    }

    @Test
    fun `forwarding - the first Tab stop is the How to link, then the find field`() = runDesktopComposeUiTest(1280, 900) {
        setContent { ForwardingView(ForwardingScreenState.Pick("", listOf(QueueRow("/", "orders")), null), NoForwardingEvents) }
        val stops = stops(6)
        assertEquals("howto-move-messages", stops.first(), "stops: $stops")
        assertTrue("forward-find" in stops, "Tab never reached the find field; stops: $stops")
    }

    @Test
    fun `full screen with forwarding open - both How to links are drawn, tab in order, and open their own dialogs`() = runDesktopComposeUiTest(1280, 900) {
        setContent {
            MigrateFromThreeScreen(
                state = MigrateFromThreeState.ChooseSource, reportText = { "" }, onChooseFile = {}, onGiveVirtualHost = {}, onCancelPrompt = {},
                onToggleSeverity = {}, onToggleGroup = {}, onExport = {}, onDismissNotice = {},
                forwarding = ForwardingScreenState.Pick("", listOf(QueueRow("/", "orders")), null), forwardingEvents = NoForwardingEvents,
            )
        }
        val tags = listOf("howto-analyse-generate", "howto-move-messages")
        tags.forEach { assertEquals(1, onAllNodes(androidx.compose.ui.test.hasTestTag(it), useUnmergedTree = true).fetchSemanticsNodes().size, "$it is drawn once") }
        val stops = stops(5)
        assertEquals(tags, stops.filter { it in tags }, "the top How to link comes before the forwarding one; stops: $stops")
    }
}
