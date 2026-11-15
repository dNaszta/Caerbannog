package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * State holder of the Publishers and consumers tab (spec "Publishers and consumers tab", decisions D2, D3, S3). One long-lived instance
 * per app, like [StreamListViewModel], which it mirrors in threading and refresh rules; the timer, the load token, the alive checks and
 * the D2 mapping are the shared [RefreshLoop] (not copied). Nothing is called while the tab is not shown ([show], [hide]).
 *
 * Per load: capabilities once per connection source, the stream (messages), its first offset, then publishers and consumers unless the
 * plugin is missing or the user may not read statistics. Read only: no write call, no message read.
 *
 * Threading: every public method is safe from any thread and hops onto [scope]; its effect shows in [state] after the scope ran it.
 *
 * @param reconnect Retry on a failed link: connect the profile of that name again.
 */
class StreamClientsViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
) : AutoCloseable, RefreshTarget {
    /** What the last good load found, before it is turned into display strings. */
    private sealed interface Outcome {
        data class Sections(val lastOffset: String, val publishers: List<PublisherRow>, val consumers: List<ConsumerRow>, val ownOnly: Boolean) : Outcome

        data class PluginMissing(val lastOffset: String) : Outcome

        data object Gone : Outcome

        data object Denied : Outcome
    }

    private var link: LinkState = active.state.value
    private var source: StreamSource? = active.source
    private var shown = false
    private var closed = false
    private var stream = ""
    private var interval: RefreshInterval = settings.streamListRefresh

    // Data of the current connection and stream; cleared on a connection change, a stream change and hide (not on a lost connection, D2).
    private var capabilities: Capabilities? = null
    private var outcome: Outcome? = null
    private var lastSuccessMillis: Long? = null
    private var stale = false
    private var banner: String? = null
    private var failedFirstLoad = false
    private val publisherHistory = RollingMetricHistory()
    private val consumerLagHistory = RollingMetricHistory()

    private val loop = RefreshLoop(
        scope = scope,
        active = active,
        isCurrent = { !closed && source === it },
        interval = {
            interval = settings.streamListRefresh
            render()
            interval
        },
        loadOnce = ::loadOnce,
    )

    private val _state = MutableStateFlow(StreamClientsState())
    val state: StateFlow<StreamClientsState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    private val following: Job

    init {
        render()
        following = scope.launch {
            combine(active.state, active.sources) { l, s -> l to s }.collect { (l, s) -> onLink(l, s) }
        }
    }

    /** The tab is shown for [stream]; the data of another stream is cleared at once. Showing the same stream again does nothing. */
    fun show(stream: String) = onScope {
        if (closed || (shown && this.stream == stream)) return@onScope
        shown = true
        this.stream = stream
        interval = settings.streamListRefresh
        clearData()
        render()
        startIfRunnable()
    }

    /** The tab is not shown: no broker call, the data is cleared. */
    fun hide() = onScope {
        if (closed || !shown) return@onScope
        shown = false
        stream = ""
        loop.cancel()
        clearData()
        render()
    }

    /** Loads now, unless a load is already running (then it does nothing). The timer restarts. */
    override fun refreshNow() = onScope {
        if (canRun() && !loop.loading) startIfRunnable()
    }

    /** Failed link: connect the profile again (the load follows by itself). Failed first load: load again. */
    fun retry() = onScope {
        val current = link
        when {
            current is LinkState.Failed -> scope.launch { reconnect(current.name) }
            _state.value.screen is StreamClientsScreen.Failed && canRun() && !loop.loading -> startIfRunnable()
        }
    }

    /** Stops the timer and any load. Safe from any thread and safe to call more than once. */
    override fun close() {
        onScope {
            closed = true
            loop.cancel()
            following.cancel()
        }
    }

    private fun onScope(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun canRun(): Boolean = !closed && shown && link is LinkState.Connected && source != null

    private fun startIfRunnable() {
        if (canRun()) source?.let(loop::loadNow)
    }

    // --- connection ----------------------------------------------------------------------------------

    private fun onLink(newLink: LinkState, newSource: StreamSource?) {
        if (closed || (newSource === source && newLink == link)) return
        link = newLink
        source = newSource
        loop.cancel()
        if (newLink is LinkState.Failed && newLink.reason == ConnectionFailureReason.CONNECTION_LOST) {
            // D2: the rows of the last load stay, marked stale, with the banner.
            if (outcome is Outcome.Sections) {
                stale = true
                banner = newLink.reason
            }
        } else {
            clearData()
        }
        render()
        startIfRunnable()
    }

    private fun clearData() {
        capabilities = null
        outcome = null
        lastSuccessMillis = null
        stale = false
        banner = null
        failedFirstLoad = false
        publisherHistory.clear()
        consumerLagHistory.clear()
    }

    // --- loading -------------------------------------------------------------------------------------

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun loadOnce(src: StreamSource) {
        val name = stream
        if (outcome == null && failedFirstLoad) {
            failedFirstLoad = false
            render()
        }
        val caps = capabilities ?: run {
            val result = loop.ask(src) { src.catalog.capabilities() } ?: return
            if (result !is CatalogResult.Success) return loadFailed(result)
            result.value.also { capabilities = it }
        }
        if (caps.visibility == Visibility.NONE) return finish(Outcome.Denied, success = false)

        val detail = loop.ask(src) { src.catalog.streamDetail(src.vhost, name) } ?: return
        val summary = (detail as? CatalogResult.Success)?.value ?: return loadFailed(detail)
        val first = src.offsets.firstOffset(src.vhost, name)
        if (!loop.alive(src)) return
        val lastOffset = lastOffsetText(first, summary.messages)
        if (caps.streamPlugin == PluginStatus.MISSING) return finish(Outcome.PluginMissing(lastOffset), success = true)

        val publishers = loop.ask(src) { src.catalog.publishers(src.vhost, name) } ?: return
        val publisherRows = (publishers as? CatalogResult.Success)?.value ?: return loadFailed(publishers, lastOffset)
        val consumers = loop.ask(src) { src.catalog.consumers(src.vhost, name) } ?: return
        val consumerRows = (consumers as? CatalogResult.Success)?.value ?: return loadFailed(consumers, lastOffset)
        finish(Outcome.Sections(lastOffset, publisherRows, consumerRows, caps.visibility == Visibility.OWN_CONNECTIONS_ONLY), success = true)
    }

    // The broker's words for a missing stream, a missing plugin and a missing right are values; anything else is a failed load.
    private fun loadFailed(result: CatalogResult<*>, lastOffset: String = "") {
        when (result) {
            CatalogResult.NotFound -> finish(Outcome.Gone, success = false)
            CatalogResult.NotAuthorised -> finish(Outcome.Denied, success = false)
            CatalogResult.PluginMissing -> finish(Outcome.PluginMissing(lastOffset), success = true)
            else -> {
                if (outcome is Outcome.Sections) {
                    stale = true
                } else {
                    outcome = null
                    failedFirstLoad = true
                }
                render()
            }
        }
    }

    private fun finish(result: Outcome, success: Boolean) {
        outcome = result
        if (success) {
            val now = clock.nowMillis()
            lastSuccessMillis = now
            if (result is Outcome.Sections) {
                val pubKeys = uniqueKeys(result.publishers.map { "${it.connectionName}#${it.publisherId}" })
                pubKeys.zip(result.publishers).forEach { (key, row) ->
                    if (!row.isSettling()) {
                        publisherHistory.record(key, row.published, now)
                    }
                }
                publisherHistory.retainKeys(pubKeys.toSet())
                val consKeys = uniqueKeys(result.consumers.map { "${it.connectionName}#${it.subscriptionId}" })
                consKeys.zip(result.consumers).forEach { (key, row) ->
                    if (!row.isSettling()) {
                        consumerLagHistory.record(key, row.offsetLag, now)
                    }
                }
                consumerLagHistory.retainKeys(consKeys.toSet())
            }
        }
        stale = false
        banner = null
        failedFirstLoad = false
        render()
    }

    // --- view ----------------------------------------------------------------------------------------

    private fun screen(): StreamClientsScreen {
        val current = link
        val result = outcome
        return when {
            current is LinkState.Failed -> if (result is Outcome.Sections) StreamClientsScreen.Content else StreamClientsScreen.Failed(current.reason)
            current is LinkState.Disconnected -> StreamClientsScreen.NotConnected
            current is LinkState.Connecting || source == null -> StreamClientsScreen.Connecting
            result is Outcome.Denied -> StreamClientsScreen.CannotReadStatistics
            result is Outcome.PluginMissing -> StreamClientsScreen.PluginMissing
            result is Outcome.Gone -> StreamClientsScreen.StreamGone
            result is Outcome.Sections -> StreamClientsScreen.Content
            failedFirstLoad -> StreamClientsScreen.Failed(StreamClientsTexts.LOAD_FAILED)
            else -> StreamClientsScreen.Loading
        }
    }

    private fun render() {
        val screen = screen()
        val result = outcome
        val sections = result as? Outcome.Sections
        val pubStates = sections?.publishers.orEmpty().let { rows ->
            uniqueKeys(rows.map { "${it.connectionName}#${it.publisherId}" }).zip(rows) { key, row -> publisherState(key, row) }
        }
        val consStates = sections?.consumers.orEmpty().let { rows ->
            uniqueKeys(rows.map { "${it.connectionName}#${it.subscriptionId}" }).zip(rows) { key, row -> consumerState(key, row) }
        }
        _updatedAt.value = lastSuccessMillis
        _state.value = StreamClientsState(
            screen = screen,
            message = when (screen) {
                StreamClientsScreen.NotConnected -> StreamClientsTexts.NOT_CONNECTED
                StreamClientsScreen.Connecting -> StreamClientsTexts.CONNECTING
                StreamClientsScreen.Loading -> StreamClientsTexts.LOADING
                StreamClientsScreen.Content -> null
                StreamClientsScreen.CannotReadStatistics -> CatalogMessages.NOT_MANAGEMENT_USER
                StreamClientsScreen.PluginMissing -> StreamClientsTexts.PLUGIN_MISSING
                StreamClientsScreen.StreamGone -> StreamClientsTexts.STREAM_GONE
                is StreamClientsScreen.Failed -> screen.reason
            },
            stream = stream,
            lastOffset = when (result) {
                is Outcome.Sections -> result.lastOffset
                is Outcome.PluginMissing -> result.lastOffset
                else -> ""
            },
            publishers = pubStates,
            consumers = consStates,
            ownConnectionsNotice = if (sections?.ownOnly == true) StreamClientsTexts.OWN_CONNECTIONS_NOTICE else null,
            stale = stale,
            banner = banner,
            updatedAtMillis = lastSuccessMillis,
            refreshInterval = interval,
            publisherSparklines = publisherHistory.snapshot(pubStates.map { it.key }, SparklineSemantic.Velocity),
            consumerLagSparklines = consumerLagHistory.snapshot(consStates.map { it.key }, SparklineSemantic.ConsumerLag),
        )
    }
}

// A broker row whose connection details are still `{}` (a client that has just attached): nothing in it can be trusted yet.
private fun PublisherRow.isSettling(): Boolean = connectionName.isEmpty()

// Also a row for which the broker sent no lag: a number nobody reported is never shown as 0 or as Caught up (M2-17c).
private fun ConsumerRow.isSettling(): Boolean = connectionName.isEmpty() || offsetLag == null

// The lag of a settled row; `null` exactly when [isSettling], so a missing number cannot reach a status or a text.
private fun ConsumerRow.settledLag(): Long? = if (isSettling()) null else offsetLag

private fun nameOrUnnamed(name: String?): String = if (name.isNullOrBlank()) StreamClientsTexts.UNNAMED else name

/**
 * Makes row keys unique within one state: the 2nd and later repeat of a base key gets `~2`, `~3` in the broker's order. Repeats occur for
 * clients that have just attached (blank connection name, same id); a duplicate key would crash the lazy list.
 */
private fun uniqueKeys(bases: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return bases.map { base ->
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base~$n"
    }
}

private fun publisherState(key: String, row: PublisherRow): PublisherRowState {
    val settling = row.isSettling()
    fun count(value: Long): String = if (settling) StreamClientsTexts.UPDATING else groupDigits(value)
    return PublisherRowState(
        key = key,
        connection = if (settling) StreamClientsTexts.UNKNOWN else row.connectionName,
        user = row.user ?: StreamClientsTexts.NO_USER,
        reference = nameOrUnnamed(row.reference),
        published = count(row.published),
        confirmed = count(row.confirmed),
        errors = count(row.errored),
        settling = settling,
    )
}

private fun consumerState(key: String, row: ConsumerRow): ConsumerRowState {
    val lag = row.settledLag()
    val settling = lag == null
    val status = when {
        lag == null -> ConsumerStatus.Updating
        !row.active -> ConsumerStatus.NotActive
        lag <= 0L -> ConsumerStatus.CaughtUp
        else -> ConsumerStatus.Behind(lag)
    }
    return ConsumerRowState(
        key = key,
        name = nameOrUnnamed(row.name),
        connection = if (settling) StreamClientsTexts.UNKNOWN else row.connectionName,
        user = row.user ?: StreamClientsTexts.NO_USER,
        offset = if (settling) StreamClientsTexts.UPDATING else row.offset.toString(),
        behind = if (lag == null) StreamClientsTexts.UPDATING else groupDigits(lag),
        status = status,
        browseFromOffset = if (settling) null else row.offset,
    )
}
