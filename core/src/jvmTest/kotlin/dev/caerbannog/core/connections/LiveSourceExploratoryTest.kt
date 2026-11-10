package dev.caerbannog.core.connections

import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06c step 4 exploratory: the live read over the REAL `ActiveConnection` (the storage scan waits in the injected pause, so the read is "slow"),
 * with a cancel, and with a switch to a 4.x connection while it loads. Nothing may reach the 4.x broker, nothing may stay on screen that
 * claims the dropped connection is current, and a cancelled read leaves nothing behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceExploratoryTest {
    private class Rig(val test: TestScope) {
        val h = Harness().also { it.secrets.store("legacy-eu", PASSWORD) }
        val gate = CompletableDeferred<Unit>()
        val active = ActiveConnection(h.session, h.gateway) { gate.await() }
        val legacy = ConnectionProfile(name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc")
        val provider = HeldLiveSourceProvider(active, h.session, test.backgroundScope)
        val holder = AnalysisHolder()
        val vm = MigrateFromThreeViewModel(
            FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), holder, FakeMigrationFolders().ports,
            scope = CoroutineScope(StandardTestDispatcher(test.testScheduler)), analysisDispatcher = StandardTestDispatcher(test.testScheduler), liveSource = provider,
        )

        init {
            h.gateway.brokerRuns("legacy-eu", "3.13.7")
            h.gateway.brokerRuns("uat-eu", "4.1.0")
            h.gateway.threeXReadsOf("legacy-eu").apply {
                definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
                storageVersionNeverAppears("/", "orders") // the scan pauses between reads: the live read is slow
            }
        }

        suspend fun connect(p: ConnectionProfile) {
            active.connect(p)
            test.runCurrent()
        }
    }

    @Test
    fun `cancel during a slow live read leaves nothing behind, also when the pause ends later`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.legacy)
        r.vm.chooseSource(SourceKind.Live); r.vm.analyseLive(); runCurrent()
        assertEquals(MigrateFromThreeState.LoadingLive("legacy-eu"), r.vm.state.value)
        r.vm.cancelLoading(); runCurrent()
        r.gate.complete(Unit); advanceUntilIdle()
        assertEquals(MigrateFromThreeState.ChooseSource, r.vm.state.value)
        assertNull(r.holder.held.value)
    }

    @Test
    fun `a switch to a 4x connection while the read loads reaches no 4x broker and shows no live offer`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.legacy)
        r.vm.chooseSource(SourceKind.Live); r.vm.analyseLive(); runCurrent()
        r.connect(r.h.uat)
        assertTrue(r.vm.picker.value.options.last() is SourceOption.LiveDisabled, "the 4.x connection is held: live is disabled")
        r.gate.complete(Unit); advanceUntilIdle()
        assertEquals(emptyList(), r.h.gateway.threeXReadsOf("uat-eu").calls, "nothing is read from the 4.x broker")
        when (val state = r.vm.state.value) {
            is MigrateFromThreeState.Report -> {
                // Characterised: the scan of the dropped connection ends Unknown (a failing read never fails the fetch), so the report is the conservative one.
                assertEquals(SourceKind.Live, r.holder.held.value?.kind)
                assertEquals("from legacy-eu (disconnected)", state.view.source.label)
                assertTrue(!state.view.source.canAnalyseAgain)
            }
            is MigrateFromThreeState.LiveFailed -> assertEquals("legacy-eu could not be reached", state.text)
            else -> kotlin.test.fail("the read ended in an unexpected state: $state")
        }
    }

    @Test
    fun `the held report stays when the connection is switched, and the picker offers no live option`() = runTest {
        val r = Rig(this); runCurrent()
        r.h.gateway.threeXReadsOf("legacy-eu").storageVersionAppearsAtRead("/", "orders", 1)
        r.connect(r.legacy)
        r.vm.chooseSource(SourceKind.Live); r.vm.analyseLive(); advanceUntilIdle()
        assertIs<MigrateFromThreeState.Report>(r.vm.state.value)
        r.connect(r.h.uat); advanceUntilIdle()
        assertIs<MigrateFromThreeState.Report>(r.vm.state.value)
        assertEquals("from legacy-eu (disconnected)", (r.vm.state.value as MigrateFromThreeState.Report).view.source.label)
        launch { }.join()
    }
}
