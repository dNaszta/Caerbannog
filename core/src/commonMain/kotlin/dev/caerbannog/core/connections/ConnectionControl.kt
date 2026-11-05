package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which icon the chip or a popup row shows. Icon and text are always both there; colour is never the only signal. */
enum class ChipIcon { LinkOff, Sync, Link, Error }

/** The five chip states of the table in "Connection control" (a sealed type, not boolean flags). */
sealed interface ChipState {
    data object NoConnection : ChipState
    data object Connecting : ChipState
    data object Connected : ChipState
    data object Failed : ChipState
    data object Disconnected : ChipState
}

/** The one action of a popup row. */
sealed interface RowAction {
    data object Connect : RowAction
    data object Disconnect : RowAction
    data object Retry : RowAction
    data object None : RowAction
}

/**
 * The closed chip. [text] is the connection name, or "No connection". [stateText] is the visible state word next to the
 * name, only for [ChipState.Disconnected] ("Disconnected"), otherwise null. [pill] is null when hidden. [tooltip] is the
 * failure reason or the settings-changed note, otherwise null.
 */
data class ConnectionChipState(
    val kind: ChipState,
    val icon: ChipIcon,
    val text: String,
    val stateText: String?,
    val pill: PermissionPill?,
    val tooltip: String?,
    val accessibleLabel: String,
    val errorBorder: Boolean,
    /**
     * M2-14 the current profile has TLS off. The chip then shows [ConnectionNotes.NOT_ENCRYPTED]
     * in EVERY state of that profile (decision O1) and the accessible label ends its state part with ", Not encrypted"
     * (`Connection dev-local, Connected, can change topology, Not encrypted. Open connection menu`). "No connection" has no profile,
     * so it never shows the warning.
     */
    val notEncrypted: Boolean = false,
)

/** One saved profile in the popup. [stateText] is "Connected", "Disconnected", "Connecting" or "Failed: <reason>". */
data class ConnectionRowState(
    val name: String,
    val icon: ChipIcon,
    val stateText: String,
    val action: RowAction,
    val current: Boolean,
    /** The settings-changed note of a Disconnected row, otherwise null. */
    val note: String? = null,
    /** M2-14 the profile of this row has TLS off; the row shows [ConnectionNotes.NOT_ENCRYPTED] in every state. */
    val notEncrypted: Boolean = false,
)

/** "Stream list refresh" section. [note] is "Connect to refresh", "Not loaded yet" or "Updated N s ago". */
data class RefreshSectionState(
    val selected: RefreshInterval,
    val enabled: Boolean,
    val note: String,
)

data class ConnectionControlState(
    val chip: ConnectionChipState,
    val rows: List<ConnectionRowState>,
    /** Set (to "No saved connections") when there is no saved profile; the popup then offers "New connection". */
    val emptyText: String?,
    val refresh: RefreshSectionState,
)

/** Events out of the chip and its popup. Every one is asynchronous: the effect shows in the state after the scope ran it. */
interface ConnectionControlEvents {
    fun connect(name: String)
    fun disconnect(name: String)
    fun retry(name: String)
    fun setRefreshInterval(interval: RefreshInterval)
    fun refreshNow()
    /** "Manage connections" and "New connection": opens the Connections screen. */
    fun openConnections()
}

/** Texts of the chip and its popup, one place (tests assert the literals). */
object ConnectionControlTexts {
    const val NO_CONNECTION: String = "No connection"
    const val NO_SAVED_CONNECTIONS: String = "No saved connections"
    const val NEW_CONNECTION: String = "New connection"
    const val SECTION_CONNECTIONS: String = "Connections"
    const val SECTION_REFRESH: String = "Stream list refresh"
    const val REFRESH_EVERY: String = "Refresh every"
    const val REFRESH_NOW: String = "Refresh now"
    const val CONNECT_TO_REFRESH: String = "Connect to refresh"
    const val NOT_LOADED_YET: String = "Not loaded yet"
    const val MANAGE_CONNECTIONS: String = "Manage connections"
    const val CONNECTED: String = "Connected"
    const val CONNECTING: String = "Connecting"
    const val DISCONNECTED: String = "Disconnected"
    const val FAILED: String = "Failed"
    const val CONNECT: String = "Connect"
    const val DISCONNECT: String = "Disconnect"
    const val RETRY: String = "Retry"
    const val OPEN_MENU: String = "Open connection menu"
}

