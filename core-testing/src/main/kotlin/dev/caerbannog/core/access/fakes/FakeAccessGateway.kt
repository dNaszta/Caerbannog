package dev.caerbannog.core.access.fakes

import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserNames
import dev.caerbannog.core.access.UserPermissions
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
 *
 * M10-03 (measured by the capture `RealBrokerAccessUsersSpikeTest`, fixtures `s-b-users-*`, `s-b-topic-permissions-*`, `s-b-user-permissions-*`): `users()` and
 * `permissionsOf()` answer [AccessResult.NotAuthorised] to a user without the `administrator` tag (the broker's 401 "Not administrator user" is not the
 * management-tag 401), `permissionsOf` of a name that is not an internal user (a token user, an unknown name) answers [AccessResult.NotFound] (404), a user
 * with no row answers an empty [UserPermissions], an empty pattern is [PatternReading.NoPermission], and like the real gateway every name and pattern passes
 * [SecretGuard] (so a name that looks like a token comes out as the hidden marker). The fake keeps no hash and no tag of any user: there is nothing to leak.
 * Requests are recorded as "whoami", "vhosts", "users" and "permissions:<user>" (the real gateway does two GETs for that last one).
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

    /** The connection is a known 3.x broker as the real gateway sees it: every call is [AccessResult.NotOffered] and nothing is sent. */
    @Volatile var notOffered: Boolean = false

    /** The broker does not answer at all: every call is [AccessResult.Unreachable]. */
    @Volatile var unreachable: Boolean = false

    /** When set, `whoami` answers this instead of the user (for example `Unexpected("HTTP 500")`). */
    @Volatile var whoamiFailure: AccessResult<WhoAmI>? = null

    /** When set, `vhosts` answers this instead of the list (for example `Unexpected("HTTP 500")`); whoami still answers. */
    @Volatile var vhostsFailure: AccessResult<List<String>>? = null

    /** M10-03: the internal users `GET /api/users` lists, in the broker's order. A token user is never in it. */
    @Volatile var internalUsers: List<String> = emptyList()

    /** M10-03: the held secret the guard is given, as the real gateway reads it per call. */
    @Volatile var heldSecret: String? = null

    /** M10-03: when set, `users` answers this instead of the list (for example `Unexpected("HTTP 500")`). */
    @Volatile var usersFailure: AccessResult<UserNames>? = null

    /** M10-03: when set, `permissionsOf` answers this for every user (for example `Unexpected("HTTP 500")`). */
    @Volatile var permissionsFailure: AccessResult<UserPermissions>? = null

    private class Perm(val user: String, val vhost: String, val configure: String, val write: String, val read: String)
    private class Topic(val user: String, val vhost: String, val exchange: String, val write: String, val read: String)

    private val perms: MutableList<Perm> = Collections.synchronizedList(ArrayList())
    private val topics: MutableList<Topic> = Collections.synchronizedList(ArrayList())

    /** M10-03: [name] is an internal user of the broker (listed, and answers 200 with an empty list when it has no row). */
    fun addUser(name: String) { if (name !in internalUsers) internalUsers = internalUsers + name }

    /** M10-03: a permission row for [user] on [vhost] (replaces an earlier row of the same pair); the user becomes an internal user. */
    fun setPermissions(user: String, vhost: String, configure: String, write: String, read: String) {
        addUser(user)
        perms.removeAll { it.user == user && it.vhost == vhost }
        perms += Perm(user, vhost, configure, write, read)
    }

    /** M10-03: a topic permission row for [user] on [vhost] and [exchange]. */
    fun setTopicPermissions(user: String, vhost: String, exchange: String, write: String, read: String) {
        addUser(user)
        topics.removeAll { it.user == user && it.vhost == vhost && it.exchange == exchange }
        topics += Topic(user, vhost, exchange, write, read)
    }

    /** Names of the calls in order: "whoami", "vhosts", "users", "permissions:<user>". Thread-safe; tests may clear it. */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val gate = AtomicReference<Pair<String, CallGate>?>(null)

    /** The next call named [call] ("whoami", "vhosts", "users" or "permissions") waits until the gate is released. */
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

    override suspend fun users(): AccessResult<UserNames> {
        enter("users")
        return common() ?: administratorOnly() ?: usersFailure ?: AccessResult.Loaded(UserNames(SecretGuard.redactAll(internalUsers, heldSecret).values))
    }

    override suspend fun permissionsOf(user: String): AccessResult<UserPermissions> {
        enter("permissions", record = "permissions:$user")
        common()?.let { return it }
        administratorOnly()?.let { return it }
        permissionsFailure?.let { return it }
        if (user !in internalUsers) return AccessResult.NotFound
        val rows = synchronized(perms) { perms.filter { it.user == user } }.map {
            PermissionRow(guard(it.vhost), cell(it.configure), cell(it.write), cell(it.read))
        }
        val topicRows = synchronized(topics) { topics.filter { it.user == user } }.map {
            TopicPermissionRow(guard(it.vhost), guard(it.exchange), cell(it.write), cell(it.read))
        }
        return AccessResult.Loaded(UserPermissions(user, rows, topicRows))
    }

    /** The broker's 401 "Not administrator user": not the management-tag 401, so [AccessResult.NotAuthorised]. */
    private fun administratorOnly(): AccessResult<Nothing>? = if ("administrator" in tags) null else AccessResult.NotAuthorised

    private fun guard(text: String): String = SecretGuard.redact(text, heldSecret).text

    private fun cell(raw: String): PatternCell {
        val guarded = SecretGuard.redact(raw, heldSecret)
        return PatternCell(guarded.text, if (raw.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(guarded.text), guarded.hidden)
    }

    private fun common(): AccessResult<Nothing>? = when {
        notOffered -> AccessResult.NotOffered
        unreachable -> AccessResult.Unreachable
        !hasManagementTag -> AccessResult.NoManagementTag
        refusesSignIn -> AccessResult.NotAuthorised
        else -> null
    }

    private suspend fun enter(call: String, record: String = call) {
        requests += record
        val held = gate.get()
        if (held != null && held.first == call && gate.compareAndSet(held, null)) held.second.await()
    }
}
