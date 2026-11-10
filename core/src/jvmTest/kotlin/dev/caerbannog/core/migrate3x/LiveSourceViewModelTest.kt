package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeLiveSourceProvider
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import dev.caerbannog.core.migrate3x.fakes.ScriptedDefinitionsSource
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M8-06c: the live source on the Migrate from 3.x screen at view-model level, in virtual time. The live port is a [FakeLiveSourceProvider] (a flow, like
 * the real one); the source is either a [ScriptedDefinitionsSource] (the test says when it answers) or the real `LiveDefinitionsSource` over `FakeThreeXReads`.
 * `liveSource` is passed by NAME on purpose: the brief puts it after `folders`, the stub has it last; the tests must not depend on the position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceViewModelTest {
    private val path = "/data/legacy.json"
    private val connection = "legacy-eu"

    private class Rig(
        val test: TestScope,
        val live: FakeLiveSourceProvider = FakeLiveSourceProvider(),
        val holder: AnalysisHolder = AnalysisHolder(),
        val chooser: FakeDefinitionsFileChooser = FakeDefinitionsFileChooser(),
        val loader: FakeDefinitionsFileLoader = FakeDefinitionsFileLoader(mapOf("/data/legacy.json" to FileLoadResult.Loaded(ScreenFiles.mixed))),
        val analysisDispatcher: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler),
    ) {
        fun vm(): MigrateFromThreeViewModel = MigrateFromThreeViewModel(
            loader, chooser, FakeReportFileSaver(), holder, FakeMigrationFolders().ports,
            scope = CoroutineScope(StandardTestDispatcher(test.testScheduler)),
            analysisDispatcher = analysisDispatcher,
            liveSource = live,
        )
    }

    private fun TestScope.rig(source: DefinitionsSource? = null) = Rig(this, FakeLiveSourceProvider(source))

    private fun fetched(name: String = connection): FetchResult.Fetched {
        val document = ScreenFiles.parse(ScreenFiles.mixed)
        return FetchResult.Fetched(document, LiveSourceFacts(name, "3.13.7"))
    }

    private fun MigrateFromThreeViewModel.report(): MigrateFromThreeState.Report = assertIs(state.value, "state was ${state.value}")

    // ---- the picker ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `without a live source the picker offers the file and a disabled live option with the reason`() = runTest {
        val vm = rig().vm(); advanceUntilIdle()
        assertEquals(
            SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE))),
            vm.picker.value,
        )
        assertEquals("Connect to a RabbitMQ 3.x broker to read it live", MigrateFromThreeTexts.LIVE_NOT_AVAILABLE)
        assertEquals(listOf("Definitions file", "Live connection"), vm.picker.value.options.map { it.label })
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "ChooseSource stays a data object")
    }

    @Test
    fun `with a live source the picker offers the file and the live connection by name, and the file stays selected`() = runTest {
        val vm = rig(ScriptedDefinitionsSource(connection)).vm(); advanceUntilIdle()
        assertEquals(SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live(connection))), vm.picker.value)
        assertEquals(listOf("Definitions file", "Live connection: legacy-eu"), vm.picker.value.options.map { it.label })
    }

    @Test
    fun `the picker follows the connection while the screen is open`() = runTest {
        val r = rig(); val vm = r.vm(); advanceUntilIdle()
        assertIs<SourceOption.LiveDisabled>(vm.picker.value.options.last())
        r.live.offer(ScriptedDefinitionsSource(connection)); advanceUntilIdle()
        assertEquals(SourceOption.Live(connection), vm.picker.value.options.last(), "the user connected from the chip popup")
        r.live.offer(ScriptedDefinitionsSource("other-eu")); advanceUntilIdle()
        assertEquals(SourceOption.Live("other-eu"), vm.picker.value.options.last(), "another 3.x connection replaces the name")
    }

    @Test
    fun `choosing live selects it, and a connection that vanishes turns the selection back into the file`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection)); val vm = r.vm(); advanceUntilIdle()
        vm.chooseSource(SourceKind.Live); advanceUntilIdle()
        assertEquals(SourceKind.Live, vm.picker.value.selected)
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        r.live.withdraw(); advanceUntilIdle()
        assertEquals(SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE))), vm.picker.value)
        r.live.offer(ScriptedDefinitionsSource(connection)); advanceUntilIdle()
        assertEquals(SourceKind.File, vm.picker.value.selected, "the connection comes back: the user has to choose live again")
    }

    @Test
    fun `choosing live is ignored while no live source is offered, choosing the file is always fine`() = runTest {
        val vm = rig().vm(); advanceUntilIdle()
        vm.chooseSource(SourceKind.Live); advanceUntilIdle()
        assertEquals(SourceKind.File, vm.picker.value.selected)
        vm.chooseSource(SourceKind.File); advanceUntilIdle()
        assertEquals(SourceKind.File, vm.picker.value.selected)
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
    }

    // ---- ChooseSource -> LoadingLive -> Report ---------------------------------------------------------------------------------------

    @Test
    fun `analyse live shows LoadingLive with the connection at once, then the locked report, and holds a live analysis`() = runTest {
        val source = ScriptedDefinitionsSource(connection)
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.chooseSource(SourceKind.Live)
        vm.analyseLive()
        assertEquals(MigrateFromThreeState.LoadingLive(connection), vm.state.value, "set before the read answers")
        runCurrent()
        assertEquals(1, source.fetches)
        assertEquals(MigrateFromThreeState.LoadingLive(connection), vm.state.value)
        assertNull(r.holder.held.value, "nothing is held before the answer")
        source.finish(fetched()); advanceUntilIdle()
        val held = assertNotNull(r.holder.held.value)
        assertEquals(SourceKind.Live, held.kind)
        assertEquals(connection, held.fileLabel)
        val card = vm.report().view.source
        assertEquals(SourceCardView(SourceKind.Live, connection, locked = true, lockText = "read only", canAnalyseAgain = true), card)
        assertEquals(connection, vm.report().view.fileLabel)
    }

    @Test
    fun `analyse live is ignored while no live source is offered, and while a live read runs`() = runTest {
        val r = rig(); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "the picker already shows why")

        val source = ScriptedDefinitionsSource(connection)
        r.live.offer(source); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        vm.analyseLive(); vm.analyseLive(); runCurrent()
        assertEquals(1, source.fetches, "a second request during LoadingLive starts no second read")
    }

    @Test
    fun `analyse live is accepted from a report, a failure and from the file states`() = runTest {
        val source = ScriptedDefinitionsSource(connection, fetched())
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.open(path); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
        vm.analyseLive(); advanceUntilIdle()
        assertEquals(connection, vm.report().view.fileLabel, "a file report is replaced by the live one")
        assertEquals(SourceKind.Live, r.holder.held.value?.kind)
    }

    @Test
    fun `a second analysis replaces the first and the decisions of the first`() = runTest {
        val source = ScriptedDefinitionsSource(connection, fetched())
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        val first = assertNotNull(r.holder.held.value)
        val item = first.review.items.first()
        assertEquals(DecisionResult.Decided, first.review.decide(item, ItemDecision.Skip))
        vm.analyseLive(); advanceUntilIdle()
        val second = assertNotNull(r.holder.held.value)
        assertNotSame(first, second)
        assertNull(second.review.decisionOf(item), "the decisions belong to the analysis they were made on")
        assertFalse(r.holder.saved.value)
    }

    @Test
    fun `an analysis shows the filters reset like a file`() = runTest {
        val source = ScriptedDefinitionsSource(connection, fetched())
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        vm.toggleSeverity(Severity.Info); advanceUntilIdle()
        assertFalse(vm.report().view.chips.first { it.severity == Severity.Info }.selected)
        vm.analyseLive(); advanceUntilIdle()
        assertTrue(vm.report().view.chips.all { it.selected }, "a new analysis starts with every severity shown")
    }

    // ---- the other outcomes -----------------------------------------------------------------------------------------------------------

    @Test
    fun `a user who may not export gets CannotExport, no report is held, and chooseFile then reads a file`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, FetchResult.NotPermitted(LiveSourceTexts.CANNOT_EXPORT))); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.CannotExport(connection, LiveSourceTexts.CANNOT_EXPORT), vm.state.value)
        assertEquals("This user cannot export definitions: the administrator tag is needed", LiveSourceTexts.CANNOT_EXPORT)
        assertNull(r.holder.held.value)
        r.chooser.next = path
        vm.chooseFile(); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
        assertEquals(SourceKind.File, r.holder.held.value?.kind)
        assertFalse(vm.report().view.source.locked)
    }

    @Test
    fun `an unreachable broker gives LiveFailed with the fixed text and no report`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, FetchResult.Unreachable)); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.LiveFailed(connection, "legacy-eu could not be reached"), vm.state.value)
        assertNull(r.holder.held.value)
    }

    @Test
    fun `a failed read gives LiveFailed with the fixed text of the source`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, FetchResult.Failed(LiveSourceTexts.READ_FAILED))); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.LiveFailed(connection, LiveSourceTexts.READ_FAILED), vm.state.value)
        assertNull(r.holder.held.value)
    }

    @Test
    fun `chooseSource leaves a refusal or a failure and returns to ChooseSource with the kind selected`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, FetchResult.Unreachable)); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertIs<MigrateFromThreeState.LiveFailed>(vm.state.value)
        vm.chooseSource(SourceKind.File); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)

        r.live.offer(ScriptedDefinitionsSource(connection, FetchResult.NotPermitted(LiveSourceTexts.CANNOT_EXPORT))); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertIs<MigrateFromThreeState.CannotExport>(vm.state.value)
        vm.chooseSource(SourceKind.Live); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertEquals(SourceKind.Live, vm.picker.value.selected)

        val files = Rig(this, loader = FakeDefinitionsFileLoader(mapOf(path to FileLoadResult.TooLarge(1, 1))))
        val fileVm = files.vm(); advanceUntilIdle()
        fileVm.open(path); advanceUntilIdle()
        assertIs<MigrateFromThreeState.Rejected>(fileVm.state.value)
        fileVm.chooseSource(SourceKind.File); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, fileVm.state.value)
    }

    @Test
    fun `chooseSource is ignored while loading, at the virtual host question and with a report on screen`() = runTest {
        val source = ScriptedDefinitionsSource(connection)
        val loader = dev.caerbannog.core.migrate3x.fakes.SuspendingDefinitionsFileLoader()
        val r = Rig(this, FakeLiveSourceProvider(source)); val vm = MigrateFromThreeViewModel(
            loader, r.chooser, FakeReportFileSaver(), r.holder, FakeMigrationFolders().ports,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)), analysisDispatcher = StandardTestDispatcher(testScheduler), liveSource = r.live,
        )
        advanceUntilIdle()
        // Loading (file)
        vm.open(path); runCurrent()
        val loading = vm.state.value
        assertIs<MigrateFromThreeState.Loading>(loading)
        vm.chooseSource(SourceKind.Live); runCurrent()
        assertEquals(loading, vm.state.value)
        assertEquals(SourceKind.File, vm.picker.value.selected, "an ignored event does not select")
        // NeedsVirtualHost
        loader.finish(path, FileLoadResult.Loaded(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders", vhost = null)), vhosts = null))); advanceUntilIdle()
        val prompt = vm.state.value
        assertIs<MigrateFromThreeState.NeedsVirtualHost>(prompt)
        vm.chooseSource(SourceKind.Live); runCurrent()
        assertEquals(prompt, vm.state.value)
        vm.cancelPrompt()
        // LoadingLive
        vm.analyseLive(); runCurrent()
        assertEquals(MigrateFromThreeState.LoadingLive(connection), vm.state.value)
        vm.chooseSource(SourceKind.File); runCurrent()
        assertEquals(MigrateFromThreeState.LoadingLive(connection), vm.state.value)
        source.finish(fetched()); advanceUntilIdle()
        // Report: the held report stays on screen (the popup entry does not change a report that is on screen)
        val report = vm.state.value
        assertIs<MigrateFromThreeState.Report>(report)
        vm.chooseSource(SourceKind.File); advanceUntilIdle()
        assertEquals(report, vm.state.value)
        vm.chooseSource(SourceKind.Live); advanceUntilIdle()
        assertEquals(report, vm.state.value)
    }

    // ---- cancel, reset and late results ------------------------------------------------------------------------------------------------

    @Test
    fun `cancel during LoadingLive goes back to ChooseSource and the late answer is dropped`() = runTest {
        val source = ScriptedDefinitionsSource(connection)
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        vm.cancelLoading()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        source.finish(fetched()); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value, "a late result may not appear")
        assertNull(r.holder.held.value)
    }

    @Test
    fun `reset during LoadingLive drops the late answer, and reset after a report clears it`() = runTest {
        val source = ScriptedDefinitionsSource(connection)
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        vm.reset()
        source.finish(fetched()); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertNull(r.holder.held.value)

        r.live.offer(ScriptedDefinitionsSource(connection, fetched())); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        assertNotNull(r.holder.held.value)
        vm.reset()
        assertEquals(MigrateFromThreeState.ChooseSource, vm.state.value)
        assertNull(r.holder.held.value)
    }

    @Test
    fun `a live answer that arrives after a newer file analysis is dropped`() = runTest {
        val source = ScriptedDefinitionsSource(connection)
        val r = rig(source); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        vm.open(path); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel)
        source.finish(fetched()); advanceUntilIdle()
        assertEquals("legacy.json", vm.report().view.fileLabel, "the live answer of the superseded job may not replace the file report")
        assertEquals(SourceKind.File, r.holder.held.value?.kind)
    }

    @Test
    fun `an answer for an analysis that was cancelled by a newer live analysis is dropped`() = runTest {
        val first = ScriptedDefinitionsSource(connection)
        val r = rig(first); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        vm.cancelLoading()
        val second = ScriptedDefinitionsSource("other-eu")
        r.live.offer(second); advanceUntilIdle()
        vm.analyseLive(); runCurrent()
        first.finish(fetched(connection)); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.LoadingLive("other-eu"), vm.state.value)
        second.finish(fetched("other-eu")); advanceUntilIdle()
        assertEquals("other-eu", vm.report().view.fileLabel)
    }

    // ---- F7 and the held analysis ----------------------------------------------------------------------------------------------------

    private class CountingDispatcher : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            block.run()
        }
    }

    @Test
    fun `the analysis of a live read runs on the injected dispatcher (F7)`() = runTest {
        val counting = CountingDispatcher()
        val reads = FakeThreeXReads().apply { definitions = ReadOutcome.Ok(ScreenFiles.mixed) }
        val source = LiveDefinitionsSource(connection, "3.13.7", reads) { }
        val r = Rig(this, FakeLiveSourceProvider(source), analysisDispatcher = counting); val vm = r.vm(); advanceUntilIdle()
        assertEquals(0, counting.dispatches.get())
        vm.analyseLive(); advanceUntilIdle()
        assertIs<MigrateFromThreeState.Report>(vm.state.value)
        assertTrue(counting.dispatches.get() >= 1, "the CPU work of the live analysis did not use the dispatcher the view model was given")
    }

    @Test
    fun `a new view model over the same holder shows the live report with the card of the connection`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, fetched())); val first = r.vm(); advanceUntilIdle()
        first.analyseLive(); advanceUntilIdle()
        val second = r.vm(); advanceUntilIdle()
        assertEquals(SourceKind.Live, second.report().view.source.kind)
        assertTrue(second.report().view.source.locked)
        assertTrue(second.report().view.source.canAnalyseAgain)
    }

    @Test
    fun `the card follows the connection, a vanished or other connection makes it 'from name (disconnected)' and no analyse again`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection, fetched())); val vm = r.vm(); advanceUntilIdle()
        vm.analyseLive(); advanceUntilIdle()
        val held = assertNotNull(r.holder.held.value)
        r.live.withdraw(); advanceUntilIdle()
        assertEquals(
            SourceCardView(SourceKind.Live, "from legacy-eu (disconnected)", locked = true, lockText = "read only", canAnalyseAgain = false),
            vm.report().view.source,
        )
        assertSame(held, r.holder.held.value, "the analysis is not cleared when the connection goes (D1)")
        assertEquals(connection, vm.report().view.fileLabel)
        r.live.offer(ScriptedDefinitionsSource("other-eu")); advanceUntilIdle()
        assertEquals("from legacy-eu (disconnected)", vm.report().view.source.label)
        assertFalse(vm.report().view.source.canAnalyseAgain)
        r.live.offer(ScriptedDefinitionsSource(connection)); advanceUntilIdle()
        assertEquals(connection, vm.report().view.source.label, "the same connection is back")
        assertTrue(vm.report().view.source.canAnalyseAgain)
    }

    @Test
    fun `a file report is never locked and has no analyse again`() = runTest {
        val r = rig(ScriptedDefinitionsSource(connection)); val vm = r.vm(); advanceUntilIdle()
        vm.open(path); advanceUntilIdle()
        assertEquals(SourceCardView(SourceKind.File, "legacy.json", locked = false, lockText = null, canAnalyseAgain = false), vm.report().view.source)
    }

    // ---- safety: the view model has no way to change a broker ----------------------------------------------------------------------------

    @Test
    fun `the live port is the read only interface and the view model takes no other broker type`() {
        val constructor = MigrateFromThreeViewModel::class.java.constructors.single { !it.isSynthetic }
        val names = constructor.parameterTypes.map { it.simpleName }
        assertTrue("LiveSourceProvider" in names, "the live port is LiveSourceProvider: $names")
        val allowed = setOf(
            "DefinitionsFileLoader", "DefinitionsFileChooser", "ReportFileSaver", "AnalysisHolder", "MigrationFolderPorts", "LiveSourceProvider",
            "CoroutineScope", "CoroutineDispatcher",
        )
        assertEquals(emptyList(), names.filter { it !in allowed && it != "int" }, "an unexpected constructor parameter: a gateway or a writer must not come in")
        val methods = LiveSourceProvider::class.java.methods.map { it.name }.toSet() + DefinitionsSource::class.java.methods.map { it.name }.toSet()
        val forbidden = Regex("(?i)^(post|put|delete|patch|create|write|purge|publish|send|set|update|remove)")
        assertEquals(emptySet(), methods.filter { forbidden.containsMatchIn(it) }.toSet(), "the live port has a write-like method")
        assertTrue(methods.containsAll(listOf("getCurrent", "fetch", "getConnectionName")), "premise: $methods")
    }
}
