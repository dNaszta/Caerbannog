package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-02: the rule of `StreamListViewModelThreadTest` for [PlannerViewModel]: NO public method may race with the view model's own scope, whichever
 * thread calls it (Compose event handlers, a window-close hook, a timer). Every public method hops onto the scope (`scope.launch`).
 *
 * Real threads, no sleeps: the scope is a single thread named "ui-sim"; a collector on `Dispatchers.Unconfined` runs on the thread that publishes
 * each state, so every publishing thread is recorded (it must always be "ui-sim"); the draft store and the migrations folder record the thread
 * they are called on (a file write from a caller's thread is the race too); every observed state must be consistent (a torn state is a race made
 * visible). Waiting is `StateFlow.first { }` with a timeout.
 */
class PlannerViewModelThreadTest {
    private companion object {
        const val UI = "ui-sim"
        fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
    }

    private class ThreadRecordingStore(private val inner: FakePlanDraftStore) : dev.caerbannog.core.planner.PlanDraftStore by inner {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        override suspend fun load(): DraftLoad { threads += threadName(); return inner.load() }
        override suspend fun save(plan: TopologyPlan): Boolean { threads += threadName(); return inner.save(plan) }
        override suspend fun clear() { threads += threadName(); inner.clear() }
        override suspend fun moveAside(): DraftMoveResult { threads += threadName(); return inner.moveAside() }
    }

    private class Rig {
        val fakeStore = FakePlanDraftStore()
        val store = ThreadRecordingStore(fakeStore)
        val folders = FakeMigrationFolders(PlannerRig.FOLDER).also { it.folder(PlannerRig.FOLDER).allowExport().addMigration("0004") }
        val settings = FakeAppSettings()
        private val executor = Executors.newSingleThreadExecutor { Thread(it, UI).also { t -> t.isDaemon = true } }
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        private val watcher = CoroutineScope(Dispatchers.Unconfined)
        val renderThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inconsistent: MutableList<String> = Collections.synchronizedList(ArrayList())

        val vm = PlannerViewModel(store, folders.ports, settings, PlannerContext(), scope)

        init {
            watcher.launch {
                vm.state.collect { s ->
                    renderThreads += threadName()
                    consistencyProblem(s)?.let { inconsistent += it }
                }
            }
            await("the draft is loaded") { it.isLoaded }
        }

        fun onUi(block: () -> Unit) { executor.submit(block).get(20, TimeUnit.SECONDS) }
        fun barrier() = onUi { }

        fun await(what: String, condition: (PlannerState) -> Boolean): PlannerState = try {
            runBlocking { withTimeout(20_000) { vm.state.first(condition) } }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; the state is ${vm.state.value}")
        }

        fun fromPlainThread(name: String = "caller", block: () -> Unit) {
            var failure: Throwable? = null
            val t = thread(name = name) { try { block() } catch (e: Throwable) { failure = e } }
            t.join(20_000)
            failure?.let { throw AssertionError("a call from a plain thread threw $it", it) }
        }

        fun shutdown() {
            watcher.cancel()
            scope.cancel()
            executor.shutdownNow()
        }

        /** Every public method once, with arguments that move through the whole range (ids that exist, ids that do not). */
        fun everyMethod(r: Random) {
            vm.openWizard()
            vm.chooseKind(PlannerTexts.WIZARD_KINDS[r.nextInt(3)])
            vm.updateForm(listOf(QueueForm("q${r.nextInt(4)}"), ExchangeForm("e${r.nextInt(4)}"), BindingForm("e0", "q0", routingKey = "k"), QueueForm(""))[r.nextInt(4)])
            vm.next()
            vm.back()
            vm.next()
            vm.addToPlan()
            vm.cancelWizard()
            vm.editStep(r.nextInt(6))
            vm.removeStep(r.nextInt(6))
            vm.moveStepUp(r.nextInt(6))
            vm.moveStepDown(r.nextInt(6))
            vm.selectStep(r.nextInt(6))
            vm.setDescription(listOf("", "d", "Add orders")[r.nextInt(3)])
            vm.setVirtualHost(listOf("/", "/orders")[r.nextInt(2)])
            vm.setNamingStyle(NamingStyle.entries[r.nextInt(NamingStyle.entries.size)])
            vm.saveAsMigration()
            vm.dismiss()
            if (r.nextBoolean()) vm.confirm() else vm.cancelQuestion()
            vm.startNewPlan()
            vm.retryDraft()
        }