/**
 * The pure rule of the chip and the popup (no clock of its own, no scope): [current] is the session's current connection
 * (name and permission status), [link] the state of [ActiveConnection], [updatedAtMillis] and [nowMillis] give "Updated N s ago".
 * A link state about another profile than the row's counts as Disconnected for that row (only the held connection has a
 * state; a profile that failed keeps its failure only while it is the current one).
 */
fun connectionControlState(
    profiles: List<ConnectionProfile>,
    current: CurrentConnection?,
    link: LinkState,
    refreshInterval: RefreshInterval,
    updatedAtMillis: Long?,
    nowMillis: Long,
): ConnectionControlState {
    // Only the current connection can have a state other than Disconnected.
    val currentLink: LinkState? = current?.let { link.takeIf { l -> l.name == it.name } ?: LinkState.Disconnected(it.name) }
    return ConnectionControlState(
        chip = chipOf(current, currentLink, notEncrypted = profiles.firstOrNull { it.name == current?.name }?.tls == false),
        rows = profiles.map {
            rowOf(it.name, it.name == current?.name, if (it.name == current?.name) currentLink else null).copy(notEncrypted = !it.tls)
        },
        emptyText = ConnectionControlTexts.NO_SAVED_CONNECTIONS.takeIf { profiles.isEmpty() },
        refresh = refreshOf(currentLink, refreshInterval, updatedAtMillis, nowMillis),
    )
}

private fun chipOf(current: CurrentConnection?, link: LinkState?, notEncrypted: Boolean): ConnectionChipState {
    if (current == null || link == null) {
        return ConnectionChipState(
            kind = ChipState.NoConnection,
            icon = ChipIcon.LinkOff,
            text = ConnectionControlTexts.NO_CONNECTION,
            stateText = null,
            pill = null,
            tooltip = null,
            accessibleLabel = "${ConnectionControlTexts.NO_CONNECTION}. ${ConnectionControlTexts.OPEN_MENU}",
            errorBorder = false,
        )
    }
    val kind = when (link) {
        is LinkState.Connecting -> ChipState.Connecting
        is LinkState.Connected -> ChipState.Connected
        is LinkState.Failed -> ChipState.Failed
        is LinkState.Disconnected -> ChipState.Disconnected
    }
    // The pill is the permission of a connection that is up or coming up, once its check answered.
    val pill = (current.status as? PermissionCheckStatus.Done)
        ?.takeIf { kind == ChipState.Connecting || kind == ChipState.Connected }
        ?.let { permissionPill(it) }
    val word = stateWord(kind)
    val permission = pill?.let { ", " + it.text.replaceFirstChar { c -> c.lowercase() } }.orEmpty()
    val warning = if (notEncrypted) ", ${ConnectionNotes.NOT_ENCRYPTED}" else ""
    return ConnectionChipState(
        kind = kind,
        icon = iconOf(kind),
        text = current.name,
        stateText = ConnectionControlTexts.DISCONNECTED.takeIf { kind == ChipState.Disconnected },
        pill = pill,
        tooltip = when (link) {
            is LinkState.Failed -> link.reason
            is LinkState.Disconnected -> link.note
            else -> null
        },
        accessibleLabel = "Connection ${current.name}, $word$permission$warning. ${ConnectionControlTexts.OPEN_MENU}",
        errorBorder = kind == ChipState.Failed,
        notEncrypted = notEncrypted,
    )
}

private fun rowOf(name: String, current: Boolean, link: LinkState?): ConnectionRowState = when (link) {
    is LinkState.Connected -> ConnectionRowState(name, ChipIcon.Link, ConnectionControlTexts.CONNECTED, RowAction.Disconnect, current)
    is LinkState.Connecting -> ConnectionRowState(name, ChipIcon.Sync, ConnectionControlTexts.CONNECTING, RowAction.None, current)
    is LinkState.Failed ->
        ConnectionRowState(name, ChipIcon.Error, "${ConnectionControlTexts.FAILED}: ${link.reason}", RowAction.Retry, current)
    is LinkState.Disconnected ->
        ConnectionRowState(name, ChipIcon.LinkOff, ConnectionControlTexts.DISCONNECTED, RowAction.Connect, current, link.note)
    null -> ConnectionRowState(name, ChipIcon.LinkOff, ConnectionControlTexts.DISCONNECTED, RowAction.Connect, current)
}

