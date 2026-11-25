package dev.caerbannog.core.access.fakes

import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.WhoAmI
import dev.caerbannog.core.connections.fakes.CallGate
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * M10-02 test double for [AccessGateway]. Lives in `core-testing`, so the unit tests and the Gherkin steps use the same fake. No network.
 *
 * Honest by design: it answers like the measured broker (M10-00, S-A). A user without a management tag gets [AccessResult.NoManagementTag] on EVERY
 * call (the broker answers 401 on whoami and on vhosts alike); a refused sign-in is [AccessResult.NotAuthorised]; a broker that is down is
 * [AccessResult.Unreachable]. The virtual host list of a non-administrator holds only the hosts the broker lists for it ([listedVhosts]), in broker
 * order (the fake does NOT sort: the view model owns the order). Every call is recorded in [requests] in order ("whoami", "vhosts"), and a test can
 * hold the next call ([holdNext]) to finish it late without any sleep. The fake only has reads: there is nothing in it that could change a broker.
 */
class FakeAccessGateway : AccessGateway {
    @Volatile var user: String = "svc-orders"
    @Volatile var tags: List<String> = listOf("monitoring")
    @Volatile var internalUser: Boolean = true

    /** The names `GET /api/vhosts` lists for the user, in the broker's order. */
    @Volatile var listedVhosts: List<String> = emptyList()

    /** The broker answers 401 "Not management user" on every call. */
    @Volatile var hasManagementTag: Boolean = true

    /** The broker answers a plain 401 (wrong or expired credentials) on every call. */
    @Volatile var refusesSignIn: Boolean = false

    /** The broker does not answer at all: every call is [AccessResult.Unreachable]. */
    @Volatile var unreachable: Boolean = false

    /** When set, `whoami` answers this instead of the user (for example `Unexpected("HTTP 500")`). */
    @Volatile var whoamiFailure: AccessResult<WhoAmI>? = null

    /** When set, `vhosts` answers this instead of the list (for example `Unexpected("HTTP 500")`); whoami still answers. */
    @Volatile var vhostsFailure: AccessResult<List<String>>? = null

    /** Names of the calls in order: "whoami", "vhosts". Thread-safe; tests may clear it. */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val gate = AtomicReference<Pair<String, CallGate>?>(null)

    /** The next call named [call] ("whoami" or "vhosts") waits until the gate is released. */
    fun holdNext(call: String, ignoreCancellation: Boolean = false): CallGate {
        val g = CallGate(ignoreCancellation)
        gate.set(call to g)
        return g
    }

    override suspend fun whoAmI(): AccessResult<WhoAmI> {
        enter("whoami")
        return common() ?: whoamiFailure ?: AccessResult.Loaded(WhoAmI(user, tags, internalUser))
    }

    override suspend fun virtualHosts(): AccessResult<List<String>> {
        enter("vhosts")
        return common() ?: vhostsFailure ?: AccessResult.Loaded(listedVhosts.toList())
    }

    private fun common(): AccessResult<Nothing>? = when {
        unreachable -> AccessResult.Unreachable
        !hasManagementTag -> AccessResult.NoManagementTag
        refusesSignIn -> AccessResult.NotAuthorised
        else -> null
    }

    private suspend fun enter(call: String) {
        requests += call
        val held = gate.get()
        if (held != null && held.first == call && gate.compareAndSet(held, null)) held.second.await()
    }
}
