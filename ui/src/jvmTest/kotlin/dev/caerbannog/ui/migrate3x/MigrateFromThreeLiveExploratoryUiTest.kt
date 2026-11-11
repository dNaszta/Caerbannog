package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertTrue

/** M8-06c step 4 exploratory: hostile connection names, narrow windows and the keyboard through the picker. Nothing may crash or draw outside the window. */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeLiveExploratoryUiTest {
    private val hostile = listOf(
        "legacy‮eu\u0000\n<b>x</b>\t\"quote\"",
        "very-long-connection-name-".repeat(12) + "end",
        "  ",
        "🐇 rabbit مرحبا",
    )

    private fun report(name: String, connected: String?): MigrateFromThreeState.Report {
        val file = MigrateScreenFiles.held()
        val held = HeldAnalysis(name, file.document, file.report, SourceKind.Live)
        return MigrateFromThreeState.Report(ReportViewBuilder.build(held, liveConnectionName = connected), null, ReviewState.NONE)
    }

    @Test
    fun `hostile connection names draw in every live state without a crash and inside a narrow window`() {
        for (name in hostile) for (width in listOf(1280, 400)) runDesktopComposeUiTest(width, 800) {
            val picker = SourcePicker(SourceKind.Live, listOf(SourceOption.File, SourceOption.Live(name)))
            listOf(
                MigrateFromThreeState.ChooseSource, MigrateFromThreeState.LoadingLive(name), MigrateFromThreeState.CannotExport(name, LiveSourceTexts.CANNOT_EXPORT),
                MigrateFromThreeState.LiveFailed(name, LiveSourceTexts.unreachable(name)), report(name, name), report(name, null),
            ).forEach { state ->
                show(state, picker = picker)
                waitForIdle()
                val bounds = onNodeWithTag(
                    when (state) {
                        is MigrateFromThreeState.ChooseSource -> "migrate3x-state-choose-source"
                        is MigrateFromThreeState.LoadingLive -> "migrate3x-state-loading-live"
                        is MigrateFromThreeState.CannotExport -> "migrate3x-state-cannot-export"
                        is MigrateFromThreeState.LiveFailed -> "migrate3x-state-live-failed"
                        else -> "migrate3x-state-report"
                    },
                ).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.right <= width + 1f, "width $width, name '${name.take(20)}', $state: the card is drawn past the window edge: $bounds")
            }
        }
    }

    @Test
    fun `Tab walks from the file option through the tips to the live option, the file button and the How to link, and Space on the live option selects it`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        val picker = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live("legacy-eu")))
        show(MigrateFromThreeState.ChooseSource, calls, picker = picker)
        onNodeWithTag("migrate3x-source-file").performSemanticsAction(SemanticsActions.RequestFocus)
        val stops = mutableListOf<String>()
        repeat(8) {
            onNodeWithTag("migrate3x-source-file", useUnmergedTree = true).performKeyInput { pressKey(Key.Tab) }; waitForIdle()
            val focused = onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.firstOrNull()
            if (focused != null) stops += focused
        }
        // M8-14a: every source option has a focusable tip, so Tab stops on the tips between the options. Whatever the exact order, all of them are reachable and the file option comes before the live one.
        listOf("migrate3x-source-live", "help-tip-m3x-3", "help-tip-m3x-4", "migrate3x-choose-file", "howto-analyse-generate").forEach { assertTrue(it in stops, "Tab never reached $it; stops: $stops") }
        assertTrue(stops.indexOf("help-tip-m3x-3") < stops.indexOf("migrate3x-source-live"), "the tip of the file option comes before the live option; stops: $stops")
        assertTrue(stops.indexOf("migrate3x-source-live") < stops.indexOf("help-tip-m3x-4"), "the live option comes before its tip; stops: $stops")
        assertTrue(stops.indexOf("help-tip-m3x-4") < stops.indexOf("migrate3x-choose-file"), "the tips come before the file button; stops: $stops")
        onNodeWithTag("migrate3x-source-live").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("migrate3x-source-live").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("chooseSource:Live"), calls.events, "Space on the live option selects it")
    }
}
