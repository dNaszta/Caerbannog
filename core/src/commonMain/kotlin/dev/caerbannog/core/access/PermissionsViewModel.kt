package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.DeclaredPermissions
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
 * State holder of the Permissions tab (M10-03; spec "Access view (M10)", "Broker-read permission", "Declared permissions"). Same threading and refresh rules
 * as [AccessViewModel] (every public method hops onto [scope]; the timer, the alive check and the D2 mapping are the shared `RefreshLoop`; the stale and banner
 * rules are the shared [AccessBanners]). Rules:
 * - The lock is decided by `whoami.tags`: without `administrator` no user or permission request is sent at all.
 * - An administrator reads the user list (names only), the virtual hosts the broker lists, and the permissions of the chosen user. A periodic refresh re-reads the
 *   chosen user only; the user list is read again on Retry. The choice is per tab visit and not stored.
 * - A token administrator (not an internal user, tag `administrator`) lands on its own name, which the broker answers with 404 (the token text).
 * - A user name the guard hid is offered as a disabled option keyed by its position; the real name never reaches this class, so it can never be requested.
 * Nothing here can change the broker: [AccessGateway] has reads only.
 *
 * @param reconnect Retry on a lost link: connect the profile of that name again.
 * @param context the held profile, for the Not encrypted banner and the declaration only (never for what loads).
 */
class PermissionsViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<AccessContext?>,
) : AutoCloseable, RefreshTarget {
    private var link: LinkState = active.state.value
    private var source: StreamSource? = active.source
    private var shown = false
    private var closed = false

    private var who: WhoAmI? = null
    private var options: List<UserOption>? = null
    private var vhostNames: List<String> = emptyList()
    private var vhostsReason: String? = null
    private var chosen: String? = null
    private var result: Result? = null
    private var locked = false
    private var lockedUser: String? = null
    private var failure: Failure? = null
    private var needFull = true
    private var gatewayRefused = false
    private var userFilter = ""
    private var vhostFilter = ""
    private val banners = AccessBanners()

    private sealed interface Result {
        data class Rows(val permissions: UserPermissions) : Result

        /** The broker answered 404 for the chosen user: a token user. */
        data object Token : Result
    }

    private class Failure(val reason: String, val message: String)

    private val loop = RefreshLoop(
        scope = scope,
        active = active,
        isCurrent = { !closed && source === it },
        interval = { settings.streamListRefresh },
        loadOnce = ::loadOnce,
    )

    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    private val following: Job
    private val followingContext: Job

    init {
        render()
        following = scope.launch {
            combine(active.state, active.sources) { l, s -> l to s }.collect { (l, s) -> onLink(l, s) }
        }
        followingContext = scope.launch { context.collect { render() } }
    }

    /** The Permissions tab is shown or not; nothing is called while it is not. Showing it again starts a new visit (default user, no filters). */
    fun setShown(shown: Boolean) = onScope {
        if (closed || this.shown == shown) return@onScope
        this.shown = shown
        if (shown) {
            clearData()
            render()
            if (canRun()) source?.let(loop::loadNow)
        } else {
            loop.cancel()
        }
    }

    override fun refreshNow() = onScope { refresh() }

    /** Failed link: connect again. Failed read: read again, the user list included. */
    fun retry() = onScope {
        val current = link
        when {
            closed -> Unit
            current is LinkState.Failed -> scope.launch { reconnect(current.name) }
            else -> {
                needFull = true
                refresh()
            }
        }
    }

    /** Chooses the user of [UserOption.key]; a hidden option or an unknown key changes nothing. */
    fun chooseUser(key: String) = onScope {
        val option = options?.firstOrNull { it.key == key }
        if (closed || option == null || option.hidden || option.name == chosen) return@onScope
        chosen = option.name
        result = null
        failure = null
        banners.clear()
        render()
        if (canRun()) source?.let(loop::loadNow)
    }

    fun setUserFilter(text: String) = onScope {
        userFilter = text
        render()
    }

    fun setVhostFilter(text: String) = onScope {
        vhostFilter = text
        render()
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

    private fun offered(): Boolean = !gatewayRefused && !isMigrationSourceOnly(source?.brokerVersion)

    private fun onLink(newLink: LinkState, newSource: StreamSource?) {
        if (closed || (newSource === source && newLink == link)) return
        link = newLink
        source = newSource
        loop.cancel()
        if (newLink is LinkState.Failed && newLink.reason == ConnectionFailureReason.CONNECTION_LOST) {
            // D2: the data of the last read stays, marked stale, with the banner.
            banners.connectionLost(newLink.reason, hasData = result != null)
        } else {
            clearData()
        }
        render()
        if (canRun()) source?.let(loop::loadNow)
    }

    private fun clearData() {
        who = null
        options = null
        vhostNames = emptyList()
        vhostsReason = null
        chosen = null
        result = null
        locked = false
        lockedUser = null
        failure = null
        needFull = true
        gatewayRefused = false
        userFilter = ""
        vhostFilter = ""
        banners.clear()
    }

    // --- loading -------------------------------------------------------------------------------------

    private suspend fun loadOnce(src: StreamSource) {
        if (failure != null && result == null) {
            failure = null
            render()
        }
        if (needFull && !readIdentity(src)) return
        readChosen(src)
    }

    private suspend fun <T> ask(src: StreamSource, call: suspend () -> AccessResult<T>): AccessResult<T>? =
        askAccess(loop, active, src, onNotOffered = {
            loop.cancel()
            clearData()
            gatewayRefused = true
            render()
        }, call)

    /** The user, the lock, the user list and the virtual hosts. False: stop here (locked, refused, unreadable or dropped). */
    private suspend fun readIdentity(src: StreamSource): Boolean {
        val user = when (val answer = ask(src) { src.access.whoAmI() } ?: return false) {
            is AccessResult.Loaded -> answer.value
            AccessResult.NoManagementTag -> return lock(null)
            AccessResult.NotAuthorised, AccessResult.NotFound -> return refused()
            is AccessResult.Unexpected -> return unreadable(answer.reason)
            else -> return false
        }
        // The tags decide the lock and no permission request is sent (nothing is guessed from other calls).
        if (ADMINISTRATOR !in user.tags) return lock(user.name)
        locked = false
        lockedUser = null
        who = user

        val names = when (val answer = ask(src) { src.access.users() } ?: return false) {
            is AccessResult.Loaded -> answer.value.names
            AccessResult.NoManagementTag, AccessResult.NotAuthorised, AccessResult.NotFound -> return refused()
            is AccessResult.Unexpected -> return unreadable(answer.reason)
            else -> return false
        }
        // An unreadable list only adds the empty hosts: the broker's own rows still show, and the banner says the list could not be read.
        when (val answer = ask(src) { src.access.virtualHosts() } ?: return false) {
            is AccessResult.Loaded -> {
                vhostNames = answer.value
                vhostsReason = null
            }
            is AccessResult.Unexpected -> {
                vhostNames = emptyList()
                vhostsReason = answer.reason
            }
            AccessResult.NoManagementTag, AccessResult.NotAuthorised, AccessResult.NotFound -> return refused()
            else -> return false
        }
        val offered = offer(names, user)
        options = offered
        val keep = chosen?.takeIf { name -> offered.any { it.name == name && !it.hidden } }
        chosen = keep ?: defaultUser(offered, user)
        needFull = false
        render()
        return true
    }

    // Alphabetical, case-insensitive; a token administrator's own name is the extra entry. A hidden name is keyed by its position in this list.
    private fun offer(names: List<String>, user: WhoAmI): List<UserOption> {
        val own = if (isTokenAdministrator(user) && user.name != SecretGuard.HIDDEN && user.name !in names) listOf(user.name) else emptyList()
        val ordered = (names + own).sortedWith(BY_NAME)
        return ordered.mapIndexed { index, name ->
            if (name == SecretGuard.HIDDEN) UserOption(name, "hidden:$index", true) else UserOption(name, name, false)
        }
    }

    private fun defaultUser(offered: List<UserOption>, user: WhoAmI): String? {
        val own = offered.firstOrNull { !it.hidden && it.name == user.name }
        return when {
            own != null -> own.name
            else -> offered.firstOrNull { !it.hidden }?.name
        }
    }

    private fun isTokenAdministrator(user: WhoAmI): Boolean = !user.isInternalUser && ADMINISTRATOR in user.tags

    private suspend fun readChosen(src: StreamSource) {
        val user = chosen
        if (user == null) {
            failure = Failure(PermissionsTexts.NO_USER, PermissionsTexts.NO_USER)
            return render()
        }
        val answer = ask(src) { src.access.permissionsOf(user) } ?: return
        // A late answer for a user that is no longer the chosen one is dropped.
        if (chosen != user) return
        when (answer) {
            is AccessResult.Loaded -> succeeded(Result.Rows(answer.value))
            AccessResult.NotFound -> succeeded(Result.Token)
            AccessResult.NoManagementTag, AccessResult.NotAuthorised -> refused()
            is AccessResult.Unexpected -> unreadable(answer.reason)
            else -> Unit
        }
        render()
    }

    private fun succeeded(read: Result) {
        result = read
        failure = null
        banners.succeeded(clock.nowMillis())
        // The virtual host list stays unread until the next full read, so the banner that says so stays too.
        vhostsReason?.let { banners.unreadable(hasData = true, banner = "${AccessTexts.VHOSTS_ERROR} ${AccessTexts.brokerText(it)}.") }
    }

    // No administrator tag (or no management tag at all): the whole tab is the lock, and what was shown may be stale, so it goes.
    private fun lock(user: String?): Boolean {
        clearData()
        locked = true
        lockedUser = user
        render()
        return false
    }

    private fun refused(): Boolean {
        if (!banners.refused(hasData = result != null)) failure = Failure(AccessTexts.SIGN_IN_REFUSED, AccessTexts.SIGN_IN_REFUSED)
        render()
        return false
    }

    private fun unreadable(reason: String): Boolean {
        if (!banners.unreadable(hasData = result != null, banner = "${PermissionsTexts.ERROR} ${AccessTexts.brokerText(reason)}.")) {
            failure = Failure(reason, PermissionsTexts.ERROR)
        }
        render()
        return false
    }

    // --- rendering -----------------------------------------------------------------------------------

    private fun screen(): PermissionsScreen {
        val current = link
        val read = result
        return when {
            current is LinkState.Failed -> if (read != null) resultScreen(read) else PermissionsScreen.Failed(current.reason)
            current is LinkState.Disconnected || !offered() -> PermissionsScreen.NotConnected
            current is LinkState.Connecting || source == null -> PermissionsScreen.Connecting
            locked -> PermissionsScreen.Locked
            failure != null -> PermissionsScreen.Failed(failure?.reason.orEmpty())
            read != null -> resultScreen(read)
            else -> PermissionsScreen.Loading
        }
    }

    private fun resultScreen(read: Result): PermissionsScreen = when (read) {
        Result.Token -> PermissionsScreen.TokenUser
        is Result.Rows -> if (read.permissions.rows.isEmpty() && read.permissions.topicRows.isEmpty()) PermissionsScreen.Empty else PermissionsScreen.Loaded
    }

    private fun render() {
        val screen = screen()
        val user = chosen
        val rows = (result as? Result.Rows)?.permissions
        val loaded = screen is PermissionsScreen.Loaded
        val withData = screen is PermissionsScreen.Loaded || screen is PermissionsScreen.Empty || screen is PermissionsScreen.TokenUser
        val offered = options.orEmpty()
        val chooser = !locked && offered.isNotEmpty() &&
            (withData || screen is PermissionsScreen.Failed || screen is PermissionsScreen.Loading)
        val visible = offered.filter { userFilter.isBlank() || it.name.contains(userFilter.trim(), ignoreCase = true) }
        _updatedAt.value = banners.lastSuccessMillis
        _state.value = PermissionsState(
            screen = screen,
            message = when (screen) {
                PermissionsScreen.NotConnected -> AccessTexts.NOT_CONNECTED
                PermissionsScreen.Connecting -> AccessTexts.CONNECTING
                PermissionsScreen.Loading -> PermissionsTexts.LOADING
                PermissionsScreen.Locked -> PermissionsTexts.LOCKED_TITLE
                PermissionsScreen.Loaded -> null
                PermissionsScreen.Empty -> PermissionsTexts.empty(user.orEmpty())
                PermissionsScreen.TokenUser -> PermissionsTexts.TOKEN_TITLE
                is PermissionsScreen.Failed -> failure?.message ?: screen.reason
            },
            detail = when {
                screen is PermissionsScreen.Locked -> lockedDetail()
                screen is PermissionsScreen.TokenUser -> PermissionsTexts.tokenDetail(user.orEmpty())
                screen is PermissionsScreen.Failed && failure != null -> AccessTexts.brokerText(screen.reason).takeIf { screen.reason != failure?.message }
                else -> null
            },
            heading = if (loaded) PermissionsTexts.heading(user.orEmpty()) else null,
            readLabel = if (loaded) PermissionsTexts.READ_FROM_BROKER else null,
            canChooseUser = chooser,
            users = if (chooser) visible else emptyList(),
            userFilter = if (chooser) userFilter else "",
            userCountText = if (chooser) PermissionsTexts.userCount(visible.size, offered.size) else null,
            chosenUser = if (withData || screen is PermissionsScreen.Failed || screen is PermissionsScreen.Loading) user else null,
            vhostFilter = vhostFilter,
            rows = if (loaded && rows != null) tableRows(rows) else emptyList(),
            topicRows = if (loaded && rows != null) topicTableRows(rows) else emptyList(),
            declaration = if (screen is PermissionsScreen.Locked) declaration() else null,
            action = if (needsRetry(screen, withData)) AccessTexts.RETRY else null,
            stale = withData && banners.stale,
            connectionBanner = if (withData) banners.connection else null,
            refusalBanner = if (withData) banners.refusal else null,
            readErrorBanner = if (withData) banners.readError else null,
            notEncrypted = if (link is LinkState.Disconnected) null else notEncryptedNote(context.value),
        )
    }

    private fun needsRetry(screen: PermissionsScreen, withData: Boolean): Boolean =
        link is LinkState.Failed || screen is PermissionsScreen.Failed || (withData && (banners.stale || banners.any))

    private fun lockedDetail(): String = when {
        declaration() != null -> lockedUser?.let(PermissionsTexts::lockedDetail) ?: AccessTexts.LOCKED_DETAIL
        lockedUser == null -> AccessTexts.LOCKED_DETAIL
        else -> PermissionsTexts.LOCKED_DETAIL_NO_DECLARATION
    }

    private fun declaration(): DeclarationView? = context.value?.profile?.declaredPermissions?.let(::declarationView)

    private fun declarationView(declared: DeclaredPermissions): DeclarationView = DeclarationView(
        label = PermissionsTexts.DECLARED_LABEL,
        configure = yesNo(declared.configure),
        write = yesNo(declared.write),
        // The profile has no read flag, so read is never claimed.
        read = PermissionsTexts.DECLARED_NOT_DECLARED,
        note = PermissionsTexts.DECLARED_NOTE,
    )

    private fun yesNo(value: Boolean): String = if (value) PermissionsTexts.DECLARED_YES else PermissionsTexts.DECLARED_NO

    // One row per host: the broker's rows (never dropped) plus the listed hosts that have none ("no permission" everywhere). A hidden host name cannot be
    // told apart from the broker's own hidden row, so a listed hidden host adds nothing.
    private fun tableRows(read: UserPermissions): List<PermissionRow> {
        val none = PatternCell("", PatternReading.NoPermission)
        val have = read.rows.map { it.vhost }.toSet()
        val empty = vhostNames.filter { it != SecretGuard.HIDDEN && it !in have }.distinct().map { PermissionRow(it, none, none, none) }
        return (read.rows + empty).sortedWith(compareBy(BY_NAME) { it.vhost }).filter(::matchesVhost)
    }

    private fun topicTableRows(read: UserPermissions): List<TopicPermissionRow> =
        read.topicRows.sortedWith(compareBy(BY_NAME) { it.vhost }).filter { matchesVhost(it.vhost) }

    private fun matchesVhost(vhost: String): Boolean = vhostFilter.isBlank() || vhost.contains(vhostFilter.trim(), ignoreCase = true)

    private fun matchesVhost(row: PermissionRow): Boolean = matchesVhost(row.vhost)

    private companion object {
        const val ADMINISTRATOR = "administrator"
        val BY_NAME: Comparator<String> = compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
    }
}
