package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.consumer
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.publisher
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
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
 * M2-06a step 2: the F-2 test of M2-05 ([StreamListViewModelThreadTest]) for [StreamClientsViewModel]. No public method may race with
 * the view model's own scope, whichever thread calls it: each hops onto the scope (single thread "ui-sim"), so every state is published
 * on that thread and every observed state is consistent. Real threads, no sleeps, waits are `StateFlow.first` with a timeout.
 * Rows are tagged with their stream (`c-of-<stream>`), so a row of another stream in a state is a torn state made visible.
 * Invariants of an observed state: Content has no message and a stream; a screen without sections has a message and no rows (stale
 * rows after a lost connection keep Content); consumer keys are unique; every consumer row belongs to `state.stream`; a settling row
 * never shows a counter other than "updating".
 */
class StreamClientsViewModelThreadTest {
    private companion object {
        const val UI = "ui-sim"
        fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
    }

    private val rigs = mutableListOf<Rig>()

    @AfterEach
    fun cleanUp() {
        rigs.forEach { it.shutdown() }
    }

    private fun rig(): Rig = Rig().also { rigs += it }

    private class RecordingSettings : AppSettings {
        @Volatile
        var value: RefreshInterval = RefreshInterval.Every5
        override var streamListRefresh: RefreshInterval
            get() = value
            set(v) { value = v }
    }

    private class Rig {
        val h = Harness()
        val catalog = h.gateway.catalogOf("uat-eu")
        val offsets = h.gateway.offsetsOf("uat-eu")
        private val executor = Executors.newSingleThreadExecutor { Thread(it, UI).also { t -> t.isDaemon = true } }
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        private val watcher = CoroutineScope(Dispatchers.Unconfined)
        val renderThreads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inconsistent: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile
        var recording = false

        val vm = StreamClientsViewModel(
            active = h.active,
            settings = RecordingSettings(),
            scope = scope,
            clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { name -> h.active.connect(if (name == "prod-eu") h.prod else h.uat) },
        )

