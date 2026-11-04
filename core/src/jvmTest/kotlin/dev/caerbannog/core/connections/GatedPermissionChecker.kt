package dev.caerbannog.core.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * M2-01 test double: every check blocks until the test finishes it, so overlapping checks can be released in
 * either order with no sleeps. [nextStarted] hands out the checks in the order the broker was asked.
 */
internal class GatedPermissionChecker : PermissionChecker {

    class Pending(val profile: ConnectionProfile, private val gate: CompletableDeferred<PermissionCheckResult>) {
        fun finish(result: PermissionCheckResult) {
            gate.complete(result)
        }
    }

    private val started = Channel<Pending>(Channel.UNLIMITED)

    override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
        val gate = CompletableDeferred<PermissionCheckResult>()
        started.trySend(Pending(profile, gate))
        return gate.await()
    }

    suspend fun nextStarted(): Pending = withTimeout(TIMEOUT_MS) { started.receive() }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
