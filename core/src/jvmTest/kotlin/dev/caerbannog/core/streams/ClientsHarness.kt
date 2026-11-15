package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.consumer
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.publisher
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * M2-06a step 2: shared set-up of the [StreamClientsViewModel] tests (same style as [StreamListHarness]: fakes only, VIRTUAL time, the
 * view model gets the test's `backgroundScope`). The stream is "orders-events" on "uat-eu": first offset 0, 48300 messages, so the last
 * offset is 48299 (the Background of scenario file 04).
 *
 * CONTRACT USED (test developer; compile stub in `StreamClientsViewModel.kt` and `StreamClientsState.kt`; brief docs/tasks/M2-06-publishers-consumers.md):
 *   StreamClientsViewModel(active, settings, scope, clock, reconnect) : AutoCloseable, RefreshTarget
 *   fun show(stream) / hide() / refreshNow() / retry() / close(); val state: StateFlow<StreamClientsState>; val updatedAtMillis: StateFlow<Long?>
 *   All screens, texts and row fields as in StreamClientsState.kt. Per load: capabilities (once per source), streamDetail, firstOffset, and publishers + consumers
 *   only when the plugin is not MISSING and visibility is not NONE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientsHarness(val test: TestScope, refresh: RefreshInterval = DEFAULT_REFRESH) {
    companion object {
        const val STREAM = "orders-events"
        const val LAST = 48299L
    }

    val h = Harness()
    val catalog: FakeStreamCatalog = h.gateway.catalogOf("uat-eu")
    val offsets: FakeStreamOffsets = h.gateway.offsetsOf("uat-eu")
    val prodCatalog: FakeStreamCatalog = h.gateway.catalogOf("prod-eu")
    val prodOffsets: FakeStreamOffsets = h.gateway.offsetsOf("prod-eu")
    val settings = FakeAppSettings(refresh)
    val reconnects = mutableListOf<String>()

    val vm = StreamClientsViewModel(
        active = h.active,
        settings = settings,
        scope = test.backgroundScope,
        clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { name ->
            reconnects += name
            h.active.connect(if (name == "prod-eu") h.prod else h.uat)
        },
    )

    val state: StreamClientsState get() = vm.state.value

    init {
        addStream(catalog, offsets)
    }

    fun addStream(on: FakeStreamCatalog, off: FakeStreamOffsets, name: String = STREAM, messages: Long? = LAST + 1, first: Long? = 0) {
        on.addStream(stream(name, messages = messages))
        if (first != null) off.setFirst("/", name, first)
    }

    fun settle() = test.runCurrent()

    fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    suspend fun connect() {
        h.active.connect(h.uat)
        settle()
    }

    /** Connected and the tab shown for [STREAM]: the first load is done unless a call is held. */
    suspend fun open(stream: String = STREAM) {
        connect()
        vm.show(stream)
        settle()
    }

    fun consumers(vararg rows: dev.caerbannog.core.streams.ConsumerRow) = catalog.setConsumers("/", STREAM, rows.toList())
    fun publishers(vararg rows: dev.caerbannog.core.streams.PublisherRow) = catalog.setPublishers("/", STREAM, rows.toList())

    fun consumerAt(name: String?, offset: Long, lag: Long? = LAST - offset, connection: String = "10.0.5.14:38812 -> 10.0.0.1:5552", id: Int = 0, active: Boolean = true) =
        consumer(name, offset, LAST, lag, connection, subscriptionId = id, active = active)

    fun count(call: String): Int = synchronized(catalog.calls) { catalog.calls.count { it == call } }
    fun clientCalls(): Int = count("publishers") + count("consumers")
    fun allCalls(): Int = catalog.calls.size + offsets.calls.size

    @Suppress("unused")
    fun publisherRow(ref: String?, published: Long = 10, connection: String = "10.0.4.21:51234 -> 10.0.0.1:5552", id: Int = 0) = publisher(ref, published, connectionName = connection, publisherId = id)
}