        init {
            for (name in listOf("s-00", "s-01", "s-02")) {
                catalog.addStream(stream(name, messages = 10))
                offsets.setFirst("/", name, 0)
                catalog.setConsumers("/", name, List(4) { consumer("c$it-of-$name".takeIf { _ -> it != 3 }, 5, 9, connectionName = if (it == 2) "" else "10.0.0.$it:1 -> 10.0.0.1:5552", subscriptionId = it) })
                catalog.setPublishers("/", name, List(3) { publisher("p$it", 3, connectionName = if (it == 1) "" else "10.0.1.$it:1 -> 10.0.0.1:5552", publisherId = it) })
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
            onUi { vm.show("s-00") }
            await("the first content") { it.screen == StreamClientsScreen.Content && it.consumers.isNotEmpty() }
            recording = true
        }

        fun onUi(block: () -> Unit) { executor.submit(block).get(20, TimeUnit.SECONDS) }
        fun barrier() = onUi { }

        fun await(what: String, condition: (StreamClientsState) -> Boolean): StreamClientsState = try {
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
            vm.show(listOf("s-00", "s-01", "s-02", "missing")[r.nextInt(4)])
            vm.hide()
            vm.show(listOf("s-00", "s-01", "s-02")[r.nextInt(3)])
            vm.refreshNow()
            vm.retry()
            vm.updatedAtMillis.value
        }

        companion object {
            fun consistencyProblem(s: StreamClientsState): String? {
                val sections = s.screen == StreamClientsScreen.Content
                if (sections && s.message != null) return "Content with a message: $s"
                if (!sections && s.message == null) return "screen ${s.screen} without a message"
                if (!sections && (s.publishers.isNotEmpty() || s.consumers.isNotEmpty())) return "screen ${s.screen} holds rows: $s"
                if (sections && s.stream.isEmpty()) return "Content without a stream"
                val keys = s.consumers.map { it.key }
                if (keys.toSet().size != keys.size) return "duplicate consumer keys $keys"
                s.consumers.firstOrNull { it.name != "unnamed" && !it.name.endsWith(s.stream) }?.let { return "consumer ${it.name} is not a row of ${s.stream}" }
                s.consumers.firstOrNull { it.connection == "unknown" && (it.offset != "updating" || it.behind != "updating" || it.status != ConsumerStatus.Updating) }?.let { return "settling consumer row shows values: $it" }
                s.publishers.firstOrNull { it.settling && (it.published != "updating" || it.connection != "unknown") }?.let { return "settling publisher row shows values: $it" }
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
    fun `show from a plain thread renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.show("s-01") }
        r.await("s-01") { it.stream == "s-01" && it.screen == StreamClientsScreen.Content && it.consumers.isNotEmpty() }
        r.assertOnlyUiRendered("show")
    }

    @Test
    fun `hide from a plain thread stops the loads and renders on the scope thread`() {
        val r = rig()
        r.fromPlainThread { r.vm.hide() }
        r.barrier()
        val before = r.catalog.calls.size
        r.onUi { r.vm.refreshNow() }
        r.barrier()
        assertEquals(before, r.catalog.calls.size, "a hidden tab must not call the broker, whichever thread hid it")
        r.assertOnlyUiRendered("hide")
    }

    @Test
    fun `refreshNow and retry from a plain thread load on the scope thread only`() {
        val r = rig()
        val before = r.catalog.calls.count { it == "consumers" }
        r.fromPlainThread { r.vm.refreshNow() }
        r.await("a second load") { r.catalog.calls.count { c -> c == "consumers" } > before }
        r.fromPlainThread { r.vm.retry() }
        r.assertOnlyUiRendered("refreshNow")
    }

    @Test
    fun `updatedAtMillis can be read from a plain thread`() {
        val r = rig()
        var seen: Long? = -1
        r.fromPlainThread { seen = r.vm.updatedAtMillis.value }
        assertTrue(seen != null && seen!! > 0, "the first load is done, so the age is known; was $seen")
    }

    @Test
    fun `8 threads calling every public method leave a consistent state and no exception`() {
        val r = rig()
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "caller-$id") {
                val random = Random(1000L + id)
                start.await()
                try { repeat(150) { r.everyMethod(random) } } catch (t: Throwable) { failures += t }
            }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }

        assertEquals(emptyList<Throwable>(), failures.toList(), "a call from a plain thread threw")
        r.barrier()

        r.fromPlainThread { r.vm.show("s-01") }
        val settled = r.await("the last word") { it.stream == "s-01" && it.screen == StreamClientsScreen.Content && it.consumers.size == 4 }
        assertEquals(4, settled.consumers.size)
        assertEquals(3, settled.publishers.size)
        r.barrier()

        assertEquals(emptyList<String>(), r.inconsistent.toList().take(5), "an observed state was inconsistent (a torn read of the view model's fields)")
        val others = r.renderThreads.toList().filter { it != UI }
        assertTrue(others.isEmpty(), "${others.size} states were published on caller threads (first: ${others.distinct().take(4)}); every public method must work on the scope thread '$UI'")
    }

    @Test
    fun `8 threads closing at once, then every call is harmless and nothing reaches the broker`() {
        val r = rig()
        val start = CountDownLatch(1)
        val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val threads = (0 until 8).map { id ->
            thread(name = "closer-$id") { start.await(); try { r.vm.close() } catch (t: Throwable) { failures += t } }
        }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList<Throwable>(), failures.toList(), "close() from several threads threw")
        r.barrier()
        val callsAfterClose = r.catalog.calls.size + r.offsets.calls.size

        r.fromPlainThread { r.everyMethod(Random(7)) }
        r.barrier()

        assertEquals(callsAfterClose, r.catalog.calls.size + r.offsets.calls.size, "a closed view model must not call the broker")
    }
}
