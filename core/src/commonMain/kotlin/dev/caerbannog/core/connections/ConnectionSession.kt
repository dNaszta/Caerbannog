package dev.caerbannog.core.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The connection the user is working with: its name and what is known about its permission check. */
data class CurrentConnection(
    val name: String,
    /** Null means not checked yet; [PermissionCheckStatus.Checking] and [PermissionCheckStatus.Done] otherwise. */
    val status: PermissionCheckStatus?,
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
    // One immutable snapshot, changed only through atomic updates, so a check, a forget and a select can
    // never see each other half-done. Tokens: every started check gets the next number; only the latest
    // started check per name may write its result.
    private data class Snapshot(
        val statuses: Map<String, PermissionCheckStatus> = emptyMap(),
        val tokens: Map<String, Long> = emptyMap(),
        val lastToken: Long = 0,
        val currentName: String? = null,
    ) {
        val current: CurrentConnection? get() = currentName?.let { CurrentConnection(it, statuses[it]) }
    }

    private val snapshot = MutableStateFlow(Snapshot())
    private val _statuses = MutableStateFlow<Map<String, PermissionCheckStatus>>(emptyMap())
    private val _current = MutableStateFlow<CurrentConnection?>(null)

    /** Checking from the start of [open], Done at the end; no entry means not checked. Only the latest started check shows. */
    val statuses: StateFlow<Map<String, PermissionCheckStatus>> = _statuses.asStateFlow()

    /** The connection chosen with [select], or null; cleared by [forget] of that name. */
    val current: StateFlow<CurrentConnection?> = _current.asStateFlow()

    private fun change(transform: (Snapshot) -> Snapshot) {
        snapshot.update(transform)
        publish()
    }

    // The public flows are copies of the snapshot. Two threads may publish out of order, so each one
    // re-reads until what it wrote is the latest snapshot: the last writer always converges.
    private fun publish() {
        while (true) {
            val seen = snapshot.value
            _statuses.value = seen.statuses
            _current.value = seen.current
            if (snapshot.value === seen) return
        }
    }

    /** Makes [name] the current connection without a check or a lookup; shows its stored result at once, if any. */
    fun select(name: String) {
        change { it.copy(currentName = name) }
    }

    /**
     * Runs the check; the connection is not usable until it finishes. Never throws, except
     * [CancellationException] (the connection then stays not usable): any other failure becomes
     * [UnreadableCause.UNEXPECTED], which is locked like every unreadable result.
     *
     * Only the latest STARTED check per name is kept: if a newer check of the same name started, or the name
     * was forgotten, this check's result is dropped from session state. The return value is always this
     * check's own result, so callers must read [statuses], [current] or [permissions] for what is current.
     */
    suspend fun open(profile: ConnectionProfile): PermissionCheckResult {
        val name = profile.name
        var token = 0L
        change {
            token = it.lastToken + 1
            it.copy(
                statuses = it.statuses + (name to PermissionCheckStatus.Checking),
                tokens = it.tokens + (name to token),
                lastToken = token,
            )
        }
        val result = try {
            check(profile)
        } catch (e: CancellationException) {
            // A cancelled check must not leave "Checking" behind, unless a newer check owns the name.
            change { if (it.tokens[name] == token) it.copy(statuses = it.statuses - name, tokens = it.tokens - name) else it }
            throw e
        }
        change {
            if (it.tokens[name] == token) it.copy(statuses = it.statuses + (name to PermissionCheckStatus.Done(result))) else it
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

    /** Drops the stored result and invalidates a running check, e.g. for the old name after a rename or a delete. */
    fun forget(name: String) {
        change {
            it.copy(
                statuses = it.statuses - name,
                tokens = it.tokens - name,
                currentName = it.currentName.takeUnless { current -> current == name },
            )
        }
    }

    /** True once a check finished; Checking is not usable. */
    fun isUsable(name: String): Boolean = snapshot.value.statuses[name] is PermissionCheckStatus.Done

    /** The finished result, or null while checking or not checked. */
    fun permissions(name: String): PermissionCheckResult? = (snapshot.value.statuses[name] as? PermissionCheckStatus.Done)?.result

    private fun applyDeclaration(profile: ConnectionProfile, result: PermissionCheckResult): PermissionCheckResult {
        val declared = profile.declaredPermissions
        return if (result is PermissionCheckResult.Unreadable && declared != null) decideDeclared(declared, result) else result
    }
}
