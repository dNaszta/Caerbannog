package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeLiveSourceProvider
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-06c: the live source on the Migrate from 3.x screen (`docs/ux/Migrate3x.dc.html`, notes of 2026-11-10): the source picker, "Analyse live connection",
 * `LoadingLive`, the locked source card, `CannotExport` and `LiveFailed`. State in, events out; events are recorded as short strings. Tags: see the
 * brief `docs/tasks/M8-06c-live-source-on-screen.md` (Scope 2). Nothing here draws a connect action: the picker only reads the held connection.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeLiveSourceUiTest {
    private val reason = MigrateFromThreeTexts.LIVE_NOT_AVAILABLE
    private val liveOffered = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live("legacy-eu")))
    private val liveSelected = liveOffered.copy(selected = SourceKind.Live)
    private val liveDisabled = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(reason)))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.says(text: String): Boolean {
        val config = fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text in it } ||
            config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
    }

    /** Hovers the node: a tooltip is a popup, so it shows up as a second root of the semantics tree whose texts hold [text]. */
    private fun DesktopComposeUiTest.tooltipShows(tag: String, text: String): Boolean {
        onNodeWithTag(tag).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(2000)
        waitForIdle()
        fun texts(n: androidx.compose.ui.semantics.SemanticsNode): String =
            (listOf(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()) + n.children.map { texts(it) }).joinToString(" ")
        val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        return roots.drop(1).any { texts(it).contains(text) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.canTakeFocus() =
        assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))

    private fun liveReport(label: String = "legacy-eu", connected: String? = "legacy-eu"): MigrateFromThreeState.Report {
        val file = MigrateScreenFiles.held()
        val held = HeldAnalysis(label, file.document, file.report, SourceKind.Live)
        return MigrateFromThreeState.Report(ReportViewBuilder.build(held, liveConnectionName = connected), null, dev.caerbannog.core.migrate3x.ReviewState.NONE)
    }

    // ---- the line at the top (owner decision 2026-11-10: one line for both sources) ----

    @Test
    fun `the info line is the one line for both sources in every state`() = runDesktopComposeUiTest(1280, 800) {
        val expected = "This screen only reads a file or the connected 3.x broker. It sends nothing that changes any broker."
        assertEquals(expected, MigrateFromThreeTexts.INFO_LINE)
        listOf(
            MigrateFromThreeState.ChooseSource, MigrateFromThreeState.LoadingLive("legacy-eu"), MigrateFromThreeState.CannotExport("legacy-eu", LiveSourceTexts.CANNOT_EXPORT),
            MigrateFromThreeState.LiveFailed("legacy-eu", LiveSourceTexts.unreachable("legacy-eu")), liveReport(),
        ).forEach { state ->
            show(state, picker = liveOffered)
            assertTextHas("migrate3x-info", expected)
            assertFalse(textOf("migrate3x-info").contains("needs no connection"), "the old file-only line is gone: ${textOf("migrate3x-info")}")
        }
    }

    // ---- the source picker ----

    @Test
    fun `live enabled - both options are offered, the file is selected, and the file button and hints show`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls, picker = liveOffered)
        assertTextHas("migrate3x-state-choose-source", "Where does the 3.x topology come from?")
        assertTextHas("migrate3x-source-file", "Definitions file")
        assertTextHas("migrate3x-source-live", "Live connection: legacy-eu")
        onNodeWithTag("migrate3x-source-file").assertIsEnabled().assertIsSelected()
        onNodeWithTag("migrate3x-source-live").assertIsEnabled().assertIsNotSelected()
        assertNotDrawn("migrate3x-source-live-reason")
        assertDrawn("migrate3x-choose-file")
        assertNotDrawn("migrate3x-analyse-live")
        assertTextHas("migrate3x-state-choose-source", "Nothing is read until you choose a file", "64 MB")
        onNodeWithTag("migrate3x-source-live").performClick()
        assertEquals(listOf("chooseSource:Live"), calls.events)
    }

    @Test
    fun `live selected - the button Analyse live connection replaces Choose definitions file and the file hints go`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls, picker = liveSelected)
        onNodeWithTag("migrate3x-source-live").assertIsSelected()
        onNodeWithTag("migrate3x-source-file").assertIsNotSelected()
        assertTextHas("migrate3x-analyse-live", "Analyse live connection")
        assertNotDrawn("migrate3x-choose-file")
        assertTrue(!wholeScreenText().contains("Nothing is read until you choose a file"), "the file hints stay with the file option")
        onNodeWithTag("migrate3x-analyse-live").assertIsEnabled().performClick()
        assertEquals(listOf("analyseLive"), calls.events)
        onNodeWithTag("migrate3x-source-file").performClick()
        assertEquals(listOf("analyseLive", "chooseSource:File"), calls.events)
    }

    @Test
    fun `live disabled - greyed, not selectable, with the reason as visible text and as accessible description`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls, picker = liveDisabled)
        assertTextHas("migrate3x-source-live", "Live connection")
        onNodeWithTag("migrate3x-source-live").assertIsNotEnabled()
        assertEquals(reason, textOf("migrate3x-source-live-reason"), "the fixed reason is visible under the option")
        assertTrue(onNodeWithTag("migrate3x-source-live").says(reason), "the disabled option says the reason to a screen reader")
        assertTrue(tooltipShows("migrate3x-source-live", reason), "hovering the disabled option shows the reason as a tooltip")
        onNodeWithTag("migrate3x-source-live").performClick()
        assertEquals(emptyList(), calls.events, "a disabled option does nothing")
        assertNotDrawn("migrate3x-analyse-live")
        assertDrawn("migrate3x-choose-file")
        onNodeWithTag("migrate3x-source-file").assertIsSelected()
    }

    @Test
    fun `the picker draws no connect action, only the options and the file button can be clicked`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveDisabled)
        val clickable = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toSet()
        assertTrue(clickable.all { it in setOf("migrate3x-source-file", "migrate3x-source-live", "migrate3x-choose-file") }, "clickable things on the empty screen: $clickable")
    }

    @Test
    fun `keyboard - both picker options and the live button are reachable, the disabled option is not`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls, picker = liveOffered)
        onNodeWithTag("migrate3x-source-file").canTakeFocus()
        onNodeWithTag("migrate3x-source-live").canTakeFocus()
        onNodeWithTag("migrate3x-source-live").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("migrate3x-source-live").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("chooseSource:Live"), calls.events, "Enter on the focused live option selects it")

        val selected = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, selected, picker = liveSelected)
        onNodeWithTag("migrate3x-analyse-live").canTakeFocus()
        onNodeWithTag("migrate3x-analyse-live").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("migrate3x-analyse-live").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("analyseLive"), selected.events, "Enter on the focused button analyses")

        show(MigrateFromThreeState.ChooseSource, picker = liveDisabled)
        onNodeWithTag("migrate3x-source-live").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    @Test
    fun `the hint under the live button names the connection and says nothing is changed, only while live is selected`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveSelected)
        assertEquals("Reads the definitions of legacy-eu with GET requests. Nothing on the broker is changed.", textOf("migrate3x-analyse-live-hint"))
        show(MigrateFromThreeState.ChooseSource, picker = liveOffered)
        assertNotDrawn("migrate3x-analyse-live-hint")
    }

    @Test
    fun `the picker is a selectable group of radio buttons that follow the selected kind`() = runDesktopComposeUiTest(1280, 800) {
        for ((picker, fileOn) in listOf(liveOffered to true, liveSelected to false)) {
            show(MigrateFromThreeState.ChooseSource, picker = picker)
            listOf("migrate3x-source-file", "migrate3x-source-live").forEach { tag ->
                onNodeWithTag(tag).assert(SemanticsMatcher("role is RadioButton") { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.RadioButton })
                onNodeWithTag(tag).assert(SemanticsMatcher("inside a selectable group") {
                    generateSequence(it.parent) { n -> n.parent }.any { n -> n.config.contains(SemanticsProperties.SelectableGroup) }
                })
            }
            if (fileOn) onNodeWithTag("migrate3x-source-file").assertIsSelected() else onNodeWithTag("migrate3x-source-live").assertIsSelected()
        }
    }

    @Test
    fun `Space and Enter on the focused enabled option choose it`() = runDesktopComposeUiTest(1280, 800) {
        for (key in listOf(Key.Enter, Key.Spacebar)) {
            val calls = MigrateCalls()
            show(MigrateFromThreeState.ChooseSource, calls, picker = liveOffered)
            onNodeWithTag("migrate3x-source-live").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithTag("migrate3x-source-live").performKeyInput { pressKey(key) }
            assertEquals(listOf("chooseSource:Live"), calls.events, "$key")
        }
    }

    @Test
    fun `the disabled live option is disabled, not focusable, and its description is the label and the reason`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveDisabled)
        onNodeWithTag("migrate3x-source-live").assertIsNotEnabled()
        onNodeWithTag("migrate3x-source-live").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
        val description = onNodeWithTag("migrate3x-source-live").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("Live connection: $reason" in description, "description: $description")
        assertEquals(reason, textOf("migrate3x-source-live-reason"))
    }

    // ---- the live read, and what can end it ----

    @Test
    fun `LoadingLive - names the connection, says nothing is changed and offers Cancel`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.LoadingLive("legacy-eu"), calls, picker = liveSelected)
        assertDrawn("migrate3x-state-loading-live")
        assertNotDrawn("migrate3x-state-loading")
        assertNotDrawn("migrate3x-state-choose-source")
        assertTextHas("migrate3x-state-loading-live", "Reading and analysing legacy-eu", "Reading the broker can take a few seconds per virtual host. Nothing on it is changed.")
        onNodeWithTag("migrate3x-cancel").performClick()
        assertEquals(listOf("cancel"), calls.events)
    }

    @Test
    fun `CannotExport - the fixed text, nothing analysed and the file import as a button`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.CannotExport("legacy-eu", LiveSourceTexts.CANNOT_EXPORT), calls, picker = liveSelected)
        assertDrawn("migrate3x-state-cannot-export")
        assertTextHas("migrate3x-state-cannot-export", "This user cannot export definitions: the administrator tag is needed", "Nothing was analysed")
        assertTextHas("migrate3x-choose-file", "Choose definitions file")
        assertNotDrawn("migrate3x-try-again")
        assertNotDrawn("migrate3x-state-report")
        onNodeWithTag("migrate3x-choose-file").performClick()
        assertEquals(listOf("chooseFile"), calls.events)
    }

    @Test
    fun `CannotExport - the file button is reachable and works from the keyboard`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.CannotExport("legacy-eu", LiveSourceTexts.CANNOT_EXPORT), calls, picker = liveSelected)
        onNodeWithTag("migrate3x-choose-file").canTakeFocus()
        onNodeWithTag("migrate3x-choose-file").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("migrate3x-choose-file").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("chooseFile"), calls.events)
    }

    @Test
    fun `LiveFailed - every fixed text is shown as it is, with Try again and Choose definitions file`() = runDesktopComposeUiTest(1280, 800) {
        val texts = listOf(
            LiveSourceTexts.unreachable("legacy-eu"), LiveSourceTexts.READ_FAILED, LiveSourceTexts.EXPORT_TOO_LARGE, LiveSourceTexts.EXPORT_UNUSABLE,
            LiveSourceTexts.VERSION_UNREADABLE, LiveSourceTexts.NOT_THREE_X,
        )
        for (text in texts) {
            val calls = MigrateCalls()
            show(MigrateFromThreeState.LiveFailed("legacy-eu", text), calls, picker = liveSelected)
            assertDrawn("migrate3x-state-live-failed")
            assertTextHas("migrate3x-state-live-failed", text, "Nothing was analysed")
            assertTextHas("migrate3x-try-again", "Try again")
            assertTextHas("migrate3x-choose-file", "Choose definitions file")
            onNodeWithTag("migrate3x-try-again").performClick()
            onNodeWithTag("migrate3x-choose-file").performClick()
            assertEquals(listOf("analyseLive", "chooseFile"), calls.events, text)
        }
    }

    // ---- the locked source card ----

    @Test
    fun `a live report shows the connection, the pill Live, the lock with read only, Analyse again and Choose another file`() = runDesktopComposeUiTest(1280, 880) {
        val calls = MigrateCalls()
        show(liveReport(), calls, picker = liveSelected)
        assertDrawn("migrate3x-state-report")
        assertTextHas("migrate3x-source-locked", "read only")
        assertTextHas("migrate3x-state-report", "legacy-eu", "Live")
        assertTextHas("migrate3x-analyse-again", "Analyse again")
        assertTextHas("migrate3x-choose-file", "Choose another file")
        onNodeWithTag("migrate3x-analyse-again").performClick()
        assertEquals(listOf("analyseLive"), calls.events)
    }

    @Test
    fun `after the connection went away the card says from name (disconnected), stays locked, and offers no Analyse again`() = runDesktopComposeUiTest(1280, 880) {
        show(liveReport(connected = null), picker = liveDisabled)
        assertTextHas("migrate3x-source-locked", "read only")
        assertTextHas("migrate3x-state-report", "from legacy-eu (disconnected)")
        assertNotDrawn("migrate3x-analyse-again")
        assertDrawn("migrate3x-choose-file")
        assertDrawn("migrate3x-export")
    }

    @Test
    fun `a file report is not locked and has no Analyse again`() = runDesktopComposeUiTest(1280, 880) {
        show(MigrateScreenFiles.report(), picker = liveOffered)
        assertNotDrawn("migrate3x-source-locked")
        assertNotDrawn("migrate3x-analyse-again")
        assertTextHas("migrate3x-state-report", "legacy.json", "File")
    }

    @Test
    fun `Analyse again is reachable from the keyboard`() = runDesktopComposeUiTest(1280, 880) {
        val calls = MigrateCalls()
        show(liveReport(), calls, picker = liveSelected)
        onNodeWithTag("migrate3x-analyse-again").canTakeFocus()
        onNodeWithTag("migrate3x-analyse-again").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("migrate3x-analyse-again").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("analyseLive"), calls.events)
    }

    // ---- safety: nothing the broker returned reaches the screen ----

    private val password = "SENTINEL-PASSWORD-ui-4711"
    private val url = "https://svc:SENTINEL-URL-PW@legacy-eu.example.com:15672/api/definitions"
    private val token = "SENTINEL-TOKEN-bearer"

    @Test
    fun `no outcome of a live read puts a password, a URL or a token into any node of the screen`() {
        val outcomes = listOf<FakeThreeXReads.() -> Unit>(
            { definitions = ReadOutcome.Failed("HTTP 500 $url body=$password $token") },
            { definitions = ReadOutcome.NotPermitted },
            { definitions = ReadOutcome.Unreachable },
            { definitions = ReadOutcome.Ok("<html>$url $password $token</html>") },
            {
                definitions = ReadOutcome.Ok(
                    DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))).replaceFirst("\"queues\"", "\"users\":[{\"name\":\"svc\",\"password_hash\":\"$password\",\"tags\":\"administrator\"}],\"queues\""),
                )
            },
        )
        for (configure in outcomes) runDesktopComposeUiTest(1280, 880) {
            val reads = FakeThreeXReads().apply(configure)
            val live = FakeLiveSourceProvider(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { })
            val vm = MigrateFromThreeViewModel(
                FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), AnalysisHolder(), FakeMigrationFolders().ports,
                scope = CoroutineScope(Dispatchers.Unconfined), analysisDispatcher = Dispatchers.Unconfined, liveSource = live,
            )
            vm.chooseSource(SourceKind.Live)
            vm.analyseLive()
            val state = vm.state.value
            assertTrue(state !is MigrateFromThreeState.ChooseSource && state !is MigrateFromThreeState.LoadingLive, "the live read did not end: $state")
            show(state, picker = vm.picker.value)
            waitForIdle()
            val screen = wholeScreenText()
            listOf(password, url, token, "SENTINEL").forEach { assertFalse(screen.contains(it), "the screen shows $it in state $state: $screen") }
        }
    }
}
