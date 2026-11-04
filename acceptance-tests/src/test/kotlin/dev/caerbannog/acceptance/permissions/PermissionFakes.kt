package dev.caerbannog.acceptance.permissions

import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.decidePermissions
import kotlinx.coroutines.CompletableDeferred

/**
 * Fake broker for 02_permission_check.feature. Behaves like the real management API as the M1-03 spike
 * found it: it needs the profile's password (a missing one is refused, like a 401), and it answers either
 * (never DeclaredByUser: that is the session's job) with the user's permission patterns or with a refusal. The decision from patterns to locked/unlocked is
 * NOT done here: the fake returns what `decidePermissions` (core) says, so the real rule is what the
 * scenarios exercise. Records every call and what the app looked like at the moment of the call.
 */
class FakePermissionChecker : PermissionChecker {
    var permissions: BrokerPermissions? = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
    /** Set to make the broker refuse; the fake supplies only a cause, core owns the wording. */
    var refusal: UnreadableCause? = null

    /** Called inside [check], i.e. while the broker is being asked; lets a step observe the app's state then. */
    var onCheck: (() -> Unit)? = null

    /** M2-01: permissions of one connection by profile name; wins over [permissions] for that name. */
    val permissionsByName = mutableMapOf<String, BrokerPermissions>()

    val calls = mutableListOf<Pair<ConnectionProfile, String>>()

    private var holdNext = false
    private var held: CompletableDeferred<Unit>? = null

    /**
     * M2-01: the next check is asked the broker now (its answer is fixed at that moment) but only returns after
     * [releaseHeld]. Only one check can be held at a time.
     */
    fun holdNextCheck() {
        check(held == null) { "a check is already held" }
        holdNext = true
    }

    fun releaseHeld() {
        checkNotNull(held) { "no check is held" }.complete(Unit)
    }

    override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
        calls += profile to password
        onCheck?.invoke()
        val answer = answer(profile, password)
        if (holdNext) {
            holdNext = false
            val gate = CompletableDeferred<Unit>()
            held = gate
            gate.await()
            held = null
        }
        return answer
    }

    private fun answer(profile: ConnectionProfile, password: String): PermissionCheckResult {
        refusal?.let { return PermissionCheckResult.Unreadable(it) }
        if (password.isEmpty()) return PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        val granted = permissionsByName[profile.name] ?: permissions
        return decidePermissions(checkNotNull(granted) { "fake has neither permissions nor a refusal" })
    }
}
