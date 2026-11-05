package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * M2-08b step 2, brief acceptance extra (3): NO public method of [TopologyViewModel] may race with the view model's own scope, whichever thread
 * calls it (copy of the rule of `StreamListViewModelThreadTest`, M2-05a F-2). Real threads, no sleeps, no virtual time:
 *  - the scope is a SINGLE-THREAD dispatcher named "ui-sim";
 *  - a collector on `Dispatchers.Unconfined` records the thread that PUBLISHES every state: it must always be "ui-sim";
 *  - every observed state must be consistent (page in range, rows one page, range text agrees, the rows of the tab only);
 *  - waiting is `StateFlow.first { condition }` with a timeout.
 * Contract: public API of [TopologyViewModel] as pinned in [TopologyHarness]. RED on the compile stub (it renders nothing).
 */
class TopologyViewModelThreadTest {

    private companion object {
        fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
        const val UI = "ui-sim"
    }

    private val rigs = mutableListOf<Rig>()

    @AfterEach
    fun cleanUp() {
        rigs.forEach { it.shutdown() }
    }

    private fun rig(queues: Int = 30, pageSize: Int = 5): Rig = Rig(queues, pageSize).also { rigs += it }

    private class Rig(queues: Int, pageSize: Int) {
        val h = Harness()
        val topology = h.gateway.topologyOf("uat-eu")
        val settings = FakeAppSettings(RefreshInterval.Every5)
        private val executor = Executors.newSingleThreadExecutor { Thread(it, UI).also { t -> t.isDaemon = true } }
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        private val watcher = CoroutineScope(Dispatchers.Unconfined)
        val renderThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inconsistent: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile
        var recording = false

        val vm = TopologyViewModel(
            active = h.active, settings = settings, scope = scope, clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { h.active.connect(h.uat) },
            context = MutableStateFlow(TopologyContext(h.uat, null)),
            pageSize = pageSize,
        )

        init {
            for (i in 0 until queues) topology.addQueue(quorum("q-%02d".format(i)))
            topology.declareVhost("billing")
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
            await("the first page") { it.screen == TopologyScreen.Ready && it.queues.isNotEmpty() }
            recording = true
        }

        fun onUi(block: () -> Unit) {
            executor.submit(block).get(20, TimeUnit.SECONDS)
        }

        fun barrier() = onUi { }

        fun await(what: String, condition: (TopologyState) -> Boolean): TopologyState = try {
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

        fun everyMethod(r: Random) {
            vm.setShown(r.nextBoolean())
            vm.refreshNow()
            vm.retry()
            vm.setTab(TopologyTab.entries[r.nextInt(3)])
            vm.setQueueFilter(QueueTypeFilter.entries[r.nextInt(2)])
            vm.setSearch(listOf("", "q-0", "Q-1", "zzz", "-")[r.nextInt(5)])
            vm.selectVhost(listOf("/", "billing", "nope")[r.nextInt(3)])
            vm.selectQueue(listOf("q-00", "q-07", null, "nope")[r.nextInt(4)])
            vm.setShowDefaultExchange(r.nextBoolean())
            vm.setPage(r.nextInt(12) - 3)
            vm.nextPage()
            vm.previousPage()
            vm.setPageSize(intArrayOf(0, 1, 3, 5, 7, 50)[r.nextInt(6)])
        }

        companion object {
            fun consistencyProblem(s: TopologyState): String? {
                if (s.pageSize < 1) return "page size ${s.pageSize}"
                val pages = maxOf(1, (s.total + s.pageSize - 1) / s.pageSize)
                if (s.page !in 1..pages) return "page ${s.page} outside 1..$pages (total ${s.total}, size ${s.pageSize})"
                val shown = when (s.tab) {
                    TopologyTab.Queues -> s.queues.size
                    TopologyTab.Exchanges -> s.exchanges.size
                    TopologyTab.Bindings -> s.bindings.size
                }
                if (shown > s.pageSize) return "$shown rows on a page of ${s.pageSize}"
                if (s.tab != TopologyTab.Queues && s.queues.isNotEmpty()) return "queue rows on the ${s.tab} tab"
                if (s.tab != TopologyTab.Exchanges && s.exchanges.isNotEmpty()) return "exchange rows on the ${s.tab} tab"
                if (s.tab != TopologyTab.Bindings && s.bindings.isNotEmpty()) return "binding rows on the ${s.tab} tab"
                if (s.tab == TopologyTab.Queues) s.queues.firstOrNull { !it.name.contains(s.search, ignoreCase = true) }?.let { return "row ${it.name} does not match '${s.search}'" }
                if (shown > 0) {
                    val start = (s.page - 1) * s.pageSize
                    val range = "${start + 1} to ${start + shown} of ${s.total}"
                    if (s.rangeText != range) return "range text '${s.rangeText}' but the page is '$range'"
                }
                if (s.vhost.isNotEmpty() && s.queues.any { it.name.startsWith("q-") } && s.vhost != "/") return "rows of / under ${s.vhost}"
                return null
            }
        }
    }

