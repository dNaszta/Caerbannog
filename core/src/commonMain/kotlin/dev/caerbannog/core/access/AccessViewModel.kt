package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.connections.isMigrationSourceOnly
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshLoop
import dev.caerbannog.core.streams.RefreshTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * State holder of the read-only Access screen, Who am I tab (M10-02; spec "Access view (M10)"). One long-lived instance per app, like
 * `TopologyViewModel`, whose threading and refresh rules it mirrors: every public method hops onto [scope], so every field is touched on the
 * scope's thread only (the effect of a call is visible in [state] after the scope has run it); the timer, the load token, the alive check and the
 * D2 mapping of an unreachable broker are the shared [RefreshLoop]. It reads `StreamSource.access` of the held connection only while shown (D3).
 * A known 3.x connection is never asked (AQ6): [AccessState.offered] is false and, when Access was open, [AccessState.leaveNotice] is set once.
 * Nothing here can change the broker: [AccessGateway] has reads only.
 *
 * @param reconnect Retry on a lost link: connect the profile of that name again.
 * @param context the held profile, for the Not encrypted banner only (never for what loads).
 */
class AccessViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<AccessContext?>,
) : AutoCloseable, RefreshTarget {
    private var link: LinkState = active.state.value
    private var source: StreamSource? = active.source
    // The connection's own virtual host, kept when the link is lost so the stale list keeps its order and mark.
    private var home: String? = source?.vhost
    private var shown = false
    private var closed = false

    // What was read. A null user means no data: nothing yet, locked or failed.
    private var who: WhoAmI? = null
    private var vhostNames: List<String> = emptyList()
    private var vhostsStatus = VhostsStatus.Loading
    private var vhostsReason: String? = null
    private var locked = false
    private var failure: Failure? = null
    private var lastSuccessMillis: Long? = null
    private var stale = false
    private var connectionBanner: String? = null
    private var refusalBanner: String? = null
    private var readErrorBanner: String? = null
    private var gatewayRefused = false
    private var leaveNotice: String? = null

    private class Failure(val reason: String, val message: String)

    private val loop = RefreshLoop(
        scope = scope,
        active = active,
        isCurrent = { !closed && source === it },
        interval = { settings.streamListRefresh },
        loadOnce = ::loadOnce,
    )

    private val _state = MutableStateFlow(AccessState())
    val state: StateFlow<AccessState> = _state.asStateFlow()
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

    /** The Access screen is shown or not; nothing is called while it is not. */
    fun setShown(shown: Boolean) = onScope {
        if (closed || this.shown == shown) return@onScope
        this.shown = shown
        if (shown) {
            if (!offered()) {
                leaveNotice = AccessTexts.NEEDS_4X
                render()
            } else if (canRun()) {
                source?.let(loop::loadNow)
            }
        } else {
            loop.cancel()
        }
    }

    override fun refreshNow() = onScope { refresh() }

    /** Failed link: connect again. Failed read: read again. */
    fun retry() = onScope {
        val current = link
        when {
            closed -> Unit
            current is LinkState.Failed -> scope.launch { reconnect(current.name) }
            else -> refresh()
        }
    }

    /** The one-shot [AccessState.leaveNotice] was shown on Connections. */
    fun noticeShown() = onScope {
        if (leaveNotice != null) {
            leaveNotice = null
            render()
        }
    }

    override fun close() {
        onScope {
            closed = true
            loop.cancel()
            following.cancel()
            followingContext.cancel()
        }
    }

    private fun onScope(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun refresh() {
        if (canRun() && !loop.loading) source?.let(loop::loadNow)
    }

    private fun canRun(): Boolean = !closed && shown && link is LinkState.Connected && source != null && offered()

    // True when nothing is held. A known 3.x broker is never asked (AQ6); an unknown version is (same rule as the migration source).
    private fun offered(): Boolean = !gatewayRefused && !isMigrationSourceOnly(source?.brokerVersion)

    private fun onLink(newLink: LinkState, newSource: StreamSource?) {
        if (closed || (newSource === source && newLink == link)) return
        link = newLink
        source = newSource
        loop.cancel()
        if (newLink is LinkState.Failed && newLink.reason == ConnectionFailureReason.CONNECTION_LOST) {
            // D2: the data of the last read stays, marked stale, with the banner.
            if (who != null) {
                stale = true
                connectionBanner = newLink.reason
                readErrorBanner = null
            }
        } else {
            clearData()
            home = newSource?.vhost
        }
        if (shown && !offered()) leaveNotice = AccessTexts.NEEDS_4X
        render()
        if (canRun()) source?.let(loop::loadNow)
    }

    private fun clearData() {
        who = null
        vhostNames = emptyList()
        vhostsStatus = VhostsStatus.Loading
        vhostsReason = null
        locked = false
        failure = null
        lastSuccessMillis = null
        stale = false
        connectionBanner = null
        refusalBanner = null
        readErrorBanner = null
        gatewayRefused = false
    }

    // --- loading -------------------------------------------------------------------------------------

    private suspend fun loadOnce(src: StreamSource) {
        if (who == null && !locked && failure != null) {
            failure = null
            render()
        }
        val user = when (val result = ask(src) { src.access.whoAmI() } ?: return) {
            is AccessResult.Loaded -> result.value
            AccessResult.NoManagementTag -> return lock()
            AccessResult.NotAuthorised -> return refused()
            is AccessResult.Unexpected -> return unreadable(result.reason)
            else -> return
        }
        locked = false
        failure = null
        who = user
        render()

        val names = when (val result = ask(src) { src.access.virtualHosts() } ?: return) {
            is AccessResult.Loaded -> result.value
            AccessResult.NoManagementTag -> return lock()
            AccessResult.NotAuthorised -> return refused()
            is AccessResult.Unexpected -> {
                // The user and tags stay; only the list shows the error.
                succeeded()
                vhostNames = emptyList()
                vhostsStatus = VhostsStatus.Unreadable
                vhostsReason = result.reason
                return render()
            }
            else -> return
        }
        succeeded()
        vhostNames = names
        vhostsStatus = if (names.isEmpty()) VhostsStatus.Empty else VhostsStatus.Listed
        vhostsReason = null
        render()
    }

    private fun succeeded() {
        lastSuccessMillis = clock.nowMillis()
        stale = false
        refusalBanner = null
        connectionBanner = null
        readErrorBanner = null
    }

    /**
     * One call. Null: drop the result (superseded or closed), the connection was reported lost (D2), or the gateway answered [AccessResult.NotOffered]
     * (the app leaves Access; this is not a failure and shows no error).
     */
    private suspend fun <T> ask(src: StreamSource, call: suspend () -> AccessResult<T>): AccessResult<T>? {
        val result = call()
        if (!loop.alive(src)) return null
        return when (result) {
            AccessResult.Unreachable -> {
                active.connectionLost(src)
                null
            }
            AccessResult.NotOffered -> {
                loop.cancel()
                clearData()
                gatewayRefused = true
                if (shown) leaveNotice = AccessTexts.NEEDS_4X
                render()
                null
            }
            else -> result
        }
    }

    // No management tag, on either call: the whole screen is the lock, and what was shown may be stale, so it goes.
    private fun lock() {
        clearData()
        locked = true
        render()
    }

    // NotAuthorised: with data the last data stays, dimmed, with the refusal banner; without, the screen has nothing to show.
    private fun refused() {
        if (who != null) {
            stale = true
            refusalBanner = AccessTexts.SIGN_IN_REFUSED
            readErrorBanner = null
        } else {
            failure = Failure(AccessTexts.SIGN_IN_REFUSED, AccessTexts.SIGN_IN_REFUSED)
        }
        render()
    }

    private fun unreadable(reason: String) {
        if (who == null) {
            failure = Failure(reason, AccessTexts.USER_ERROR)
        } else {
            stale = true
            // Precedence: refusal, then lost connection, then read error; a read that was answered cannot coexist with the other two.
            if (refusalBanner == null && connectionBanner == null) readErrorBanner = "${AccessTexts.USER_ERROR} ${AccessTexts.brokerText(reason)}."
        }
        render()
    }

    // --- rendering -----------------------------------------------------------------------------------

    private fun screen(): AccessScreen {
        val current = link
        return when {
            current is LinkState.Failed -> if (who != null) AccessScreen.Loaded else AccessScreen.Failed(current.reason)
            current is LinkState.Disconnected || !offered() -> AccessScreen.NotConnected
            current is LinkState.Connecting || source == null -> AccessScreen.Connecting
            locked -> AccessScreen.Locked
            who != null -> AccessScreen.Loaded
            failure != null -> AccessScreen.Failed(failure?.reason.orEmpty())
            else -> AccessScreen.Loading
        }
    }

    private fun vhostRows(): List<VhostRowView> {
        val byName = compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
        val ordered = vhostNames.filter { it == home }.take(1) + vhostNames.filter { it != home }.sortedWith(byName)
        return ordered.map { VhostRowView(it, if (it == home) AccessTexts.CONNECTION_VHOST_NOTE else null) }
    }

    private fun render() {
        val screen = screen()
        val loaded = screen is AccessScreen.Loaded
        val link = link
        val user = if (loaded) who else null
        _updatedAt.value = lastSuccessMillis
        _state.value = AccessState(
            screen = screen,
            message = when (screen) {
                AccessScreen.NotConnected -> AccessTexts.NOT_CONNECTED
                AccessScreen.Connecting -> AccessTexts.CONNECTING
                AccessScreen.Loading -> AccessTexts.LOADING
                AccessScreen.Locked -> AccessTexts.LOCKED_TITLE
                AccessScreen.Loaded -> null
                is AccessScreen.Failed -> failure?.message ?: screen.reason
            },
            detail = when {
                screen is AccessScreen.Locked -> AccessTexts.LOCKED_DETAIL
                screen is AccessScreen.Failed && failure != null -> AccessTexts.brokerText(screen.reason).takeIf { screen.reason != failure?.message }
                else -> null
            },
            readOnlyNote = if (loaded || screen is AccessScreen.Loading) AccessTexts.READ_ONLY_NOTE else null,
            user = user?.name,
            tags = user?.tags.orEmpty(),
            tokenUser = user != null && !user.isInternalUser,
            vhostsStatus = if (loaded) vhostsStatus else if (screen is AccessScreen.Loading) VhostsStatus.Loading else VhostsStatus.None,
            vhosts = if (loaded) vhostRows() else emptyList(),
            vhostsMessage = if (!loaded) null else when (vhostsStatus) {
                VhostsStatus.Empty -> AccessTexts.NO_VHOSTS
                VhostsStatus.Unreadable -> AccessTexts.VHOSTS_ERROR
                else -> null
            },
            vhostsReason = if (loaded && vhostsStatus == VhostsStatus.Unreadable) vhostsReason else null,
            action = if (needsRetry(screen, link)) AccessTexts.RETRY else null,
            stale = loaded && stale,
            connectionBanner = if (loaded) connectionBanner else null,
            refusalBanner = if (loaded) refusalBanner else null,
            readErrorBanner = if (loaded) readErrorBanner else null,
            notEncrypted = if (link is LinkState.Disconnected) null else notEncrypted(),
            offered = offered(),
            leaveNotice = leaveNotice,
        )
    }

    private fun needsRetry(screen: AccessScreen, link: LinkState): Boolean =
        link is LinkState.Failed || screen is AccessScreen.Failed ||
            (screen is AccessScreen.Loaded && (vhostsStatus == VhostsStatus.Unreadable || stale || refusalBanner != null || readErrorBanner != null))

    private fun notEncrypted(): String? = if (context.value?.profile?.tls == false) ConnectionNotes.NOT_ENCRYPTED else null
}
