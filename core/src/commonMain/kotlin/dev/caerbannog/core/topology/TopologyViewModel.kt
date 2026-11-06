package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.permissionBanner
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.DEFAULT_PAGE_SIZE
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.RefreshLoop
import dev.caerbannog.core.streams.RefreshTarget
import dev.caerbannog.core.streams.groupDigits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val RABBIT_NODE_PREFIX = "rabbit@"

/**
 * State holder of the read-only Topology screen (brief M2-08, 08b; decisions D2, D3, T1, T5, rulings R1 to R6). It follows
 * [ActiveConnection] like the stream list and loads, through the shared [RefreshLoop], only what the shown tab needs: the vhost list and
 * every queue page of the SELECTED vhost at every tick, the exchanges on the Exchanges tab, the bindings on the Bindings tab or while a queue
 * is selected. Rows are filtered and paged IN MEMORY: the broker's page counts include streams (R1), so the numbers shown come from the
 * loaded rows only. Nothing here reads, gets or purges a queue's messages: [TopologyCatalog] has no such call.
 *
 * Threading: every public method hops onto [scope] (`scope.launch`), so every field is touched on the scope's thread only; the effect of
 * a call is visible in [state] after the scope has run it, not on return. [scope] must be confined to one thread and used for this view
 * model only; [close] cancels what it started.
 *
 * @param reconnect Retry on a lost link: connect the profile of that name again.
 * @param context the connected profile and its permission result, for the banners only (never for what loads).
 */
class TopologyViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<TopologyContext?>,
    pageSize: Int = DEFAULT_PAGE_SIZE,
) : AutoCloseable, RefreshTarget {
    private var link: LinkState = active.state.value
    private var source: StreamSource? = active.source
    private var shown = false
    private var closed = false

    // The connection's own vhost (the permission check was made there) and the selected one (R4: per connection, not persisted).
    private var home: String = source?.vhost.orEmpty()
    private var vhost: String = home
    private var offered: List<String>? = null

    private var tab = TopologyTab.Queues
    private var queueFilter = QueueTypeFilter.Quorum
    private val searches = mutableMapOf<TopologyTab, String>()
    private var showDefaultExchange = false
    private var selectedName: String? = null
    private var page = 1
    private var pageSize = pageSize.coerceAtLeast(1)

    // Rows of the selected vhost; null = not loaded (yet). Only the tabs that were asked for are filled.
    private var queues: List<QueueRow>? = null
    private var exchanges: List<ExchangeRow>? = null
    private var bindings: List<BindingRow>? = null

    private var lastSuccessMillis: Long? = null
    private var stale = false
    private var banner: String? = null
    private var cannotRead = false
    private var vhostUnavailable = false
    private var failedFirstLoad = false

    private val loop = RefreshLoop(
        scope = scope,
        active = active,
        isCurrent = { !closed && source === it },
        interval = { settings.streamListRefresh },
        loadOnce = ::loadOnce,
    )

    private val _state = MutableStateFlow(TopologyState())
    val state: StateFlow<TopologyState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    private val following: Job
    private val followingContext: Job

    init {
        render()
        // Collectors in our own coroutines, not session.observe: observers run inside the writer's change and must not call back.
        following = scope.launch {
            combine(active.state, active.sources) { l, s -> l to s }.collect { (l, s) -> onLink(l, s) }
        }
        followingContext = scope.launch { context.collect { render() } }
    }

    /** The Topology screen is on screen. Not shown means no broker call at all (D3). */
    fun setShown(shown: Boolean) = onScope {
        if (closed || this.shown == shown) return@onScope
        this.shown = shown
        if (shown) {
            if (canRun()) source?.let(loop::loadNow)
        } else {
            loop.cancel()
        }
    }

    /** Loads now unless a load runs; the timer restarts. */
    override fun refreshNow() = onScope { refresh() }

    /** A failed link: connect the profile again. A failed first load: load again. */
    fun retry() = onScope {
        val current = link
        when {
            closed -> Unit
            current is LinkState.Failed -> scope.launch { reconnect(current.name) }
            _state.value.screen is TopologyScreen.Failed -> refresh()
        }
    }

    fun setTab(tab: TopologyTab) = onScope {
        if (closed || this.tab == tab) return@onScope
        this.tab = tab
        page = 1
        // The old tab's rows go; the bindings stay while a selected queue still needs them.
        exchanges = null
        if (selectedName == null) bindings = null
        failedFirstLoad = false
        render()
        if (canRun() && needsLoad()) source?.let(loop::loadNow)
    }

    fun setQueueFilter(filter: QueueTypeFilter) = onScope {
        queueFilter = filter
        page = 1
        viewChanged()
    }

    /** The search text of the CURRENT tab: a substring of the name, case-insensitive like the stream list; back to page 1. */
    fun setSearch(text: String) = onScope {
        searches[tab] = text
        page = 1
        viewChanged()
    }

    /** R4: drops the rows of the old vhost, cancels the running load, loads the chosen one now. Not persisted. */
    fun selectVhost(name: String) = onScope {
        if (closed || link !is LinkState.Connected || name == vhost || offered?.contains(name) != true) return@onScope
        vhost = name
        queues = null
        exchanges = null
        bindings = null
        selectedName = null
        searches.clear()
        page = 1
        stale = false
        vhostUnavailable = false
        failedFirstLoad = false
        lastSuccessMillis = null
        render()
        loop.cancel()
        if (canRun()) source?.let(loop::loadNow)
    }

    /** Shows the detail of a loaded queue; null closes it. A name that is not loaded is ignored. */
    fun selectQueue(name: String?) = onScope {
        if (closed) return@onScope
        if (name != null && queues.orEmpty().none { it.name == name }) return@onScope
        selectedName = name
        if (name == null && tab != TopologyTab.Bindings) bindings = null
        render()
        if (name != null && bindings == null && canRun()) source?.let(loop::loadNow)
    }

    fun setShowDefaultExchange(show: Boolean) = onScope {
        showDefaultExchange = show
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

    /** Stops the timer and any load. Safe from any thread and safe to call more than once. */
    override fun close() {
        onScope {
            closed = true
            loop.cancel()
            following.cancel()
            followingContext.cancel()
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

    private fun viewChanged() {
        if (closed) return
        page = clampedPage()
        render()
    }

    // --- connection ----------------------------------------------------------------------------------

    private fun onLink(newLink: LinkState, newSource: StreamSource?) {
        if (closed || (newSource === source && newLink == link)) return
        link = newLink
        source = newSource
        loop.cancel()
        if (newLink is LinkState.Failed && newLink.reason == ConnectionFailureReason.CONNECTION_LOST) {
            // D2: the rows of the last load stay, marked stale, with the banner.
            if (currentRows() != null) {
                stale = true
                banner = newLink.reason
            }
        } else {
            clearData(newSource)
        }
        render()
        if (canRun()) source?.let(loop::loadNow)
    }

    // A new connection starts on the connection's own vhost again (R4).
    private fun clearData(newSource: StreamSource?) {
        home = newSource?.vhost.orEmpty()
        vhost = home
        offered = null
        queues = null
        exchanges = null
        bindings = null
        selectedName = null
        searches.clear()
        page = 1
        lastSuccessMillis = null
        stale = false
        banner = null
        cannotRead = false
        vhostUnavailable = false
        failedFirstLoad = false
    }

    private fun canRun(): Boolean = !closed && shown && link is LinkState.Connected && source != null

    // --- loading -------------------------------------------------------------------------------------

    private fun needsExchanges(): Boolean = tab == TopologyTab.Exchanges

    private fun needsBindings(): Boolean = tab == TopologyTab.Bindings || selectedName != null

    private fun needsLoad(): Boolean = (needsExchanges() && exchanges == null) || (needsBindings() && bindings == null)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun loadOnce(src: StreamSource) {
        if (currentRows() == null && failedFirstLoad) {
            failedFirstLoad = false
            render()
        }
        val target = vhost

        // The list of vhosts. A list the broker answers badly is not a reason to fail the queues: the last one stays.
        when (val result = loop.ask(src) { src.topology.vhosts() } ?: return) {
            is CatalogResult.Success -> {
                offered = result.value.map { it.name }
                render()
            }
            CatalogResult.NotAuthorised -> return notAllowed()
            else -> Unit
        }

        val newQueues = readAllQueues(src, target) ?: return
        val newExchanges = if (needsExchanges()) {
            readOne(src) { src.topology.exchanges(target) } ?: return
        } else {
            null
        }
        val newBindings = if (needsBindings()) {
            readOne(src) { src.topology.bindings(target) } ?: return
        } else {
            null
        }

        queues = newQueues
        exchanges = newExchanges
        bindings = newBindings
        if (selectedName != null && newQueues.none { it.name == selectedName }) selectedName = null
        lastSuccessMillis = clock.nowMillis()
        stale = false
        banner = null
        cannotRead = false
        vhostUnavailable = false
        failedFirstLoad = false
        page = clampedPage()
        render()
    }

    // The broker pages over ALL queues of the vhost (streams included, R1); the catalog keeps the queues. A failure on any page fails the load.
    private suspend fun readAllQueues(src: StreamSource, target: String): List<QueueRow>? {
        val all = mutableListOf<QueueRow>()
        var next = 1
        while (true) {
            val result = loop.ask(src) { src.topology.queues(target, next) } ?: return null
            if (result !is CatalogResult.Success) {
                loadFailed(result)
                return null
            }
            all += result.value.rows
            if (next >= result.value.pageCount) return all
            next++
        }
    }

    private suspend fun <T> readOne(src: StreamSource, call: suspend () -> CatalogResult<T>): T? {
        val result = loop.ask(src, call) ?: return null
        if (result !is CatalogResult.Success) {
            loadFailed(result)
            return null
        }
        return result.value
    }

    private fun loadFailed(result: CatalogResult<*>) {
        when {
            result == CatalogResult.NotAuthorised -> notAllowed()
            result == CatalogResult.NotFound -> unavailable()
            currentRows() == null -> {
                failedFirstLoad = true
                render()
            }
            else -> {
                stale = true
                render()
            }
        }
    }

    private fun notAllowed() {
        queues = null
        exchanges = null
        bindings = null
        selectedName = null
        offered = null
        cannotRead = true
        stale = false
        render()
    }

    // R2: the broker answered 404 for the vhost. The rows are dropped (not stale: the broker answered), the switch stays usable.
    private fun unavailable() {
        queues = null
        exchanges = null
        bindings = null
        selectedName = null
        vhostUnavailable = true
        stale = false
        failedFirstLoad = false
        lastSuccessMillis = clock.nowMillis()
        render()
    }

    // --- view ----------------------------------------------------------------------------------------

    private fun currentRows(): Any? = when (tab) {
        TopologyTab.Queues -> queues
        TopologyTab.Exchanges -> exchanges
        TopologyTab.Bindings -> bindings
    }

    private fun search(): String = searches[tab].orEmpty()

    private fun matches(name: String): Boolean = search().let { it.isEmpty() || name.contains(it, ignoreCase = true) }

    private fun queueViews(): List<QueueRowView> {
        val byName = compareBy<QueueRow, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
        return queues.orEmpty()
            .filter { it.isQuorum == (queueFilter == QueueTypeFilter.Quorum) && matches(it.name) }
            .sortedWith(byName)
            .map(::queueView)
    }

    private fun exchangeViews(): List<ExchangeRowView> =
        exchanges.orEmpty().map(::exchangeView).filter { matches(it.name) }

    // The default exchange and its implicit bindings are noise on a big vhost: hidden until asked for.
    private fun visibleBindings(): List<BindingRowView> =
        bindings.orEmpty().filter { showDefaultExchange || it.source.isNotEmpty() }.map(::bindingView)

    private fun bindingViews(): List<BindingRowView> = visibleBindings().filter {
        matches(it.source) || matches(it.destination) || matches(it.routingKey)
    }

    private fun filteredCount(): Int = when (tab) {
        TopologyTab.Queues -> queueViews().size
        TopologyTab.Exchanges -> exchangeViews().size
        TopologyTab.Bindings -> bindingViews().size
    }

    private fun pageCount(total: Int): Int = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)

    private fun clampedPage(): Int = page.coerceIn(1, pageCount(filteredCount()))

    private fun <T> List<T>.pageOf(current: Int): List<T> = drop((current - 1) * pageSize).take(pageSize)

    private fun isEmptyTab(): Boolean = when (tab) {
        TopologyTab.Queues -> queues.orEmpty().isEmpty()
        TopologyTab.Exchanges -> exchanges.orEmpty().isEmpty()
        TopologyTab.Bindings -> visibleBindings().isEmpty()
    }

    private fun screen(): TopologyScreen {
        val current = link
        return when {
            current is LinkState.Failed -> if (currentRows() != null) TopologyScreen.Ready else TopologyScreen.Failed(current.reason)
            current is LinkState.Disconnected -> TopologyScreen.NotConnected
            current is LinkState.Connecting || source == null -> TopologyScreen.Connecting
            cannotRead -> TopologyScreen.CannotRead
            vhostUnavailable -> TopologyScreen.VhostUnavailable(vhost)
            currentRows() != null -> if (isEmptyTab()) TopologyScreen.Empty else TopologyScreen.Ready
            failedFirstLoad -> TopologyScreen.Failed(TopologyTexts.LOAD_FAILED)
            else -> TopologyScreen.Loading
        }
    }

    private fun emptyText(): String = when (tab) {
        TopologyTab.Queues -> TopologyTexts.NO_QUEUES
        TopologyTab.Exchanges -> TopologyTexts.NO_EXCHANGES
        TopologyTab.Bindings -> TopologyTexts.NO_BINDINGS
    }

    private fun vhostsOffered(): List<String> {
        val names = offered.orEmpty()
        val byName = compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
        return names.filter { it == home }.take(1) + names.filter { it != home }.sortedWith(byName)
    }

    private fun render() {
        // Only the current tab's rows are built (a vhost can have thousands of queues or bindings).
        val queueRows = if (tab == TopologyTab.Queues) queueViews() else emptyList()
        val exchangeRows = if (tab == TopologyTab.Exchanges) exchangeViews() else emptyList()
        val bindingRows = if (tab == TopologyTab.Bindings) bindingViews() else emptyList()
        val total = queueRows.size + exchangeRows.size + bindingRows.size
        val current = page.coerceIn(1, pageCount(total))
        val start = (current - 1) * pageSize
        val screen = screen()
        val shownQueues = queueRows.pageOf(current)
        val shownExchanges = exchangeRows.pageOf(current)
        val shownBindings = bindingRows.pageOf(current)
        val shownCount = shownQueues.size + shownExchanges.size + shownBindings.size
        val offeredVhosts = if (cannotRead) emptyList() else vhostsOffered()
        _updatedAt.value = lastSuccessMillis
        _state.value = TopologyState(
            screen = screen,
            message = when (screen) {
                TopologyScreen.NotConnected -> TopologyTexts.NOT_CONNECTED
                TopologyScreen.Connecting -> TopologyTexts.CONNECTING
                TopologyScreen.Loading -> TopologyTexts.LOADING
                TopologyScreen.Ready -> null
                TopologyScreen.Empty -> emptyText()
                TopologyScreen.CannotRead -> TopologyTexts.NO_MANAGEMENT_TAG
                is TopologyScreen.VhostUnavailable -> TopologyTexts.vhostUnavailable(screen.vhost)
                is TopologyScreen.Failed -> screen.reason
            },
            action = if (screen is TopologyScreen.Failed || link is LinkState.Failed) TopologyTexts.RETRY else null,
            tab = tab,
            queueFilter = queueFilter,
            search = search(),
            vhost = vhost,
            vhosts = offeredVhosts,
            showVhostSwitch = offeredVhosts.isNotEmpty(),
            homeVhost = if (screen != TopologyScreen.CannotRead && offeredVhosts.contains(home)) home else null,
            queues = shownQueues,
            exchanges = shownExchanges,
            bindings = shownBindings,
            showDefaultExchange = showDefaultExchange,
            selectedQueue = selectedName?.let { name -> queues.orEmpty().firstOrNull { it.name == name }?.detail(bindings.orEmpty()) },
            page = current,
            pageSize = pageSize,
            total = total,
            rangeText = if (shownCount == 0) "" else "${start + 1} to ${start + shownCount} of $total",
            hasNextPage = start + shownCount < total,
            hasPreviousPage = current > 1,
            stale = stale,
            connectionBanner = banner,
            banners = banners(),
            refreshInterval = settings.streamListRefresh,
            updatedAtMillis = lastSuccessMillis,
        )
    }

    // --- banners: why the screen is read-only (T5, R6, owner decision O1) -----------------------------------

    // Order: ReadOnlyLevel (only at that level), NoConfigure, DeclaredNotConfirmed or NotReadable, OtherVhost. The broker and declared reasons
    // belong to the connection's own vhost, the only one the permission check looked at (R6), so they are hidden on another one.
    private fun banners(): List<TopologyBanner> {
        val ctx = context.value
        if (ctx == null || cannotRead || vhost.isEmpty()) return emptyList()
        val reasons = mutableListOf<TopologyBanner>()
        if (ctx.profile.topologyControlLevel == TopologyControlLevel.READ_ONLY) {
            reasons += TopologyBanner(TopologyBannerKind.ReadOnlyLevel, TopologyTexts.READ_ONLY_HEADLINE, TopologyTexts.READ_ONLY_DETAIL)
        }
        if (vhost == home) {
            brokerReason(ctx)?.let { reasons += it }
        } else {
            reasons += TopologyBanner(TopologyBannerKind.OtherVhost, TopologyTexts.otherVhost(home), null)
        }
        return reasons
    }

    private fun brokerReason(ctx: TopologyContext): TopologyBanner? = when (val result = ctx.permission) {
        null -> null
        is PermissionCheckResult.Checked ->
            if (result.topologyUnlocked) null
            else TopologyBanner(TopologyBannerKind.NoConfigure, TopologyTexts.noConfigure(ctx.profile.username, home), null)
        is PermissionCheckResult.DeclaredByUser -> {
            // The wording of the permission banner is reused, so the declaration reads the same on every screen.
            val text = permissionBanner(ctx.profile, PermissionCheckStatus.Done(result))
            TopologyBanner(TopologyBannerKind.DeclaredNotConfirmed, text.headline, text.detail)
        }
        is PermissionCheckResult.Unreadable -> TopologyBanner(TopologyBannerKind.NotReadable, TopologyTexts.LOCKED_HEADLINE, result.reason)
    }
}

// --- row texts ---------------------------------------------------------------------------------------

private fun count(value: Long?): String = value?.let(::groupDigits) ?: TopologyTexts.NOT_AVAILABLE

private fun membersOnlineText(row: QueueRow): String =
    if (row.members.isEmpty()) TopologyTexts.NOT_APPLICABLE else "${row.membersOnline} of ${row.membersTotal}"

private fun queueView(row: QueueRow): QueueRowView = QueueRowView(
    name = row.name,
    type = row.type,
    ready = count(row.readyMessages),
    unacked = count(row.unackedMessages),
    consumers = count(row.consumers?.toLong()),
    leader = row.leader ?: TopologyTexts.NOT_APPLICABLE,
    membersOnline = membersOnlineText(row),
    memberOffline = row.hasOfflineMember,
)

private fun typeText(type: String): String = when (type) {
    "quorum" -> "Quorum queue"
    "classic" -> "Classic queue"
    else -> type
}

private fun QueueRow.detail(allBindings: List<BindingRow>): QueueDetailView = QueueDetailView(
    name = name,
    typeText = typeText(type),
    leader = leader ?: TopologyTexts.NOT_APPLICABLE,
    members = members.joinToString(", ") { it.removePrefix(RABBIT_NODE_PREFIX) }.ifEmpty { TopologyTexts.NOT_APPLICABLE },
    membersOnline = membersOnlineText(this),
    readyUnacked = "${count(readyMessages)} / ${count(unackedMessages)}",
    consumers = count(consumers?.toLong()),
    policy = policy ?: TopologyTexts.NOT_APPLICABLE,
    // Explicit bindings only: the implicit one of the default exchange is not a binding anybody made.
    bindings = allBindings
        .filter { it.destinationType == BindingDestinationType.QUEUE && it.destination == name && it.source.isNotEmpty() }
        .map(::bindingView),
    memberOffline = hasOfflineMember,
)

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun exchangeView(row: ExchangeRow): ExchangeRowView = ExchangeRowView(
    name = if (row.isDefault) TopologyTexts.DEFAULT_EXCHANGE else row.name,
    type = row.type,
    durable = yesNo(row.durable),
    autoDelete = yesNo(row.autoDelete),
    internal = yesNo(row.internal),
    // The flag set by the parser's one rule; the argument text cannot tell the JSON boolean from the string "true".
    superStream = row.superStream,
    isDefault = row.isDefault,
)

private fun argumentsText(arguments: Map<String, String>): String =
    arguments.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" }

private fun bindingView(row: BindingRow): BindingRowView = BindingRowView(
    source = if (row.source.isEmpty()) TopologyTexts.DEFAULT_EXCHANGE else row.source,
    destination = row.destination,
    destinationType = when (row.destinationType) {
        BindingDestinationType.QUEUE -> "queue"
        BindingDestinationType.EXCHANGE -> "exchange"
    },
    routingKey = row.routingKey,
    arguments = argumentsText(row.arguments),
)
