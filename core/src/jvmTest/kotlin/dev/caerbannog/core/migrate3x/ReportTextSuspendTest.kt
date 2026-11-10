package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M8-06e item 4: `reportText()` is `suspend`, renders on the analysis dispatcher (a text export of 240,000 findings must not run on the UI thread),
 * changes no state and does not use the report job. The analysis dispatcher here has its OWN scheduler, so a test can see that the call is waiting for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportTextSuspendTest {
    private class Rig(val test: TestScope, held: HeldAnalysis, val saver: ReportFileSaver = FakeReportFileSaver()) {
        val holder = AnalysisHolder().also { it.hold(held) }
        val analysis = StandardTestDispatcher()
        val main = StandardTestDispatcher(test.testScheduler)
        val vm = MigrateFromThreeViewModel(
            FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), saver, holder, FakeMigrationFolders().ports, CoroutineScope(main), analysis,
        )

        /** The view model's own start-up work (it follows the live source once) is done before a test looks at the state. */
        fun settle() { test.testScheduler.advanceUntilIdle(); analysis.scheduler.advanceUntilIdle() }
    }

    // The text of all 240,000 findings (hundreds of MB) does not fit the default test heap twice; 40,000 findings are enough to tell where it is rendered.
    private fun big(): HeldAnalysis = GroupingFixtures.bigPart(40_000)

    @Test
    fun `the text of a large report (40000 findings) is rendered on the analysis dispatcher, not before it runs`() = runTest {
        val held = big()
        val rig = Rig(this, held)
        val text = async { rig.vm.reportText() }
        runCurrent()
        assertFalse(text.isCompleted, "reportText returned before the analysis dispatcher ran: the text was rendered on the caller's thread")
        rig.analysis.scheduler.advanceUntilIdle()
        runCurrent()
        // no second render for the comparison: two 240,000-finding texts do not fit the default test heap (the text equality is checked on a small report below)
        assertTrue(text.await().length > 100_000, "the text export holds every finding")
    }

    @Test
    fun `a cancelled call leaves the state and the notice as they were`() = runTest {
        val rig = Rig(this, ScreenFiles.held())
        rig.settle()
        val before = rig.vm.state.value
        val text = async { rig.vm.reportText() }
        runCurrent()
        text.cancel()
        rig.analysis.scheduler.advanceUntilIdle()
        advanceUntilIdle()
        assertTrue(text.isCancelled, "the call was not cancelled")
        assertSame(before, rig.vm.state.value, "a cancelled reportText changed the state")
        assertNull((rig.vm.state.value as MigrateFromThreeState.Report).notice, "a cancelled reportText showed a notice")
    }

    @Test
    fun `reportText changes no state and shows no notice by itself`() = runTest {
        val rig = Rig(this, ScreenFiles.held())
        rig.settle()
        val before = rig.vm.state.value
        val text = async { rig.vm.reportText() }
        runCurrent(); rig.analysis.scheduler.advanceUntilIdle(); runCurrent()
        assertEquals(CompatibilityReportText.render(rig.holder.held.value!!.report), text.await())
        assertSame(before, rig.vm.state.value)
    }

    @Test
    fun `the held analysis is read inside the block - a reset before the dispatcher runs gives an empty text`() = runTest {
        val rig = Rig(this, ScreenFiles.held())
        val text = async { rig.vm.reportText() }
        runCurrent()
        rig.vm.reset()
        rig.analysis.scheduler.advanceUntilIdle(); runCurrent()
        assertEquals("", text.await(), "the held analysis must be read on the analysis dispatcher, after the reset")
    }

    @Test
    fun `a copy is not work of the report job - it does not cancel a running export and is not cancelled by it`() = runTest {
        val gate = CompletableDeferred<SaveOutcome>()
        val saver = ReportFileSaver { _, _ -> gate.await() }
        val rig = Rig(this, ScreenFiles.held(), saver)
        rig.vm.exportReportJson()
        rig.analysis.scheduler.advanceUntilIdle(); advanceUntilIdle() // the export is now waiting in the saver
        val text = async { rig.vm.reportText() }
        runCurrent(); rig.analysis.scheduler.advanceUntilIdle(); runCurrent()
        assertTrue(text.await().isNotEmpty(), "the copy finished while the export waits")
        gate.complete(SaveOutcome.Saved("/home/me/r.json"))
        advanceUntilIdle()
        val notice = assertNotNull((rig.vm.state.value as MigrateFromThreeState.Report).notice, "the export was cancelled by the copy")
        assertEquals("Report saved to /home/me/r.json", notice.text)
    }
}
