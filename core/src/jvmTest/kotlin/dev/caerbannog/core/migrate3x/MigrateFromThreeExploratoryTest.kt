package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-06a step 4: exploratory checks beyond the scenarios. No timing assertions; the measured times are in the test report. */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrateFromThreeExploratoryTest {
    @Test
    fun `240000 findings through the view model - open, toggle a group, filter, export - stay consistent`() = runTest {
        val base = ScreenFiles.held(DefinitionsFiles.classicQueues(2_000))
        val one = base.report.findings
        val findings = (0 until 240_000 / one.size + 1).flatMap { n -> one.map { it.copy(id = "${it.id}#$n") } }.take(240_000)
        val holder = AnalysisHolder().also { it.hold(HeldAnalysis("big.json", base.document, base.report.copy(findings = findings))) }
        val d = StandardTestDispatcher(testScheduler)
        val vm = MigrateFromThreeViewModel(FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), holder, CoroutineScope(d), d)
        advanceUntilIdle()
        val report = vm.state.value as MigrateFromThreeState.Report
        val key = report.view.groups.maxBy { it.count }.key
        vm.toggleGroup(key); vm.toggleSeverity(Severity.Info); vm.toggleSeverity(Severity.Info); advanceUntilIdle()
        val after = (vm.state.value as MigrateFromThreeState.Report).view
        assertEquals(240_000, after.groups.sumOf { it.count })
        assertEquals(after.groups.first { it.key == key }.count, after.rows(key).size)
        assertTrue(vm.reportText().length > 1_000_000, "the text export holds every finding")
    }

    @Test
    fun `names with bidi overrides, zero width characters, an emoji and a lone surrogate do not break a row or the loss copy text`() {
        val raw = "a‮b​c🐇\uD800z"
        val held = ScreenFiles.held(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(raw))))
        val loss = ReportViewBuilder.build(held).lossBlock!!
        assertEquals(1, loss.queueCount)
        assertEquals(loss.queueAt(0), loss.copyText)
        val first = ReportViewBuilder.build(held, openGroups = ReportViewBuilder.build(held).groups.map { it.key }.toSet())
        first.groups.forEach { g -> val rows = first.rows(g.key); (0 until rows.size).forEach { rows.at(it) } }
    }

    @Test
    fun `an empty definitions file has no loss block and only info groups (global notes)`() {
        val held = ScreenFiles.held(DefinitionsFiles.file())
        val view = ReportViewBuilder.build(held)
        assertTrue(view.groups.all { it.severity == Severity.Info }, "${view.groups}")
        assertEquals(null, view.lossBlock)
    }
}