        fun consistencyProblem(s: PlannerState): String? {
            val ids = s.plan.steps.map { it.id }
            if (ids.size != ids.toSet().size) return "step ids repeat: $ids in $s"
            s.selectedStepId?.let { if (it !in ids) return "selected step $it is not in $ids" }
            (s.wizard as? WizardState.Details)?.editingStepId?.let { if (it !in ids) return "edited step $it is not in $ids" }
            (s.wizard as? WizardState.Summary)?.editingStepId?.let { if (it !in ids) return "edited step $it (summary) is not in $ids" }
            if (s.plan.steps.isEmpty() && s.saveGate == SaveGate.Enabled) return "Save is enabled for an empty plan"
            if (s.problems != PlanValidation.problems(s.plan)) return "problems do not match the plan in $s"
            return null
        }
    }

    private val rigs = mutableListOf<Rig>()

    @AfterTest
    fun cleanUp() = rigs.forEach { it.shutdown() }

    private fun rig(): Rig = Rig().also { rigs += it }

    private fun Rig.assertOnlyUiTouched(call: String) {
        barrier()
        val others = renderThreads.toList().filter { it != UI }
        assertTrue(others.isEmpty(), "$call from a plain thread rendered the state on $others; it must hop onto the scope thread '$UI'")
        val storeOthers = store.threads.toList().filter { it != UI }
        assertTrue(storeOthers.isEmpty(), "$call called the draft store on $storeOthers; the file write belongs on '$UI'")
        assertEquals(emptyList<String>(), inconsistent.toList())
    }

    @Test
    fun `the plan methods from a plain thread render and store on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.openWizard(); r.vm.chooseKind("Queue"); r.vm.updateForm(QueueForm("orders")); r.vm.next(); r.vm.addToPlan() }
        r.await("one step") { it.plan.steps.size == 1 }
        r.fromPlainThread { r.vm.setDescription("d"); r.vm.setNamingStyle(NamingStyle.SNAKE); r.vm.setVirtualHost("/orders") }
        r.await("the description") { it.description == "d" && it.plan.vhost == "/orders" }
        r.assertOnlyUiTouched("the wizard and the header")
    }

    @Test
    fun `setNamingStyle writes the setting on the scope thread's state and the value is the last one`() {
        val r = rig()
        r.fromPlainThread { r.vm.setNamingStyle(NamingStyle.CAMEL) }
        r.await("camelCase") { it.namingStyle == NamingStyle.CAMEL }
        assertEquals(NamingStyle.CAMEL, r.settings.plannerNamingStyle)
        r.assertOnlyUiTouched("setNamingStyle")
    }

    @Test
    fun `saveAsMigration from a plain thread writes the file and the draft on the scope thread`() {
        val r = rig()
        r.onUi { r.vm.openWizard(); r.vm.chooseKind("Queue"); r.vm.updateForm(QueueForm("orders")); r.vm.next(); r.vm.addToPlan() }
        r.fromPlainThread { r.vm.saveAsMigration() }
        r.await("the saved plan") { it.saved != null }
        r.assertOnlyUiTouched("saveAsMigration")
        assertEquals(listOf("0004", "0005"), r.folders.folder(PlannerRig.FOLDER).files.keys.toList())
    }

    @Test
    fun `dismiss and confirm from plain threads render on the scope thread`() {
        val r = rig()
        r.onUi { r.vm.openWizard(); r.vm.chooseKind("Queue"); r.vm.updateForm(QueueForm("orders")); r.vm.next(); r.vm.addToPlan() }
        r.fromPlainThread { r.vm.dismiss() }
        r.await("the question") { it.question != null }
        r.fromPlainThread { r.vm.confirm() }
        r.await("dismissed") { it.dismissed }
        r.assertOnlyUiTouched("dismiss")
    }

    @Test
    fun `8 threads calling every public method leave a consistent state and no exception`() {
        val r = rig()
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "caller-$id") {
                val random = Random(2000L + id)
                start.await()
                try { repeat(100) { r.everyMethod(random) } } catch (t: Throwable) { failures += t }
            }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }

        assertEquals(emptyList<Throwable>(), failures.toList(), "a call from a plain thread threw")
        r.barrier()

        // The last word, from one thread: the screen settles into exactly this.
        r.fromPlainThread {
            r.vm.cancelWizard()
            r.vm.cancelQuestion()
            r.vm.setDescription("")
            r.vm.dismiss()
            r.vm.confirm()
        }
        r.await("the settled state") { it.plan.steps.isEmpty() && it.description == "" && it.wizard == WizardState.Closed && it.question == null }
        r.assertOnlyUiTouched("the storm")
        assertTrue(!r.fakeStore.hasDraft, "after the last word nothing is left of the draft")
        val written = r.folders.folder(PlannerRig.FOLDER).written
        assertEquals(written.size, written.toSet().size, "a version was written twice: $written")
    }
}
