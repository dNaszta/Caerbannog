package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * M2-04 step 2: shared set-up of the stream list view model tests. Everything is a fake and runs in VIRTUAL time:
 * the view model gets the test's `backgroundScope` (so the endless timer and the state collector end with the test) and a
 * clock that reads the test scheduler. No sleeps anywhere.
 *
 * CONTRACT USED (test developer, see docs/tasks/M2-04-stream-list-core.md Status, step 2). Package `dev.caerbannog.core.streams`, commonMain:
 *
 *   fun interface RefreshClock { fun nowMillis(): Long }
 *   class StreamListViewModel(
 *       active: ActiveConnection, settings: AppSettings, scope: CoroutineScope, clock: RefreshClock,
 *       reconnect: suspend (String) -> Unit,            // Retry = reconnect(name), then load; the root passes ConnectionsViewModel::connect
 *       pageSize: Int = DEFAULT_PAGE_SIZE,              // DEFAULT_PAGE_SIZE = 50, a constant
 *   ) : AutoCloseable {                                 // close() cancels what the view model started and unregisters nothing it does not own
 *       val state: StateFlow<StreamListState>
 *       fun setShown(shown: Boolean)                    // the stream list is on screen (false at the start); not shown = no call at all
 *       fun refreshNow(); fun retry()
 *       fun setFilter(text: String); fun setSort(sort: StreamSort)
 *       fun setPage(page: Int); fun nextPage(); fun previousPage(); fun setPageSize(size: Int)
 *       fun setRefreshInterval(interval: RefreshInterval)   // writes AppSettings and restarts the timer
 *       fun updatedSecondsAgo(): Long?                  // from the last SUCCESSFUL load, by the injected clock; null before the first one
 *   }
 *   data class StreamListState(screen, message: String?, action: String?, rows: List<StreamRow> (the visible page), filter, sort, page, pageSize,
 *       totalStreams: Int (after the filter), rangeText: String ("1 to 5 of 12", "" when no rows), hasNextPage, hasPreviousPage,
 *       stale: Boolean, banner: String?, refreshInterval: RefreshInterval, approximateNote: String)
 *   sealed interface StreamListScreen { NotConnected, Connecting, Loading, Rows, Empty, CannotReadStatistics, Failed(reason: String) }
 *   enum class StreamSort { NameAscending, NameDescending }
 *   data class StreamRow(name, type, messages, firstOffset, lastOffset, publishers, consumers, retention: String)   // display strings only, NO size member
 *   object StreamListTexts { NOT_CONNECTED = "Not connected", UNAVAILABLE = "unavailable", NOT_AVAILABLE = "n/a", LOADING (not blank),
 *       NO_STREAMS (contains "no streams"), CREATE_STREAM = "Create stream", RETRY = "Retry", LOAD_FAILED (not blank) }
 *   core/connections: ConnectionFailureReason.CONNECTION_LOST = "Connection lost" (public);
 *       ActiveConnection.source: StreamSource?  and  ActiveConnection.connectionLost(source: StreamSource)  (D2, see StreamSourceTest)
 *   StreamCatalog.publisherCounts(vhost: String): CatalogResult<Map<String, Int>>  (D8)
 *
 * Assumptions that the architect may overrule are listed in the step 2 report; the tests pin them on purpose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StreamListHarness(val test: TestScope, pageSize: Int = 5, refresh: RefreshInterval = DEFAULT_REFRESH) {
    val h = Harness()
    val catalog: FakeStreamCatalog = h.gateway.catalogOf("uat-eu")
    val offsets: FakeStreamOffsets = h.gateway.offsetsOf("uat-eu")
    val prodCatalog: FakeStreamCatalog = h.gateway.catalogOf("prod-eu")
    val prodOffsets: FakeStreamOffsets = h.gateway.offsetsOf("prod-eu")
    val settings = FakeAppSettings(refresh)
    val reconnects = mutableListOf<String>()

    val vm = StreamListViewModel(
        active = h.active,
        settings = settings,
        scope = test.backgroundScope,
        clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { name ->
            reconnects += name
            h.active.connect(if (name == "prod-eu") h.prod else h.uat)
        },
        pageSize = pageSize,
    )

    val state: StreamListState get() = vm.state.value

    /** Lets everything that is due now run (a load that is not held finishes in here). */
    fun settle() = test.runCurrent()

    /** Virtual time passes, then everything due at the end runs too. */
    fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    suspend fun connect() {
        h.active.connect(h.uat)
        settle()
    }

    /** Connected and the list shown: the first load is done unless a call is held. */
    suspend fun open() {
        connect()
        vm.setShown(true)
        settle()
    }

    fun streams(count: Int, prefix: String = "s-") {
        for (i in 0 until count) addStream("$prefix${"%02d".format(i)}")
    }

    /** A stream with [messages] messages and first offset [first], publishers as given. */
    fun addStream(name: String, messages: Long? = 3, first: Long? = 0, consumers: Int = 0, publishers: Int = 0, retention: Retention = Retention()) {
        catalog.addStream(stream(name, messages = messages, consumers = consumers, retention = retention))
        if (first != null) offsets.setFirst("/", name, first)
        if (publishers > 0) {
            catalog.setPublishers(
                "/", name,
                List(publishers) { PublisherRow("c$it", "svc", it, null, 1, 1, 0) },
            )
        }
    }

    fun names(): List<String> = state.rows.map { it.name }

    fun count(call: String): Int = synchronized(catalog.calls) { catalog.calls.count { it == call } }
}
