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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9-03, follow-up 1 of the M9-02 review: the description field writes the draft after about 500 ms of quiet, not on every keystroke.
 * Virtual clock only (no sleep): the view model runs on a test scheduler. The text is in the state at once; only the DRAFT FILE is delayed.
 * A pending description is written by Save as migration, Dismiss, [PlannerViewModel.flush] (the screen calls it on leaving) and by any
 * other change of the plan (which writes the whole plan anyway). Nothing is written after the draft is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelDebounceTest {
    private class World(val test: TestScope) {
        val store = FakePlanDraftStore()
        val folders = FakeMigrationFolders("/work/migrations")
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val vm = PlannerViewModel(store, folders.ports, FakeAppSettings(), PlannerContext(), scope)

        init {
            folders.folder("/work/migrations").allowExport().addMigration("0004")
            test.testScheduler.runCurrent()
        }

        val state get() = vm.state.value
        fun run() = test.testScheduler.runCurrent()
        fun elapse(ms: Long) { test.testScheduler.advanceTimeBy(ms); test.testScheduler.runCurrent() }
        fun addQueue(name: String) {
            vm.openWizard(); run(); vm.chooseKind("Queue"); run(); vm.updateForm(QueueForm(name)); run(); vm.next(); run(); vm.addToPlan(); run()
        }
        fun close() = scope.cancel()
    }

    private fun runWorld(block: suspend World.() -> Unit) = runTest {
        val w = World(this)
        try { w.block() } finally { w.close() }
    }

    @Test
    fun `the default is about half a second`() = assertEquals(500L, PlannerViewModel.DESCRIPTION_DEBOUNCE_MILLIS)

    @Test
    fun `the text is in the state at once but the draft is not written yet`() = runWorld {
        vm.setDescription("Add orders"); run()
        assertEquals("Add orders", state.description)
        assertEquals(emptyList(), store.savedTexts)
        assertFalse(store.hasDraft)
    }

    @Test
    fun `the draft is written once the quiet time has passed, not a millisecond before`() = runWorld {
        vm.setDescription("Add orders"); run()
        elapse(499)
        assertEquals(0, store.savedTexts.size, "written after 499 ms")
        elapse(1)
        assertEquals(1, store.savedTexts.size, "not written after 500 ms")
        assertTrue(store.savedTexts.single().contains("Add orders"))
    }

    @Test
    fun `typing keeps pushing the write back and only the last text is written, once`() = runWorld {
        vm.setDescription("A"); run()
        elapse(300)
        vm.setDescription("Ad"); run()
        elapse(300)
        vm.setDescription("Add"); run()
        elapse(499)
        assertEquals(0, store.savedTexts.size, "written while typing")
        elapse(1)
        assertEquals(1, store.savedTexts.size)
        assertTrue(store.savedTexts.single().contains("\"Add\""), store.savedTexts.single())
    }

    @Test
    fun `typed and deleted again inside the window writes nothing and leaves no draft`() = runWorld {
        vm.setDescription("x"); run()
        vm.setDescription(""); run()
        elapse(2_000)
        assertEquals(emptyList(), store.savedTexts)
        assertFalse(store.hasDraft)
    }

    @Test
    fun `flush writes a pending description at once, and only once`() = runWorld {
        vm.setDescription("Add orders"); run()
        vm.flush(); run()
        assertEquals(1, store.savedTexts.size)
        elapse(5_000)
        assertEquals(1, store.savedTexts.size, "the timer wrote the same text again")
    }

    @Test
    fun `flush with nothing pending writes nothing`() = runWorld {
        vm.flush(); run()
        assertEquals(emptyList(), store.savedTexts)
        addQueue("orders")
        val before = store.savedTexts.size
        vm.flush(); run()
        assertEquals(before, store.savedTexts.size)
    }

    @Test
    fun `Save as migration writes the pending description into the file and leaves no draft behind`() = runWorld {
        addQueue("orders")
        vm.setDescription("Add orders"); run()
        vm.saveAsMigration(); run()
        val saved = assertNotNull(state.saved, "not saved: ${state.errorMessage}")
        assertTrue(folders.folder("/work/migrations").files.getValue(saved.version).rawJson.contains("Add orders"))
        assertFalse(store.hasDraft)
        elapse(5_000)
        assertFalse(store.hasDraft, "the debounce brought the draft back after Save")
    }

    @Test
    fun `Dismiss with a pending description leaves no draft, and the timer does not bring one back`() = runWorld {
        addQueue("orders")
        vm.setDescription("typing"); run()
        vm.dismiss(); run()
        vm.confirm(); run()
        assertTrue(state.dismissed)
        assertFalse(store.hasDraft)
        elapse(5_000)
        assertFalse(store.hasDraft, "the debounce brought the draft back after Dismiss")
    }

    @Test
    fun `Dismiss of a description-only plan discards it and nothing is written after`() = runWorld {
        vm.setDescription("only words"); run()
        vm.dismiss(); run()
        assertTrue(state.dismissed)
        elapse(5_000)
        assertFalse(store.hasDraft)
    }

    @Test
    fun `another change of the plan writes the whole plan at once, description included`() = runWorld {
        vm.setDescription("Add orders"); run()
        addQueue("orders")
        val last = store.savedTexts.last()
        assertTrue(last.contains("Add orders") && last.contains("orders"), last)
    }

    @Test
    fun `after a restart the draft holds the flushed text and not the pending one that never got written`() = runWorld {
        addQueue("orders")
        vm.setDescription("first"); run()
        vm.flush(); run()
        vm.setDescription("second, never flushed"); run()
        val reopened = PlannerViewModel(store, folders.ports, FakeAppSettings(), PlannerContext(), scope); run()
        assertEquals("first", reopened.state.value.description)
    }

    @Test
    fun `a refused draft write after the quiet time shows the message, and a later keystroke tries again`() = runWorld {
        store.refuseWrites = true
        vm.setDescription("a"); run()
        elapse(500)
        assertEquals(PlannerTexts.DRAFT_NOT_SAVED, state.errorMessage)
        store.refuseWrites = false
        vm.setDescription("ab"); run()
        elapse(500)
        assertTrue(store.hasDraft)
    }

    @Test
    fun `flush on a locked planner writes nothing, the unreadable file stays as it was`() = runWorld {
        val bytes = "{ not a plan"
        store.putRaw(bytes)
        val locked = PlannerViewModel(store, folders.ports, FakeAppSettings(), PlannerContext(), scope); run()
        locked.setDescription("x"); run()
        locked.flush(); run()
        elapse(5_000)
        assertEquals(bytes, store.rawText)
        assertEquals(emptyList(), store.savedTexts)
    }
}
