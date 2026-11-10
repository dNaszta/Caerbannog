package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.SuspendingDefinitionsFileLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-06a: every state and transition of the screen's view model, in virtual time. Fakes for loader, chooser and saver; no broker exists. */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrateFromThreeViewModelTest {
    private val path = "/data/legacy.json"
    private val good = FileLoadResult.Loaded(ScreenFiles.mixed)

    private class Rig(
        val test: TestScope,
        val loader: DefinitionsFileLoader,
        val chooser: FakeDefinitionsFileChooser = FakeDefinitionsFileChooser(),
        val saver: FakeReportFileSaver = FakeReportFileSaver(),
        val holder: AnalysisHolder = AnalysisHolder(),
    ) {
        fun vm(): MigrateFromThreeViewModel =
            MigrateFromThreeViewModel(loader, chooser, saver, holder, CoroutineScope(StandardTestDispatcher(test.testScheduler)), StandardTestDispatcher(test.testScheduler))
    }

    private fun TestScope.rig(files: Map<String, FileLoadResult> = mapOf(path to good)) = Rig(this, FakeDefinitionsFileLoader(files))

    private fun MigrateFromThreeViewModel.report(): MigrateFromThreeState.Report = assertIs(state.value, "state was ${state.value}")

    @Test
    fun `starts at ChooseSource and reads nothing`() = runTest {
        val r = rig(); val vm = r.vm(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertEquals(emptyList(), (r.loader as FakeDefinitionsFileLoader).requestedPaths)
    }

    @Test
    fun `choosing a file opens it, and a cancelled dialog changes nothing`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.chooseFile(); advanceUntilIdle()
        assertEquals(1, r.chooser.calls)
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "null from the dialog means cancelled")
        r.chooser.next = path
        vm.chooseFile(); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
        assertEquals(listOf(path), (r.loader as FakeDefinitionsFileLoader).requestedPaths)
    }

    @Test
    fun `open shows Loading with the file name at once, then the report, and holds the analysis`() = runTest {
        val loader = SuspendingDefinitionsFileLoader()
        val r = Rig(this, loader); val vm = r.vm()
        vm.open(path)
        assertEquals(MigrateFromThreeState.Loading("legacy.json"), vm.state.value, "Loading is set before the loader answers")
        runCurrent()
        assertEquals(MigrateFromThreeState.Loading("legacy.json"), vm.state.value)
        loader.finish(path, good); advanceUntilIdle()
        val view = vm.report().view
        assertEquals("legacy.json", view.fileLabel)
        assertEquals("3.13.7", view.sourceVersion)
        assertNull(vm.report().notice)
        assertEquals("legacy.json", r.holder.held.value?.fileLabel)
    }

    @Test
    fun `a Windows path gives the file name as label`() = runTest {
        val r = rig(mapOf("C:\\Users\\a\\legacy.json" to good)); val vm = r.vm()
        vm.open("C:\\Users\\a\\legacy.json"); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
    }

    @Test
    fun `a rejected file shows a fixed text and no report, and nothing is held`() = runTest {
        val cases = mapOf(
            "not json SENTINEL-HASH-secret" to "The file is not valid JSON",
            "[]" to "The file is not a definitions file",
            "{}" to "The file has no definitions",
        )
        for ((content, text) in cases) {
            val r = rig(mapOf(path to FileLoadResult.Loaded(content))); val vm = r.vm()
            vm.open(path); advanceUntilIdle()
            assertEquals(MigrateFromThreeState.Rejected("legacy.json", text), vm.state.value, "for $content")
            assertNull(r.holder.held.value)
        }
    }

    @Test
    fun `a file that is too large or cannot be read shows its fixed text, never the loader's reason`() = runTest {
        val large = rig(mapOf(path to FileLoadResult.TooLarge(100L * 1024 * 1024, MAX_DEFINITIONS_FILE_BYTES))); val vl = large.vm()
        vl.open(path); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.Rejected("legacy.json", "The file is larger than 64 MB"), vl.state.value)
        val unreadable = rig(mapOf(path to FileLoadResult.Unreadable("EACCES /home/secret/SENTINEL-PASSWORD"))); val vu = unreadable.vm()
        vu.open(path); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.Rejected("legacy.json", "The file could not be read"), vu.state.value)
    }

    @Test
    fun `from Rejected the user can choose another file`() = runTest {
        val r = rig(mapOf(path to FileLoadResult.Loaded("[]"), "/data/ok.json" to good)); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        assertIs<MigrateFromThreeState.Rejected>(vm.state.value)
        r.chooser.next = "/data/ok.json"
        vm.chooseFile(); advanceUntilIdle()
        assertEquals("ok.json", vm.report().view.fileLabel)
    }

    private val vhostFile = "/data/vhost.json"
    private fun TestScope.vhostRig() = rig(mapOf(vhostFile to FileLoadResult.Loaded(DefinitionsFixtures.read(DefinitionsFixtures.VHOST_V1))))

    @Test
    fun `a per-virtual-host file asks for the name, analyses nothing and starts without an error`() = runTest {
        val r = vhostRig(); val vm = r.vm()
        vm.open(vhostFile); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null), vm.state.value)
        assertNull(r.holder.held.value, "nothing is analysed or held before a name is given")
    }

    @Test
    fun `the virtual host name is trimmed before it is assigned`() = runTest {
        val r = vhostRig(); val vm = r.vm()
        vm.open(vhostFile); advanceUntilIdle()
        vm.giveVirtualHost("  orders  "); advanceUntilIdle()
        val view = vm.report().view
        assertEquals("orders", view.singleVirtualHost)
        assertEquals("The file holds no users, virtual hosts or permissions, so the access migration is empty.", view.accessNote)
        assertEquals("orders", r.holder.held.value?.document?.queues?.single()?.vhost)
    }

    @Test
    fun `a refused name shows the core reason as returned and keeps the prompt, then another name works`() = runTest {
        val r = vhostRig(); val vm = r.vm()
        vm.open(vhostFile); advanceUntilIdle()
        vm.giveVirtualHost("   "); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.NeedsVirtualHost("vhost.json", "The virtual host name must not be empty"), vm.state.value)
        vm.giveVirtualHost("a\tb"); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.NeedsVirtualHost("vhost.json", "The virtual host name must not contain control characters"), vm.state.value)
        assertNull(r.holder.held.value)
        vm.giveVirtualHost("orders"); advanceUntilIdle()
        assertEquals("orders", vm.report().view.singleVirtualHost)
    }

    @Test
    fun `cancelling the prompt goes back to ChooseSource and holds nothing`() = runTest {
        val r = vhostRig(); val vm = r.vm()
        vm.open(vhostFile); advanceUntilIdle()
        assertIs<MigrateFromThreeState.NeedsVirtualHost>(vm.state.value, "the premise: the prompt is shown")
        vm.cancelPrompt(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        vm.giveVirtualHost("orders"); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "a name given after Cancel is ignored")
        assertNull(r.holder.held.value)
    }

    @Test
    fun `cancel during Loading returns to ChooseSource and the late file is ignored`() = runTest {
        val loader = SuspendingDefinitionsFileLoader()
        val r = Rig(this, loader); val vm = r.vm()
        vm.open(path); runCurrent()
        assertEquals(MigrateFromThreeState.Loading("legacy.json"), vm.state.value, "the premise: a file is loading")
        vm.cancelLoading(); runCurrent()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        loader.finish(path, good); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "the late result must not bring a report back")
        assertNull(r.holder.held.value)
    }

    @Test
    fun `cancelPrompt outside the virtual host prompt does nothing`() = runTest {
        val loader = SuspendingDefinitionsFileLoader()
        val r = Rig(this, loader); val vm = r.vm()
        vm.cancelPrompt(); runCurrent()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        vm.open(path); runCurrent()
        vm.cancelPrompt(); runCurrent()
        assertEquals(MigrateFromThreeState.Loading("legacy.json"), vm.state.value, "Loading is not cancelled by cancelPrompt")
        loader.finish(path, good); advanceUntilIdle()
        vm.cancelPrompt(); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel, "a shown report is not touched")
        assertNotNull(r.holder.held.value)
    }

    @Test
    fun `cancelLoading outside Loading does nothing`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        vm.cancelLoading(); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
        assertNotNull(r.holder.held.value)
    }

    @Test
    fun `cancelLoading while a second file loads keeps the held report of the first`() = runTest {
        val loader = SuspendingDefinitionsFileLoader()
        val r = Rig(this, loader); val vm = r.vm()
        vm.open(path); runCurrent(); loader.finish(path, good); advanceUntilIdle()
        vm.open("/data/second.json"); runCurrent()
        assertEquals(MigrateFromThreeState.Loading("second.json"), vm.state.value)
        vm.cancelLoading(); runCurrent()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertEquals("legacy.json", r.holder.held.value?.fileLabel, "the held report is not changed")
    }

    @Test
    fun `reportCopied shows the copied notice and a later export replaces it`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        vm.reportCopied(); advanceUntilIdle()
        assertEquals(ExportNotice("Report copied as text", failed = false), vm.report().notice)
    }

    @Test
    fun `a second file replaces the first, and a late answer for the first file is ignored`() = runTest {
        val loader = SuspendingDefinitionsFileLoader()
        val r = Rig(this, loader); val vm = r.vm()
        vm.open(path); runCurrent()
        vm.open("/data/second.json"); runCurrent()
        assertEquals(MigrateFromThreeState.Loading("second.json"), vm.state.value)
        loader.finish("/data/second.json", good); advanceUntilIdle()
        loader.finish(path, FileLoadResult.Loaded("[]")); advanceUntilIdle()
        assertEquals("second.json", vm.report().view.fileLabel, "the first file must not overwrite the second")
        assertEquals("second.json", r.holder.held.value?.fileLabel)
    }

    @Test
    fun `a second file after a report replaces the held analysis`() = runTest {
        val r = rig(mapOf(path to good, "/data/b.json" to good)); val vm = r.vm()
        vm.open(path); advanceUntilIdle(); vm.open("/data/b.json"); advanceUntilIdle()
        assertEquals("b.json", vm.report().view.fileLabel)
        assertEquals("b.json", r.holder.held.value?.fileLabel)
    }

    @Test
    fun `reset clears the report and the holder, and a prompt answered after reset is ignored`() = runTest {
        val r = vhostRig(); val vm = r.vm()
        vm.open(vhostFile); advanceUntilIdle()
        assertIs<MigrateFromThreeState.NeedsVirtualHost>(vm.state.value, "the premise: the prompt is shown")
        vm.reset(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        vm.giveVirtualHost("orders"); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertNull(r.holder.held.value)

        val r2 = rig(); val vm2 = r2.vm()
        vm2.open(path); advanceUntilIdle()
        assertNotNull(r2.holder.held.value, "the premise: a report is held")
        vm2.reset(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm2.state.value)
        assertNull(r2.holder.held.value)
    }

    @Test
    fun `the report survives a new view model over the same holder, as after a connection change`() = runTest {
        val r = rig(); val first = r.vm()
        first.open(path); advanceUntilIdle()
        val second = r.vm()
        advanceUntilIdle()
        assertEquals("legacy.json", second.report().view.fileLabel, "a view model built later shows the held report")
        assertEquals(first.report().view.summary, second.report().view.summary)
        second.reset()
        assertEquals(MigrateFromThreeState.ChooseSource, r.vm().state.value, "only reset drops the held analysis")
    }

    @Test
    fun `severity chips filter the groups, keep the counts, and toggle back`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        val all = vm.report().view
        vm.toggleSeverity(Severity.Warning); vm.toggleSeverity(Severity.Info); advanceUntilIdle()
        val view = vm.report().view
        assertEquals(listOf(true, false, false), view.chips.map { it.selected })
        assertEquals(all.chips.map { it.count }, view.chips.map { it.count })
        assertTrue(view.groups.filter { it.findingClass != FindingClass.MessageLoss }.all { it.severity == Severity.Blocker })
        assertNotNull(view.lossBlock, "the message-loss block stays")
        vm.toggleSeverity(Severity.Warning); vm.toggleSeverity(Severity.Info); advanceUntilIdle()
        assertEquals(all.groups.map { it.key }, vm.report().view.groups.map { it.key })
    }

    @Test
    fun `toggling a group opens and closes it and rows follow`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        val g = vm.report().view.groups.first { it.findingClass != FindingClass.MessageLoss }
        assertEquals(0, vm.report().view.rows(g.key).size)
        vm.toggleGroup(g.key); advanceUntilIdle()
        assertEquals(g.count, vm.report().view.rows(g.key).size)
        assertTrue(vm.report().view.groups.first { it.key == g.key }.open)
        vm.toggleGroup(g.key); advanceUntilIdle()
        assertEquals(0, vm.report().view.rows(g.key).size)
    }

    @Test
    fun `reportText is the text export of the whole report, whatever the filter`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        vm.toggleSeverity(Severity.Info); advanceUntilIdle()
        assertEquals(CompatibilityReportText.render(assertNotNull(r.holder.held.value, "a report is held").report), vm.reportText())
    }

    @Test
    fun `export hands the json export to the saver and shows where it went`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        r.saver.outcome = SaveOutcome.Saved("/home/me/report.json")
        vm.exportReportJson(); advanceUntilIdle()
        val save = r.saver.saves.single()
        assertTrue(save.suggestedName.endsWith(".json") && save.suggestedName.contains("legacy"), save.suggestedName)
        assertEquals(CompatibilityReportJson.encode(assertNotNull(r.holder.held.value, "a report is held").report), save.text)
        assertTrue(save.text.contains("\"schema\": \"caerbannog.compat-report/1\""))
        val notice = assertNotNull(vm.report().notice)
        assertEquals(false, notice.failed)
        assertTrue(notice.text.contains("/home/me/report.json"), notice.text)
        vm.dismissNotice(); advanceUntilIdle()
        assertNull(vm.report().notice)
    }

    @Test
    fun `a cancelled save shows no notice, a failed save says so with the fixed reason and keeps the report`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        r.saver.outcome = SaveOutcome.Cancelled
        vm.exportReportJson(); advanceUntilIdle()
        assertNull(vm.report().notice)
        r.saver.outcome = SaveOutcome.Failed("The place cannot be written")
        vm.exportReportJson(); advanceUntilIdle()
        val notice = assertNotNull(vm.report().notice)
        assertEquals(true, notice.failed)
        assertTrue(notice.text.startsWith("The report could not be saved") && notice.text.contains("The place cannot be written"), notice.text)
        assertEquals("legacy.json", vm.report().view.fileLabel, "the report is still shown")
    }

    @Test
    fun `export without a report asks nothing of the saver`() = runTest {
        val r = rig(); val vm = r.vm()
        vm.exportReportJson(); advanceUntilIdle()
        assertEquals(emptyList(), r.saver.saves)
    }

    @Test
    fun `60000 classic queues become groups with counts and no rows until a group is opened`() = runTest {
        val text = DefinitionsFiles.classicQueues(60_000)
        val r = rig(mapOf(path to FileLoadResult.Loaded(text))); val vm = r.vm()
        vm.open(path); advanceUntilIdle()
        val view = vm.report().view
        assertEquals(assertNotNull(r.holder.held.value, "a report is held").report.findings.size, view.groups.sumOf { it.count })
        assertTrue(view.groups.size < 20, "groups with counts, not one entry per finding: ${view.groups.size}")
        view.groups.filter { !it.open }.forEach { assertEquals(0, view.rows(it.key).size) }
        val loss = assertNotNull(view.lossBlock, "60,000 classic queues are at risk")
        assertEquals(60_000, loss.queueCount)
        assertEquals(10, loss.preview.size)
        assertEquals(loss.queueAt(59_999), "q-60000")
        val biggest = view.groups.maxBy { it.count }
        vm.toggleGroup(biggest.key); advanceUntilIdle()
        val rows = vm.report().view.rows(biggest.key)
        assertEquals(biggest.count, rows.size)
        rows.at(rows.size - 1)
    }
}
