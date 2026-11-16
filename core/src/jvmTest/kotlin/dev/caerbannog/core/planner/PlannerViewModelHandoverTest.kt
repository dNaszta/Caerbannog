package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-03 step 4, round 2: the residual of finding F1, as a characterization (virtual clock, no window). When the window replaces the view
 * model (a connection or permission change), the new one reads the draft in its init. A description that the old one still holds back
 * (inside its 500 ms quiet time) is on disk only after the old one's `flush()` has run.
 *  - [raceHandOver] pins TODAY'S behaviour of the window's order (new view model created, then the old one flushed on dispose, each on its own
 *    scope): the new plan lacks the text, and a later step change of the new view model writes a draft without it. If the architect changes the
 *    wiring, this test is meant to change with it.
 *  - [orderedHandOver] shows the cheap remedy is sound: the old one's flush queued first on the SAME single-thread scope as the new one's
 *    init, so the new one reads the text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelHandoverTest {
    private class World(val test: TestScope) {
        val store = FakePlanDraftStore()
        val folders = FakeMigrationFolders("/work/migrations").also { it.folder("/work/migrations").allowExport().addMigration("0004") }
        val settings = FakeAppSettings()
        val scheduler = test.testScheduler
        fun scope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        fun vm(scope: CoroutineScope) = PlannerViewModel(store, folders.ports, settings, PlannerContext(), scope)
        fun run() = scheduler.runCurrent()
        fun addQueue(vm: PlannerViewModel, name: String) {
            vm.openWizard(); run(); vm.chooseKind("Queue"); run(); vm.updateForm(QueueForm(name)); run(); vm.next(); run(); vm.addToPlan(); run()
        }
    }

    private fun world(block: suspend World.() -> Unit) = runTest { World(this).block() }

    @Test
    fun raceHandOver() = world {
        val oldScope = scope(); val newScope = scope()
        val old = vm(oldScope); run()
        addQueue(old, "orders")
        old.setDescription("typed just before the switch"); run()
        scheduler.advanceTimeBy(100); run() // well inside the 500 ms quiet time: nothing on disk yet
        val new = vm(newScope) // the window builds the new view model first ...
        old.flush() // ... and the old one is flushed when its composition is disposed
        run()
        assertEquals("", new.state.value.description, "the new view model read the draft before the flush")
        assertTrue(store.rawText!!.contains("typed just before the switch"), "the flush did reach the file")
        addQueue(new, "invoices") // a plan change of the new view model writes ITS plan
        assertTrue(!store.rawText!!.contains("typed just before the switch"), "the later write kept the text: the finding no longer holds")
        oldScope.cancel(); newScope.cancel()
    }

    @Test
    fun orderedHandOver() = world {
        val shared = scope()
        val old = vm(shared); run()
        addQueue(old, "orders")
        old.setDescription("typed just before the switch"); run()
        scheduler.advanceTimeBy(100); run()
        old.flush() // queued first on the single scope ...
        val new = vm(shared) // ... so the new view model's init runs after it
        run()
        assertEquals("typed just before the switch", new.state.value.description)
        assertEquals(1, new.state.value.plan.steps.size)
        shared.cancel()
    }
}
