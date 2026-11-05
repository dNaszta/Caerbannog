package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val BROKER_PAGE_SIZE = 500
private const val OFFSET_CALLS_AT_ONCE = 8
private const val MILLIS = 1000L

/**
 * State holder of the stream list (spec "Stream list", decisions D2, D3, D5, D8, D9). Follows [ActiveConnection]
 * (link state and [ActiveConnection.sources]), loads every stream over all broker pages of 500 queues, keeps only streams,
 * and filters, sorts and pages them in memory. Nothing is called while the list is not shown ([setShown]).
 *
 * Loads: one at a time, driven by one coroutine per (connection, shown) pair; the timer waits one interval after
 * the LAST load finished, so a slow load makes ticks skip and never overlap. A manual refresh while a load runs
 * does nothing. A result is used only if its [StreamSource] is still the current one, compared by reference: the same
 * profile reconnected has an equal `Connected(name)` state but a new source.
 *
 * Threading: every public method is safe to call from any thread. Each one hops onto [scope] (`scope.launch`), so all fields are
 * read and written on the scope's thread only (the UI dispatcher in the app, a test scheduler in tests); the effect of a call
 * is therefore visible in [state] after the scope has run it, not on return. [scope] must be confined to one thread and used for
 * exactly this view model; [close] cancels what it started.
 *
 * @param reconnect Retry on a failed link: connect the profile of that name again (the root passes the connections view model).
 */
class StreamListViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    pageSize: Int = DEFAULT_PAGE_SIZE,
) : AutoCloseable {
    private var link: LinkState = active.state.value
    private var source: StreamSource? = active.source
    private var shown = false
    private var closed = false

    private var filter = ""
    private var sort = StreamSort.NameAscending
    private var page = 1
    private var pageSize = pageSize.coerceAtLeast(1)
    private var interval: RefreshInterval = settings.streamListRefresh

    // Data of the current connection; cleared when the connection changes (not when it is lost, D2).
    private var capabilities: Capabilities? = null
    private var loaded: List<StreamSummary>? = null
    private var publisherCounts: Map<String, Int>? = null
    private val firstOffsets = mutableMapOf<String, FirstOffset>()
    private var lastSuccessMillis: Long? = null
    private var stale = false
    private var banner: String? = null
    private var cannotRead = false
    private var failedFirstLoad = false

    private val loop = RefreshLoop(
        scope = scope,
        active = active,
        isCurrent = { !closed && source === it },
        interval = { interval },
        loadOnce = ::loadOnce,
    )

    private val _state = MutableStateFlow(StreamListState())
    val state: StateFlow<StreamListState> = _state.asStateFlow()

    private val following: Job

    init {
        render()
        // A collector in our own coroutine, not session.observe: observers run inside the writer's change and must not call back.
        following = scope.launch {
            combine(active.state, active.sources) { l, s -> l to s }.collect { (l, s) -> onLink(l, s) }
        }
    }

    /** The stream list is on screen. Not shown means no broker call at all (D3). */
    fun setShown(shown: Boolean) = onScope {
        if (closed || this.shown == shown) return@onScope
        this.shown = shown
        if (shown) {
            if (canRun()) source?.let(loop::loadNow)
        } else {
            loop.cancel()
        }
    }

    /** Loads now, unless a load is already running (then it does nothing). The timer restarts. */
    fun refreshNow() = onScope { refresh() }

    /** Failed link: connect the profile again (the load follows by itself). Failed first load: load again. */
    fun retry() = onScope {
        val current = link
        when {
            current is LinkState.Failed -> scope.launch { reconnect(current.name) }
            _state.value.screen is StreamListScreen.Failed -> refresh()
        }
    }

    fun setFilter(text: String) = onScope {
        filter = text
        page = 1
        viewChanged()
    }

    fun setSort(sort: StreamSort) = onScope {
        this.sort = sort
        page = 1
        viewChanged()
    }

    fun setPage(page: Int) = onScope { showPage(page) }

    fun nextPage() = onScope { showPage(clampedPage() + 1) }

    fun previousPage() = onScope { showPage(clampedPage() - 1) }

    fun setPageSize(size: Int) = onScope {
        pageSize = size.coerceAtLeast(1)
        page = 1
        viewChanged()
    }

    /** Writes the app setting (on the scope's thread) and restarts the timer from now. */
    fun setRefreshInterval(interval: RefreshInterval) = onScope {
        settings.streamListRefresh = interval
        this.interval = interval
        render()
        // A running load picks the new value up when it ends.
        if (canRun() && !loop.loading) source?.let(loop::timerOnly)
    }

    /**
     * Seconds since the last successful load by the injected clock; null before the first one. Reads the published state only
     * (a thread-safe snapshot), so it can be called from any thread, for example by an "Updated N s ago" ticker.
     */
    fun updatedSecondsAgo(): Long? = _state.value.updatedAtMillis?.let { (clock.nowMillis() - it).coerceAtLeast(0) / MILLIS }

    /** Stops the timer and any load. Safe from any thread and safe to call more than once (every step is idempotent). */
    override fun close() {
        onScope {
            closed = true
            loop.cancel()
            following.cancel()
        }
    }

    // The one way in from outside: serialised on the scope. A cancelled scope drops the call, which is right after close.
    private fun onScope(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun refresh() {
        if (canRun() && !loop.loading) source?.let(loop::loadNow)
    }

    private fun showPage(requested: Int) {
        page = requested
        viewChanged()
    }

    // --- connection ----------------------------------------------------------------------------------

    private fun onLink(newLink: LinkState, newSource: StreamSource?) {
        if (closed || (newSource === source && newLink == link)) return
        link = newLink
        source = newSource
        loop.cancel()
        if (newLink is LinkState.Failed && newLink.reason == ConnectionFailureReason.CONNECTION_LOST) {
            // D2: the rows of the last load stay, marked stale, with the banner.
            if (loaded != null) {
                stale = true
                banner = newLink.reason
            }
        } else {
            clearData()
        }
        render()
        if (canRun()) source?.let(loop::loadNow)
    }

    private fun clearData() {
        capabilities = null
        loaded = null
        publisherCounts = null
        firstOffsets.clear()
        lastSuccessMillis = null
        stale = false
        banner = null
        cannotRead = false
        failedFirstLoad = false
    }

    private fun canRun(): Boolean = !closed && shown && link is LinkState.Connected && source != null

    // --- loading -------------------------------------------------------------------------------------

    @Suppress("ReturnCount")
    private suspend fun loadOnce(src: StreamSource) {
        if (loaded == null && failedFirstLoad) {
            failedFirstLoad = false
            render()
        }
        val caps = capabilities ?: run {
            val result = loop.ask(src) { src.catalog.capabilities() } ?: return
            if (result !is CatalogResult.Success) return loadFailed(result)
            result.value.also { capabilities = it }
        }
        if (caps.visibility == Visibility.NONE) return notAllowed()

        val streams = readAllStreams(src) ?: return

        // D8: one call per load, and only when the number is complete; otherwise "unavailable", never a partial number.
        var counts: Map<String, Int>? = null
        if (caps.visibility == Visibility.FULL && caps.streamPlugin == PluginStatus.PRESENT) {
            val result = loop.ask(src) { src.catalog.publisherCounts(src.vhost) } ?: return
            counts = (result as? CatalogResult.Success)?.value
        }

        loaded = streams
        publisherCounts = counts
        lastSuccessMillis = clock.nowMillis()
        stale = false
        banner = null
        cannotRead = false
        failedFirstLoad = false
        page = clampedPage()
        render()
        fetchOffsets(src, refreshAll = true)
    }

    // The broker pages over ALL queues; the catalog keeps the streams. A failure on any page fails the whole load.
    private suspend fun readAllStreams(src: StreamSource): List<StreamSummary>? {
        val all = mutableListOf<StreamSummary>()
        var next = 1
        while (true) {
            val result = loop.ask(src) { src.catalog.listStreams(StreamListRequest(vhost = src.vhost, page = next, pageSize = BROKER_PAGE_SIZE)) } ?: return null
            if (result !is CatalogResult.Success) {
                loadFailed(result)
                return null
            }
            all += result.value.rows
            if (next >= result.value.pageCount) return all
            next++
        }
    }

    private fun loadFailed(result: CatalogResult<*>) {
        if (result == CatalogResult.NotAuthorised) return notAllowed()
        if (loaded == null) failedFirstLoad = true else stale = true
        render()
    }

    private fun notAllowed() {
        loaded = null
        cannotRead = true
        stale = false
        render()
    }

    // First offsets of the visible rows only (D5 b). Rows that already have one keep it until the new answer arrives.
    private suspend fun fetchOffsets(src: StreamSource, refreshAll: Boolean) {
        var names = visibleSummaries().map { it.name }.filter { refreshAll || it !in firstOffsets }
        val gate = Semaphore(OFFSET_CALLS_AT_ONCE)
        while (names.isNotEmpty()) {
            coroutineScope {
                for (name in names) {
                    launch {
                        val offset = gate.withPermit { src.offsets.firstOffset(src.vhost, name) }
                        if (loop.alive(src)) {
                            firstOffsets[name] = offset
                            render()
                        }
                    }
                }
            }
            // The page may have changed while the calls ran.
            names = visibleSummaries().map { it.name }.filter { it !in firstOffsets }
        }
    }

    // --- view ----------------------------------------------------------------------------------------

    // Page, filter and sort never call the broker list (paging is in memory); the timer restarts with them.
    private fun viewChanged() {
        page = clampedPage()
        render()
        if (canRun() && !loop.loading) source?.let { src -> loop.stepThenTimer(src) { fetchOffsets(src, refreshAll = false) } }
    }

    private fun filteredSorted(): List<StreamSummary> {
        val all = loaded.orEmpty()
        val matching = if (filter.isEmpty()) all else all.filter { it.name.contains(filter, ignoreCase = true) }
        val byName = compareBy<StreamSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
        return when (sort) {
            StreamSort.NameAscending -> matching.sortedWith(byName)
            StreamSort.NameDescending -> matching.sortedWith(byName.reversed())
        }
    }

    private fun pageCount(total: Int): Int = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)

    private fun clampedPage(): Int = page.coerceIn(1, pageCount(filteredSorted().size))

    private fun visibleSummaries(): List<StreamSummary> {
        val all = filteredSorted()
        val current = page.coerceIn(1, pageCount(all.size))
        return all.drop((current - 1) * pageSize).take(pageSize)
    }

    private fun screen(): StreamListScreen {
        val current = link
        return when {
            current is LinkState.Failed -> if (loaded != null) StreamListScreen.Rows else StreamListScreen.Failed(current.reason)
            current is LinkState.Disconnected -> StreamListScreen.NotConnected
            current is LinkState.Connecting || source == null -> StreamListScreen.Connecting
            cannotRead -> StreamListScreen.CannotReadStatistics
            loaded != null -> if (loaded.orEmpty().isEmpty()) StreamListScreen.Empty else StreamListScreen.Rows
            failedFirstLoad -> StreamListScreen.Failed(StreamListTexts.LOAD_FAILED)
            else -> StreamListScreen.Loading
        }
    }

    private fun render() {
        val all = filteredSorted()
        val current = page.coerceIn(1, pageCount(all.size))
        val start = (current - 1) * pageSize
        val visible = all.drop(start).take(pageSize)
        val screen = screen()
        val counts = publisherCounts
        _state.value = StreamListState(
            screen = screen,
            message = when (screen) {
                StreamListScreen.NotConnected -> StreamListTexts.NOT_CONNECTED
                StreamListScreen.Connecting -> StreamListTexts.CONNECTING
                StreamListScreen.Loading -> StreamListTexts.LOADING
                StreamListScreen.Rows -> null
                StreamListScreen.Empty -> StreamListTexts.NO_STREAMS
                StreamListScreen.CannotReadStatistics -> CatalogMessages.NOT_MANAGEMENT_USER
                is StreamListScreen.Failed -> screen.reason
            },
            action = when {
                screen is StreamListScreen.Failed || link is LinkState.Failed -> StreamListTexts.RETRY
                screen == StreamListScreen.Empty -> StreamListTexts.CREATE_STREAM
                else -> null
            },
            rows = visible.map { streamRow(it, firstOffsets[it.name], counts?.let { c -> c[it.name] ?: 0 }) },
            filter = filter,
            sort = sort,
            page = current,
            pageSize = pageSize,
            totalStreams = all.size,
            rangeText = if (visible.isEmpty()) "" else "${start + 1} to ${start + visible.size} of ${all.size}",
            hasNextPage = start + visible.size < all.size,
            hasPreviousPage = current > 1,
            stale = stale,
            banner = banner,
            refreshInterval = interval,
            updatedAtMillis = lastSuccessMillis,
        )
    }
}
