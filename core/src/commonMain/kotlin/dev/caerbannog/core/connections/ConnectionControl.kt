package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// M2-05b step 2: COMPILE STUBS ONLY (test developer). The types, the texts and the signatures are the contract the tests
// are written against; every behaviour is `TODO(...)` and is the senior developer's to build. See
// docs/tasks/M2-05-stream-list-screen.md, "Connection control", and "Step 2 report, 05b".

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
): ConnectionControlState = TODO("M2-05b: the table of 'Connection control' (senior developer)")

/**
 * Follows the connections, the held connection and the stream list, and turns them into [state]; the events call the existing
 * view models (Connect on another profile disconnects the held one by the `ActiveConnection` rule, no new logic).
 * [tick] recomputes the state with the clock (the UI calls it once a second while the app bar is shown), so "Updated N s ago" ages.
 */
class ConnectionControlModel(
    connections: ConnectionsViewModel,
    active: ActiveConnection,
    streamList: StreamListViewModel,
    scope: CoroutineScope,
    clock: RefreshClock,
    onOpenConnections: () -> Unit,
) : ConnectionControlEvents, AutoCloseable {
    val state: StateFlow<ConnectionControlState> get() = TODO("M2-05b")
    fun tick(): Unit = TODO("M2-05b")
    override fun connect(name: String): Unit = TODO("M2-05b")
    override fun disconnect(name: String): Unit = TODO("M2-05b")
    override fun retry(name: String): Unit = TODO("M2-05b")
    override fun setRefreshInterval(interval: RefreshInterval): Unit = TODO("M2-05b")
    override fun refreshNow(): Unit = TODO("M2-05b")
    override fun openConnections(): Unit = TODO("M2-05b")
    override fun close() {
        // stub: nothing to release yet (the real one stops following the connections)
    }
}
