package dev.caerbannog.core.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gate that runs the permission check before a connection counts as usable, keeping results per profile
 * name (each profile is its own environment). Holds the only rule that applies a user declaration.
 */
class ConnectionSession(
    private val checker: PermissionChecker,
    private val secretStore: SecretStore,
) {
    // The check runs outside any lock; the last check to finish wins per name. A state flow gives atomic,
    // lock-free updates while isUsable/permissions stay non-suspend.
    private val results = MutableStateFlow<Map<String, PermissionCheckResult>>(emptyMap())

    /**
     * Runs the check; the connection is not usable until it finishes. Never throws, except
     * [CancellationException] (the connection then stays not usable): any other failure becomes
     * [UnreadableCause.UNEXPECTED], which is locked like every unreadable result.
     */
    suspend fun open(profile: ConnectionProfile): PermissionCheckResult {
        results.update { it - profile.name }
        val result = check(profile)
        results.update { it + (profile.name to result) }
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

    /** Drops the stored result, e.g. for the old name after a rename. */
    fun forget(name: String) {
        results.update { it - name }
    }

    fun isUsable(name: String): Boolean = results.value.containsKey(name)

    fun permissions(name: String): PermissionCheckResult? = results.value[name]

    private fun applyDeclaration(profile: ConnectionProfile, result: PermissionCheckResult): PermissionCheckResult {
        val declared = profile.declaredPermissions
        return if (result is PermissionCheckResult.Unreadable && declared != null) decideDeclared(declared, result) else result
    }
}
