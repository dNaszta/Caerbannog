package dev.caerbannog.core.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Holds at most one connection to a broker and tells what is going on in one [LinkState] (spec "Connection
 * lifecycle"). Uses [ConnectionSession] to select the profile and run the permission check, then the
 * [ConnectionGateway] to open the clients.
 *
 * Latest started change wins: every connect, disconnect, edit, delete and close takes the next generation
 * number; a connect that finds its number outdated closes whatever the broker opened for it and writes
 * nothing. The generation, the held connection and the state change together under a short lock (pure field
 * operations, never held across a suspension or a broker call); readers of [state] never take it.
 */
class ActiveConnection(
    private val session: ConnectionSession,
    private val gateway: ConnectionGateway,
) {
    private val lock = Mutex()
    private var generation = 0L
    private var held: HeldConnection? = null
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected())

    /** The state of the connection the app holds or tried to hold last. Read it for the app bar chip. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** [state] if it is about [name], otherwise [LinkState.Disconnected] for that name (profile cards). */
    fun stateOf(name: String): LinkState = _state.value.takeIf { it.name == name } ?: LinkState.Disconnected(name)

    // Runs [block] under the lock; the block must not suspend or call out.
    private fun <T> locked(block: () -> T): T {
        // Spin on tryLock like ConnectionSession: a few field writes, and this stays non-suspending.
        while (!lock.tryLock()) Unit
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // Starts a new generation and hands out the connection that was held, for the caller to close.
    private fun supersede(next: LinkState): HeldConnection? {
        generation++
        _state.value = next
        return held.also { held = null }
    }

    /**
     * Closes any held connection, selects [profile] in the session, runs the permission check, then opens the
     * clients. Never throws except [CancellationException] (the state then ends Disconnected unless a newer
     * change owns it). A missing stored password fails here, before the gateway is called.
     */
    suspend fun connect(profile: ConnectionProfile) {
        val name = profile.name
        var mine = 0L
        val previous = locked {
            // Select inside the lock so the newest connect also owns the session's current connection.
            val old = supersede(LinkState.Connecting(name))
            mine = generation
            session.select(name)
            old
        }
        previous.closeQuietly()

        val permissions = try {
            session.open(profile)
        } catch (e: CancellationException) {
            abandon(mine, name)
            throw e
        }
        if (permissions == PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD)) {
            finishIfCurrent(mine, LinkState.Failed(name, ConnectionFailureReason.NO_PASSWORD_STORED))
            return
        }
        if (locked { generation != mine }) return

        val opened = try {
            openClients(profile)
        } catch (e: CancellationException) {
            abandon(mine, name)
            throw e
        }
        when (opened) {
            is OpenResult.Failed -> finishIfCurrent(mine, LinkState.Failed(name, opened.reason))
            is OpenResult.Opened -> {
                val keep = locked {
                    (generation == mine).also {
                        if (it) {
                            held = opened.connection
                            _state.value = LinkState.Connected(name)
                        }
                    }
                }
                if (!keep) opened.connection.closeQuietly()
            }
        }
    }

    // The one documented broad catch, at the boundary: nothing a gateway throws may escape Connect or leave it
    // stuck in Connecting. The message is dropped on purpose, it could carry a secret or a URL.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun openClients(profile: ConnectionProfile): OpenResult = try {
        gateway.open(profile)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        OpenResult.Failed(ConnectionFailureReason.UNEXPECTED)
    }

    private fun finishIfCurrent(mine: Long, next: LinkState) {
        locked { if (generation == mine) _state.value = next }
    }

    // A cancelled connect must not leave Connecting behind, unless a newer change owns the state.
    private fun abandon(mine: Long, name: String) {
        finishIfCurrent(mine, LinkState.Disconnected(name))
    }

    /** Closes the held connection, if any. The session's current connection stays; the state is Disconnected. */
    fun disconnect() {
        val old = locked {
            val now = _state.value
            // Already idle: keep the state (and its note); still a new generation so a running connect backs off.
            supersede(if (now is LinkState.Disconnected) now else LinkState.Disconnected(now.name))
        }
        old.closeQuietly()
    }

    /** Window close: same as [disconnect]; safe to call more than once. */
    fun close() = disconnect()

    /** A saved change to [name] (edit, rename, CA): the held or connecting connection has old settings, so it is closed. */
    fun profileChanged(name: String) = endIfAbout(name, LinkState.Disconnected(name, ConnectionNotes.SETTINGS_CHANGED))

    /** [name] was deleted: the held or connecting connection is closed, without a note. */
    fun profileDeleted(name: String) = endIfAbout(name, LinkState.Disconnected(name))

    private fun endIfAbout(name: String, next: LinkState) {
        val old = locked {
            val now = _state.value
            val active = now.name == name && (now is LinkState.Connecting || now is LinkState.Connected)
            if (active) supersede(next) else null
        }
        old.closeQuietly()
    }

    // A failing close must not turn Disconnect into an exception or keep the state wrong; the connection
    // is dropped from our side either way.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun HeldConnection?.closeQuietly() {
        try {
            this?.close()
        } catch (e: Exception) {
            // nothing more can be done with a connection that cannot be closed
        }
    }
}