    private fun Rig.assertOnlyUiRendered(call: String) {
        barrier()
        val others = renderThreads.toList().filter { it != UI }
        assertTrue(others.isEmpty(), "$call from a plain thread rendered the state on $others; it must hop onto the scope thread '$UI'")
        assertEquals(emptyList<String>(), inconsistent.toList())
    }

    @Test
    fun `setTab from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setTab(TopologyTab.Exchanges) }
        r.await("the Exchanges tab") { it.tab == TopologyTab.Exchanges && it.exchanges.isNotEmpty() }
        r.assertOnlyUiRendered("setTab")
    }

    @Test
    fun `setSearch and setQueueFilter from a plain thread render on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.setSearch("q-1") }
        r.await("the search") { it.search == "q-1" }
        r.fromPlainThread { r.vm.setQueueFilter(QueueTypeFilter.OtherTypes) }
        r.await("the chip") { it.queueFilter == QueueTypeFilter.OtherTypes }
        r.assertOnlyUiRendered("setSearch and setQueueFilter")
    }

    @Test
    fun `selectVhost from a plain thread renders on the scope thread and never shows the old rows under the new vhost`() {
        val r = rig()
        r.topology.addQueue(quorum("inv", vhost = "billing"))
        r.fromPlainThread { r.vm.selectVhost("billing") }
        r.await("billing") { it.vhost == "billing" && it.queues.map { q -> q.name } == listOf("inv") }
        r.assertOnlyUiRendered("selectVhost")
    }

    @Test
    fun `selectQueue and setShowDefaultExchange from a plain thread render on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.selectQueue("q-00") }
        r.await("the detail") { it.selectedQueue?.name == "q-00" }
        r.fromPlainThread { r.vm.setShowDefaultExchange(true) }
        r.await("the switch") { it.showDefaultExchange }
        r.assertOnlyUiRendered("selectQueue and setShowDefaultExchange")
    }

    @Test
    fun `paging from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.nextPage() }
        r.await("page 2") { it.page == 2 }
        r.fromPlainThread { r.vm.setPage(3) }
        r.await("page 3") { it.page == 3 }
        r.fromPlainThread { r.vm.previousPage() }
        r.await("page 2 again") { it.page == 2 }
        r.fromPlainThread { r.vm.setPageSize(10) }
        r.await("page size 10") { it.pageSize == 10 }
        r.assertOnlyUiRendered("paging")
    }

    @Test
    fun `setShown false from a plain thread stops the loads`() {
        val r = rig()
        r.fromPlainThread { r.vm.setShown(false) }
        r.barrier()
        val before = r.topology.calls.size
        r.onUi { r.vm.refreshNow() }
        r.barrier()
        assertEquals(before, r.topology.calls.size, "a hidden screen must not call the broker, whichever thread hid it")
        r.assertOnlyUiRendered("setShown")
    }

    @Test
    fun `refreshNow and retry from a plain thread load on the scope thread only`() {
        val r = rig()
        r.topology.addQueue(quorum("q-00", ready = 9))
        r.fromPlainThread { r.vm.refreshNow() }
        r.await("a second load") { it.queues.firstOrNull()?.ready == "9" }
        r.fromPlainThread { r.vm.retry() }
        r.barrier()
        r.assertOnlyUiRendered("refreshNow")
    }

    @Test
    fun `8 threads calling every public method leave a consistent state and no exception`() {
        val r = rig(queues = 40)
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "caller-$id") {
                val random = Random(2000L + id)
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

        r.fromPlainThread {
            r.vm.setShown(true)
            r.vm.selectVhost("/")
            r.vm.setTab(TopologyTab.Queues)
            r.vm.setQueueFilter(QueueTypeFilter.Quorum)
            r.vm.setSearch("")
            r.vm.selectQueue(null)
            r.vm.setPageSize(5)
        }
        // A page asked for while the rows are still loading is clamped (nothing to page yet), so page 2 is a second, later word.
        r.await("the first page of the last word") {
            it.vhost == "/" && it.tab == TopologyTab.Queues && it.search == "" && it.pageSize == 5 && it.screen == TopologyScreen.Ready && it.total == 40
        }
        r.fromPlainThread { r.vm.setPage(2) }
        val settled = r.await("page 2") { it.page == 2 && it.queues.size == 5 }
        assertEquals((5 until 10).map { "q-%02d".format(it) }, settled.queues.map { it.name }, "page 2 of 5 of the 40 sorted queues")
        r.barrier()
        assertEquals(emptyList<String>(), r.inconsistent.toList().take(5), "an observed state was inconsistent (a torn read)")
        val others = r.renderThreads.toList().filter { it != UI }
        assertTrue(others.isEmpty(), "${others.size} states were published on caller threads (first: ${others.distinct().take(4)}); expected only '$UI'")
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
        val callsAfterClose = r.topology.calls.size

        r.fromPlainThread { r.everyMethod(Random(7)) }
        r.fromPlainThread { r.vm.setShown(true); r.vm.refreshNow(); r.vm.retry() }
        r.barrier()

        assertEquals(callsAfterClose, r.topology.calls.size, "a closed view model must not call the broker")
    }
}
