package dev.caerbannog.core.connections

import dev.caerbannog.core.migrate3x.GuardedDefinitionsSource
import dev.caerbannog.core.migrate3x.GuardedThreeXReads
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.NoThreeXReads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 *
 * Observers (collectors of [state]) must not throw and must not call this class or the session: they run on the
 * writing thread, inside the change.
 */
class ActiveConnection(
    private val session: ConnectionSession,
    private val gateway: ConnectionGateway,
    /** The pause between storage version reads of the live source; production waits, tests pass a recording no-op (no sleep). */
    private val liveSourcePause: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var generation = 0L
    @Volatile
    private var held: HeldConnection? = null
    private val _source = MutableStateFlow<StreamSource?>(null)
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected())

    /** The state of the connection the app holds or tried to hold last. Read it for the app bar chip. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /**
     * The catalog and offsets of the held connection, only while Connected, otherwise null. Never the
     * [HeldConnection] itself (it is closable, the UI must not be able to close it). A source of a connection
     * that was closed or superseded answers "unreachable" and touches nothing.
     */
    val source: StreamSource? get() = _source.value

    /**
     * [source] as a flow. Why it exists next to [state]: reconnecting the same profile gives an equal
     * `Connected(name)`, so a collector of [state] cannot tell the old connection from the new one; a
     * [StreamSource] is compared by reference, so every new connection is a new value. Written before [state] on
     * connect and after it on every end, so a reader that combines both sees at worst a transient "Connecting".
     */
    val sources: StateFlow<StreamSource?> = _source.asStateFlow()

    /** [state] if it is about [name], otherwise [LinkState.Disconnected] for that name (profile cards). */
    fun stateOf(name: String): LinkState = _state.value.takeIf { it.name == name } ?: LinkState.Disconnected(name)

    // Runs [block] under the lock; the block must not suspend or call out.
    private fun <T> locked(block: () -> T): T {
        // Spin on tryLock like ConnectionSession: a few field writes, and this stays non-suspending.
        while (!lock.tryLock()) {
            // spin
        }
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
        _source.value = null
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
                            // Before Connected, in the same step: no reader sees a connected 3.x broker that is not locked yet.
                            // Only the current connect gets here, so a superseded one never overwrites a newer check. Lock order is
                            // always ActiveConnection, then session; the session's observers never call back into this class.
                            markIfMigrationSource(name, opened.connection.brokerVersion)
                            _source.value = guardedSource(opened.connection, name, profile.virtualHost, profile.hasMqtt)
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

    // The permission result was made before the clients opened, so the version (from the one overview read) arrives only now.
    private fun markIfMigrationSource(name: String, version: String?) {
        // The cleaned text only: an odd version is unknown, so it is not marked and never shown.
        val shown = displayableBrokerVersion(version)
        if (shown != null && isMigrationSourceOnly(shown)) session.markMigrationSource(name, shown)
    }

    private fun guardedSource(connection: HeldConnection, connectionName: String, vhost: String, hasMqtt: Boolean): StreamSource {
        val isCurrent = { held === connection }
        val shownVersion = displayableBrokerVersion(connection.brokerVersion)
        return StreamSource(
            catalog = GuardedCatalog(connection.catalog, isCurrent),
            offsets = GuardedOffsets(connection.offsets, isCurrent),
            vhost = vhost,
            topology = GuardedTopology(connection.topology, isCurrent),
            messages = GuardedMessageReader(connection.messages, isCurrent),
            publisher = GuardedPublisher(connection.publisher, isCurrent),
            manager = GuardedStreamManager(connection.manager, isCurrent),
            mqtt = GuardedMqttCatalog(connection.mqtt, isCurrent),
            mqttPublisher = GuardedMqttPublisher(connection.mqttPublisher, isCurrent),
            queueInspector = GuardedQueueMessageInspector(connection.queueInspector, isCurrent),
            migrationExecutor = connection.migrationExecutor?.let { GuardedMigrationExecutor(it, isCurrent) },
            declareCheck = if (connection.migrationExecutor == null) null else connection.declareCheck?.let { GuardedDeclareCheck(it, isCurrent) },
            policyReader = if (connection.migrationExecutor == null) null else connection.policyReader?.let { GuardedPolicyReader(it, isCurrent) },
            migrationUnavailableReason = connection.migrationUnavailableReason,
            brokerVersion = shownVersion,
            // Built for every connection: a source that is not a readable 3.x one refuses before any read (never a null to test for).
            definitionsSource = GuardedDefinitionsSource(
                LiveDefinitionsSource(
                    connectionName,
                    shownVersion,
                    connection.threeXReads?.let { GuardedThreeXReads(it, isCurrent) } ?: NoThreeXReads,
                    liveSourcePause,
                ),
                isCurrent,
            ),
            shovels = connection.shovels,
            access = GuardedAccessGateway(connection.access, isCurrent),
            hasMqtt = hasMqtt,
        )
    }

    /**
     * D2 (decided 2026-11-04, the one place to change): the view model saw the broker unreachable on a data
     * call made with [source]. If [source] is still the current one, the link becomes Failed("Connection lost") and
     * the held connection is closed; otherwise (superseded, already lost) nothing happens. No permission check, no
     * reconnect. Call it from your own coroutine, never from an observer of [state].
     */
    fun connectionLost(source: StreamSource) {
        val old = locked {
            if (_source.value === source) supersede(LinkState.Failed(_state.value.name.orEmpty(), ConnectionFailureReason.CONNECTION_LOST)) else null
        }
        old.closeQuietly()
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
