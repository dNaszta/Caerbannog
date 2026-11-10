package dev.caerbannog.ui.migrate3x

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-06e item 4: `reportText` is `suspend`. The copy button renders, copies to the clipboard, then tells the view model; a screen that is left before
 * the text is ready copies nothing and shows no notice.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeCopyTextUiTest {
    @Test
    fun `Copy as text renders the text, puts it on the clipboard and only then reports it copied`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        val clipboard = RecordingClipboard()
        showWith({ MigrateScreenFiles.report() }, calls, reportText = { calls.events += "render"; "TEXT OF THE REPORT" }, clipboard = clipboard)
        onNodeWithTag("migrate3x-copy-text").performClick()
        waitForIdle()
        assertEquals(listOf("render", "reportCopied"), calls.events, "the notice must come after the text was rendered")
        assertEquals(listOf<String?>("TEXT OF THE REPORT"), clipboard.copied)
    }

    @Test
    fun `a report text that never arrives shows no notice and copies nothing`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        val clipboard = RecordingClipboard()
        showWith({ MigrateScreenFiles.report() }, calls, reportText = { awaitCancellation() }, clipboard = clipboard)
        onNodeWithTag("migrate3x-copy-text").performClick()
        waitForIdle()
        assertTrue(calls.events.isEmpty(), "no notice before the text is ready, events: ${calls.events}")
        assertTrue(clipboard.copied.isEmpty(), "nothing is copied before the text is ready: ${clipboard.copied}")
    }

    @Test
    fun `leaving the report before the text is ready copies nothing and shows no notice`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        val clipboard = RecordingClipboard()
        val gate = CompletableDeferred<String>()
        val state = mutableStateOf<MigrateFromThreeState>(MigrateScreenFiles.report())
        showWith({ state.value }, calls, reportText = { gate.await() }, clipboard = clipboard)
        onNodeWithTag("migrate3x-copy-text").performClick()
        waitForIdle()
        state.value = MigrateFromThreeState.ChooseSource // a reset or another screen: ReportState leaves the composition and its scope is cancelled
        waitForIdle()
        gate.complete("TEXT OF THE REPORT")
        waitForIdle()
        assertTrue(clipboard.copied.isEmpty(), "the text was copied after the screen was left: ${clipboard.copied}")
        assertTrue(calls.events.isEmpty(), "a notice was reported after the screen was left: ${calls.events}")
    }
}
