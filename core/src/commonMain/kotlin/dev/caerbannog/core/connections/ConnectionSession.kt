package dev.caerbannog.core.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** The connection the user is working with: its name and what is known about its permission check. */
data class CurrentConnection(
    val name: String,
    /** Null means not checked yet; [PermissionCheckStatus.Checking] and [PermissionCheckStatus.Done] otherwise. */
    val status: PermissionCheckStatus?,
)

/** Everything a reader may see of a [ConnectionSession], in one immutable value. */
data class SessionState(
    /** Checking from the start of a check, Done at the end; no entry means not checked. */
    val statuses: Map<String, PermissionCheckStatus> = emptyMap(),
    /** The chosen connection, or null; its status is always the one in [statuses]. */
    val current: CurrentConnection? = null,
)

/**
 * Gate that runs the permission check before a connection counts as usable, keeping results per profile
 * name (each profile is its own environment), and the single source of truth for which connection is
 * current. Holds the only rule that applies a user declaration.
 */
class ConnectionSession(
    private val checker: PermissionChecker,
    private val secretStore: SecretStore,
) {
    // Tokens: every started check gets the next number; only the latest started check per name may write
    // its result. They are private bookkeeping next to the public state, so a change touches two values and
    // is serialized by a short lock (pure map operations, never held across a suspension). Readers of
    // [state] never take the lock and always see one complete SessionState.
    private data class Tokens(val byName: Map<String, Long> = emptyMap(), val last: Long = 0)

    private val lock = Mutex()
    private var tokens = Tokens()
    private val _state = MutableStateFlow(SessionState())
    private val listeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    /**
     * The one public view of the session: statuses (Checking from the start of [open], Done at the end; no
     * entry means not checked; only the latest started check shows) and the current connection chosen with
     * [select] (cleared by [forget] of that name). Every value is complete: `current.status` always equals
     * the status of that name.
     */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Calls [onChange] on the writing thread after every change of [state], so a holder that derives its own
     * state can follow synchronously without owning a coroutine scope. The callback should read [state] (the
     * latest value), not rely on the order of calls. Returns the function that unregisters it; the session
     * keeps a reference until then, so a holder that outlives its use must call it. Observers must not throw
     * and must not call [ActiveConnection] or this session: they run on the writing thread, after the change.
     */
    fun observe(onChange: () -> Unit): () -> Unit {
        listeners.update { it + onChange }
        return { listeners.update { current -> current - onChange } }
    }

    // Runs [transform] on the tokens and the public state as one step; null means no change.
    private fun change(transform: (Tokens, SessionState) -> Pair<Tokens, SessionState>?) {
        // Spin on tryLock: the critical section is a few map operations, and this stays non-suspending.
        while (!lock.tryLock()) {
            // spin
        }
        try {
            val next = transform(tokens, _state.value) ?: return
            tokens = next.first
            _state.value = next.second.withCurrentStatus()
        } finally {
            lock.unlock()
        }
        listeners.value.forEach { it() }
    }

    // `current` is derived from statuses and the chosen name, so the pair cannot drift.
    private fun SessionState.withCurrentStatus(): SessionState =
        current?.let { copy(current = it.copy(status = statuses[it.name])) } ?: this

    /** Makes [name] the current connection without a check or a lookup; shows its stored result at once, if any. */
    fun select(name: String) {
        change { t, s -> t to s.copy(current = CurrentConnection(name, null)) }
    }

    /**
     * Runs the check; the connection is not usable until it finishes. Never throws, except
     * [CancellationException] (the connection then stays not usable): any other failure becomes
     * [UnreadableCause.UNEXPECTED], which is locked like every unreadable result.
     *
     * Only the latest STARTED check per name is kept: if a newer check of the same name started, or the name
     * was forgotten, this check's result is dropped from session state. The return value is always this
     * check's own result, so callers must read [state] or [permissions] for what is current.
     */
    suspend fun open(profile: ConnectionProfile): PermissionCheckResult {
        val name = profile.name
        var token = 0L
        change { t, s ->
            token = t.last + 1
            Tokens(t.byName + (name to token), token) to s.copy(statuses = s.statuses + (name to PermissionCheckStatus.Checking))
        }
        val result = try {
            check(profile)
        } catch (e: CancellationException) {
            // A cancelled check must not leave "Checking" behind, unless a newer check owns the name.
            change { t, s -> if (t.byName[name] == token) Tokens(t.byName - name, t.last) to s.copy(statuses = s.statuses - name) else null }
            throw e
        }
        change { t, s ->
            if (t.byName[name] == token) t to s.copy(statuses = s.statuses + (name to PermissionCheckStatus.Done(result))) else null
        }
        return result
    }

    // The one documented broad catch, at the boundary: nothing a checker or keychain does may escape and
    // lose a save result or leave a connection unlocked. Only the class name is kept, never the message
    // (it could carry a password or URL).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun check(profile: ConnectionProfile): PermissionCheckResult {
        val password = try {
            secretStore.retrieve(profile.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same as NO_PASSWORD: without a password nothing can connect, so no declaration applies.
            return PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED, e::class.simpleName)
        }
            ?: return PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD)
        val checked = try {
            checker.check(profile, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED, e::class.simpleName)
        }
        return applyDeclaration(profile, checked)
    }

    /** Same as [open]; named separately so a save can be seen to trigger a second check. */
    suspend fun reopen(profile: ConnectionProfile): PermissionCheckResult = open(profile)

    /**
     * Replaces the finished result of [name] with [PermissionCheckResult.MigrationSource]. The version is only known after the clients
     * opened, which is after [open] produced its result, so this is a second step. Same token rule as [open]: only a finished check of
     * [name] is replaced, so a newer check or [forget] wins (nothing is written then).
     */
    fun markMigrationSource(name: String, version: String) {
        change { t, s ->
            if (t.byName[name] != null && s.statuses[name] is PermissionCheckStatus.Done) {
                t to s.copy(statuses = s.statuses + (name to PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource(version))))
            } else null
        }
    }

    /** Drops the stored result and invalidates a running check, e.g. for the old name after a rename or a delete. */
    fun forget(name: String) {
        change { t, s ->
            Tokens(t.byName - name, t.last) to
                s.copy(statuses = s.statuses - name, current = s.current.takeUnless { it?.name == name })
        }
    }

    /** True once a check finished; Checking is not usable. */
    fun isUsable(name: String): Boolean = _state.value.statuses[name] is PermissionCheckStatus.Done

    /** The finished result, or null while checking or not checked. */
    fun permissions(name: String): PermissionCheckResult? = (_state.value.statuses[name] as? PermissionCheckStatus.Done)?.result

    private fun applyDeclaration(profile: ConnectionProfile, result: PermissionCheckResult): PermissionCheckResult {
        val declared = profile.declaredPermissions
        return if (result is PermissionCheckResult.Unreadable && declared != null) decideDeclared(declared, result) else result
    }
}
