package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * M2-05a step 2, finding F-2 of M2-04 (brief Scope item 2): NO public method of [StreamListViewModel] may race with the view
 * model's own scope, whichever thread calls it (the popup, a "Updated N s ago" timer, a window-close hook are callers besides
 * the Compose event handlers). Architect ruling: the mutating methods hop onto the scope (`scope.launch`), so they are safe from any thread.
 *
 * How the test works (real threads, no sleeps, no virtual time, because the point IS the threads):
 *  - the view model's scope is a SINGLE-THREAD dispatcher named "ui-sim" (what `Dispatchers.Main` is in the app);
 *  - a collector on `Dispatchers.Unconfined` watches `vm.state`: an Unconfined collector runs on the thread that PUBLISHES a new
 *    state, so every thread that renders is recorded. The rule: that thread is always "ui-sim", never a caller. (A view model that
 *    renders on the caller's thread has all its fields written from the caller: the race.)
 *  - every observed state must also be consistent (page inside its range, rows match the filter, rows are one sorted page,
 *    range text agrees): a torn state is a data race made visible;
 *  - `AppSettings.streamListRefresh = ...` is recorded with its thread too (it is the one write that leaves the view model).
 *  - Waiting is `StateFlow.first { condition }` with a timeout, never a sleep.
 *
 * Contract: public API of [StreamListViewModel] as of M2-04, unchanged. `updatedSecondsAgo()` is called from the background threads
 * too (it must not throw and must not read a half written value). If the developer moves the age into the state (`updatedAtMillis`,
 * the brief's preferred shape) and deletes `updatedSecondsAgo()`, the one call in [Rig.everyMethod] goes with it.
 * RED on the M2-04 code: it renders on the caller's thread.
 */
class StreamListViewModelThreadTest {

    // The coroutines debug agent renames a thread while it runs a coroutine ("ui-sim @coroutine#7"): compare the plain name.
    private companion object {
        fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
    }

    private val rigs = mutableListOf<Rig>()

    @AfterEach
    fun cleanUp() {
        rigs.forEach { it.shutdown() }
    }

    private fun rig(streams: Int = 30, pageSize: Int = 5): Rig = Rig(streams, pageSize).also { rigs += it }

    private class RecordingSettings : AppSettings {
        val writerThreads: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile
        var value: RefreshInterval = DEFAULT_REFRESH

        override var streamListRefresh: RefreshInterval
            get() = value
            set(v) {
                writerThreads += threadName()
                value = v
            }
    }

    private class Rig(streams: Int, pageSize: Int) {
        val h = Harness()
        val catalog = h.gateway.catalogOf("uat-eu")
        val offsets = h.gateway.offsetsOf("uat-eu")
        val settings = RecordingSettings()
        private val executor = Executors.newSingleThreadExecutor { Thread(it, UI).also { t -> t.isDaemon = true } }
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        private val watcher = CoroutineScope(Dispatchers.Unconfined)

        /** Name of the thread of every state published while [recording] is on; and every inconsistent state. */
        val renderThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inconsistent: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile
        var recording = false

        val vm = StreamListViewModel(
            active = h.active,
            settings = settings,
            scope = scope,
            clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { name -> h.active.connect(if (name == "prod-eu") h.prod else h.uat) },
            pageSize = pageSize,
        )

        init {
            for (i in 0 until streams) {
                val name = "s-%02d".format(i)
                catalog.addStream(stream(name, messages = 3))
                offsets.setFirst("/", name, 0)
            }
            watcher.launch {
                vm.state.collect { s ->
                    if (recording) {
                        renderThreads += threadName()
                        consistencyProblem(s)?.let { inconsistent += it }
                    }
                }
            }
            runBlocking { h.active.connect(h.uat) }
            onUi { vm.setShown(true) }
            await("the first page with offsets") { it.screen == StreamListScreen.Rows && it.rows.isNotEmpty() && it.rows.none { r -> r.firstOffset == StreamListTexts.LOADING } }
            recording = true
        }

        /** Runs [block] on the view model's own thread and waits for it (the correct caller). */
        fun onUi(block: () -> Unit) {
            executor.submit(block).get(20, TimeUnit.SECONDS)
        }

        /** Everything queued on the scope's thread before this call has run when it returns. */
        fun barrier() = onUi { }

        fun await(what: String, condition: (StreamListState) -> Boolean): StreamListState = try {
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
            runCatching { vm.close() }
            watcher.cancel()
            scope.cancel()
            executor.shutdownNow()
        }

        /** Every public method of the view model once, with arguments that move through the whole range. */
        fun everyMethod(r: Random) {
            vm.setShown(r.nextBoolean())
            vm.refreshNow()
            vm.retry()
            vm.setFilter(listOf("", "s-0", "S-1", "s-2", "zzz", "-")[r.nextInt(6)])
            vm.setSort(StreamSort.entries[r.nextInt(2)])
            vm.setPage(r.nextInt(12) - 3)
            vm.nextPage()
            vm.previousPage()
            vm.setPageSize(intArrayOf(0, 1, 3, 5, 7, 50)[r.nextInt(6)])
            vm.setRefreshInterval(RefreshInterval.entries[r.nextInt(RefreshInterval.entries.size)])
            vm.updatedSecondsAgo()
        }

        companion object {
            const val UI = "ui-sim"

            fun consistencyProblem(s: StreamListState): String? {
                val pages = maxOf(1, (s.totalStreams + s.pageSize - 1) / s.pageSize)
                if (s.pageSize < 1) return "page size ${s.pageSize} in $s"
                if (s.page !in 1..pages) return "page ${s.page} outside 1..$pages (total ${s.totalStreams}, size ${s.pageSize})"
                if (s.rows.size > s.pageSize) return "${s.rows.size} rows on a page of ${s.pageSize}"
                s.rows.firstOrNull { !it.name.contains(s.filter, ignoreCase = true) }?.let { return "row ${it.name} does not match the filter '${s.filter}'" }
                val names = s.rows.map { it.name }
                val expected = if (s.sort == StreamSort.NameAscending) names.sorted() else names.sortedDescending()
                if (names != expected) return "rows are not sorted $names for ${s.sort}"
                if (s.rows.isNotEmpty()) {
                    val start = (s.page - 1) * s.pageSize
                    val range = "${start + 1} to ${start + s.rows.size} of ${s.totalStreams}"
                    if (s.rangeText != range) return "range text '${s.rangeText}' but the page is '$range'"
                }
                return null
            }
        }
    }

    // --- per method: a call from a plain thread publishes on the view model's thread --------------------------

    private fun Rig.assertOnlyUiRendered(call: String) {
        barrier()
        val others = renderThreads.toList().filter { it != Rig.UI }
        assertTrue(others.isEmpty(), "$call from a plain thread rendered the state on $others; it must hop onto the scope thread '${Rig.UI}'")
        assertEquals(emptyList<String>(), inconsistent.toList())
    }

    @Test
    fun `setFilter from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setFilter("s-1") }
        r.await("the filter") { it.filter == "s-1" }
        r.assertOnlyUiRendered("setFilter")
    }

    @Test
    fun `setSort from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setSort(StreamSort.NameDescending) }
        r.await("the sort") { it.sort == StreamSort.NameDescending }
        r.assertOnlyUiRendered("setSort")
    }

    @Test
    fun `setKindFilter and toggleExpanded from a plain thread render on the scope thread (M2-07)`() {
        val r = rig()
        r.fromPlainThread { r.vm.setKindFilter(StreamKindFilter.SuperStreams) }
        r.await("the kind filter") { it.kindFilter == StreamKindFilter.SuperStreams }
        r.fromPlainThread { r.vm.toggleExpanded("whatever") }
        r.assertOnlyUiRendered("setKindFilter and toggleExpanded")
    }

    @Test
    fun `setPage from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setPage(3) }
        r.await("page 3") { it.page == 3 }
        r.assertOnlyUiRendered("setPage")
    }

    @Test
    fun `nextPage from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.nextPage() }
        r.await("page 2") { it.page == 2 }
        r.assertOnlyUiRendered("nextPage")
    }

    @Test
    fun `previousPage from a plain thread renders on the scope thread`() {
        val r = rig()
        r.onUi { r.vm.setPage(3) }
        r.await("page 3") { it.page == 3 }
        r.fromPlainThread { r.vm.previousPage() }
        r.await("page 2") { it.page == 2 }
        r.assertOnlyUiRendered("previousPage")
    }

    @Test
    fun `setPageSize from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setPageSize(10) }
        r.await("page size 10") { it.pageSize == 10 }
        r.assertOnlyUiRendered("setPageSize")
    }

    @Test
    fun `setRefreshInterval from a plain thread renders and writes the setting on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setRefreshInterval(RefreshInterval.Every30) }
        r.await("30 s") { it.refreshInterval == RefreshInterval.Every30 }
        r.assertOnlyUiRendered("setRefreshInterval")
        assertEquals(listOf(Rig.UI), r.settings.writerThreads.toList(), "AppSettings must be written from the scope thread (the file write of M2-05 must not race either)")
        assertEquals(RefreshInterval.Every30, r.settings.value)
    }

    @Test
    fun `setShown false from a plain thread stops the loads and renders nowhere else`() {
        val r = rig()
        r.fromPlainThread { r.vm.setShown(false) }
        r.barrier()
        val before = r.catalog.calls.size
        r.onUi { r.vm.refreshNow() }
        r.barrier()
        assertEquals(before, r.catalog.calls.size, "a hidden list must not call the broker, whichever thread hid it")
        r.assertOnlyUiRendered("setShown")
    }

    @Test
    fun `refreshNow and retry from a plain thread load on the scope thread only`() {
        val r = rig()
        r.catalog.addStream(stream("s-00", messages = 9)) // the new answer: the state changes only if the load really ran
        r.fromPlainThread { r.vm.refreshNow() }
        r.await("a second load") { it.rows.firstOrNull()?.messages == "9" }
        r.fromPlainThread { r.vm.retry() }
        r.barrier()
        r.assertOnlyUiRendered("refreshNow")
    }

    @Test
    fun `updatedSecondsAgo can be read from a plain thread`() {
        val r = rig()
        var seen: Long? = -1
        r.fromPlainThread { seen = r.vm.updatedSecondsAgo() }
        assertTrue(seen != null && seen!! >= 0, "the first load is done, so the age is known; was $seen")
    }

    // --- the stress test ------------------------------------------------------------------------------------------

    /**
     * 8 plain threads call every public method (but close) 150 times each, started together, with seeded random arguments.
     * Expected: no exception, only "ui-sim" renders, every observed state is consistent, the settings are written on "ui-sim", and
     * after the storm a deterministic last word from one thread settles into exactly that last word.
     */
    @Test
    fun `8 threads calling every public method leave a consistent state and no exception`() {
        val r = rig(streams = 40)
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "caller-$id") {
                val random = Random(1000L + id)
                start.await()
                try {
                    repeat(150) { r.everyMethod(random) }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }

        assertEquals(emptyList<Throwable>(), failures.toList(), "a call from a plain thread threw")
        r.barrier()

        // The last word, from one thread, after the storm: the screen must settle into exactly this.
        r.fromPlainThread {
            r.vm.setShown(true)
            r.vm.setRefreshInterval(RefreshInterval.Off)
            r.vm.setFilter("")
            r.vm.setSort(StreamSort.NameAscending)
            r.vm.setPageSize(5)
            r.vm.setPage(2)
        }
        val settled = r.await("the last word") {
            it.filter == "" && it.sort == StreamSort.NameAscending && it.pageSize == 5 && it.page == 2 &&
                it.screen == StreamListScreen.Rows && it.rows.size == 5 && it.refreshInterval == RefreshInterval.Off
        }
        assertEquals((5 until 10).map { "s-%02d".format(it) }, settled.rows.map { it.name }, "page 2 of 5 of the 40 sorted streams")
        r.barrier()

        assertEquals(emptyList<String>(), r.inconsistent.toList().take(5), "an observed state was inconsistent (a torn read of the view model's fields)")
        val others = r.renderThreads.toList().filter { it != Rig.UI }
        assertTrue(others.isEmpty(), "${others.size} states were published on caller threads (first: ${others.distinct().take(4)}); every public method must work on the scope thread '${Rig.UI}'")
        val writers = r.settings.writerThreads.toList().filter { it != Rig.UI }
        assertTrue(writers.isEmpty(), "AppSettings was written from caller threads ${writers.distinct().take(4)}; expected only '${Rig.UI}'")
    }

    @Test
    fun `8 threads closing at once, then every call is harmless and nothing reaches the broker`() {
        val r = rig()
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "closer-$id") {
                start.await()
                try { r.vm.close() } catch (t: Throwable) { failures += t }
            }
        }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList<Throwable>(), failures.toList(), "close() from several threads threw")
        r.barrier()
        val callsAfterClose = r.catalog.calls.size + r.offsets.calls.size

        r.fromPlainThread { r.everyMethod(Random(7)) }
        r.fromPlainThread { r.vm.setShown(true); r.vm.refreshNow(); r.vm.retry() }
        r.barrier()

        assertEquals(callsAfterClose, r.catalog.calls.size + r.offsets.calls.size, "a closed view model must not call the broker")
    }
}