private fun refreshOf(link: LinkState?, interval: RefreshInterval, updatedAtMillis: Long?, nowMillis: Long): RefreshSectionState =
    if (link is LinkState.Connected) {
        val note = updatedAtMillis?.let { "Updated ${(nowMillis - it).coerceAtLeast(0) / MILLIS_PER_SECOND} s ago" }
            ?: ConnectionControlTexts.NOT_LOADED_YET
        RefreshSectionState(interval, enabled = true, note = note)
    } else {
        RefreshSectionState(interval, enabled = false, note = ConnectionControlTexts.CONNECT_TO_REFRESH)
    }

private fun stateWord(kind: ChipState): String = when (kind) {
    ChipState.Connecting -> ConnectionControlTexts.CONNECTING
    ChipState.Connected -> ConnectionControlTexts.CONNECTED
    ChipState.Failed -> ConnectionControlTexts.FAILED
    ChipState.Disconnected, ChipState.NoConnection -> ConnectionControlTexts.DISCONNECTED
}

private fun iconOf(kind: ChipState): ChipIcon = when (kind) {
    ChipState.Connecting -> ChipIcon.Sync
    ChipState.Connected -> ChipIcon.Link
    ChipState.Failed -> ChipIcon.Error
    ChipState.Disconnected, ChipState.NoConnection -> ChipIcon.LinkOff
}

private const val MILLIS_PER_SECOND: Long = 1000

/**
 * Follows the connections, the held connection and the stream list, and turns them into [state]; the events call the existing
 * view models (Connect on another profile disconnects the held one by the `ActiveConnection` rule, no new logic).
 * [tick] recomputes the state with the clock (the UI calls it once a second while the app bar is shown), so "Updated N s ago" ages.
 * Every event hops onto [scope]; read results from [state].
 */
class ConnectionControlModel(
    private val connections: ConnectionsViewModel,
    private val active: ActiveConnection,
    private val streamList: StreamListViewModel,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val onOpenConnections: () -> Unit,
    /** The screen on show (list or tab): its age and its Refresh now drive the control. Null: the stream list (M2-05 behaviour). COMPILE STUB, not read yet. */
    private val shownTarget: kotlinx.coroutines.flow.StateFlow<dev.caerbannog.core.streams.RefreshTarget>? = null,
) : ConnectionControlEvents, AutoCloseable {
    private val ticks = MutableStateFlow(0L)
    private val _state = MutableStateFlow(compute())

    /** What the chip and the popup show. */
    val state: StateFlow<ConnectionControlState> = _state.asStateFlow()

    // Problem solved: three independent sources (profiles and session, link, stream list) and the clock feed one derived value.
    private val following: Job = scope.launch {
        combine(connections.state, active.state, streamList.state, ticks) { _, _, _, _ -> Unit }
            .collect { _state.value = compute() }
    }

    private fun compute(): ConnectionControlState {
        val shown = connections.state.value
        return connectionControlState(
            profiles = shown.profiles,
            current = shown.currentConnection,
            link = active.state.value,
            refreshInterval = streamList.state.value.refreshInterval,
            updatedAtMillis = streamList.state.value.updatedAtMillis,
            nowMillis = clock.nowMillis(),
        )
    }

    /** Recomputes the state with the clock; the UI ticker calls it once a second. Makes no broker call. */
    fun tick() {
        ticks.update { it + 1 }
    }

    override fun connect(name: String) {
        scope.launch { connections.connect(name) }
    }

    override fun disconnect(name: String) {
        scope.launch { if (active.state.value.name == name) active.disconnect() }
    }

    override fun retry(name: String) {
        scope.launch { connections.connect(name) }
    }

    override fun setRefreshInterval(interval: RefreshInterval) = streamList.setRefreshInterval(interval)

    override fun refreshNow() = streamList.refreshNow()

    override fun openConnections() {
        scope.launch { onOpenConnections() }
    }

    override fun close() {
        following.cancel()
    }
}
