package dev.caerbannog.acceptance.permissions

import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.decidePermissions

/**
 * Fake broker for 02_permission_check.feature. Behaves like the real management API as the M1-03 spike
 * found it: it needs the profile's password (a missing one is refused, like a 401), and it answers either
 * with the user's permission patterns or with a refusal. The decision from patterns to locked/unlocked is
 * NOT done here: the fake returns what `decidePermissions` (core) says, so the real rule is what the
 * scenarios exercise. Records every call and what the app looked like at the moment of the call.
 */
class FakePermissionChecker : PermissionChecker {
    var permissions: BrokerPermissions? = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
    var refusal: String? = null

    /** Called inside [check], i.e. while the broker is being asked; lets a step observe the app's state then. */
    var onCheck: (() -> Unit)? = null

    val calls = mutableListOf<Pair<ConnectionProfile, String>>()

    override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
        calls += profile to password
        onCheck?.invoke()
        refusal?.let { return PermissionCheckResult.Unreadable(it) }
        if (password.isEmpty()) return PermissionCheckResult.Unreadable("the broker rejected the credentials")
        return decidePermissions(checkNotNull(permissions) { "fake has neither permissions nor a refusal" })
    }
}
