package dev.caerbannog.core.connections

/**
 * Gate that runs the permission check before a connection counts as usable, keeping results per profile
 * name (each profile is its own environment). Holds the only rule that applies a user declaration.
 */
class ConnectionSession(
    private val checker: PermissionChecker,
    private val secretStore: SecretStore,
) {
    private val results = mutableMapOf<String, PermissionCheckResult>()

    /** Runs the check; the connection is not usable until it finishes. */
    suspend fun open(profile: ConnectionProfile): PermissionCheckResult {
        results.remove(profile.name)
        val password = secretStore.retrieve(profile.name)
        val result = if (password == null) {
            PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD)
        } else {
            applyDeclaration(profile, checker.check(profile, password))
        }
        results[profile.name] = result
        return result
    }

    /** Same as [open]; named separately so a save can be seen to trigger a second check. */
    suspend fun reopen(profile: ConnectionProfile): PermissionCheckResult = open(profile)

    /** Drops the stored result, e.g. for the old name after a rename. */
    fun forget(name: String) {
        results.remove(name)
    }

    fun isUsable(name: String): Boolean = results.containsKey(name)

    fun permissions(name: String): PermissionCheckResult? = results[name]

    private fun applyDeclaration(profile: ConnectionProfile, result: PermissionCheckResult): PermissionCheckResult {
        val declared = profile.declaredPermissions
        return if (result is PermissionCheckResult.Unreadable && declared != null) decideDeclared(declared, result) else result
    }
}
