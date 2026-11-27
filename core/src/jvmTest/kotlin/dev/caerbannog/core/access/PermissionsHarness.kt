package dev.caerbannog.core.access

import dev.caerbannog.core.access.fakes.FakeAccessGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * M10-03 step 2: shared set-up of the Permissions view model tests. Fakes only, VIRTUAL time, no sleeps.
 *
 * CONTRACT USED (test developer; brief docs/tasks/M10-03-permissions-tab.md): `PermissionsViewModel(active, settings, scope, clock, reconnect, context)` with
 * `state: StateFlow<PermissionsState>`, `setShown`, `refreshNow`, `retry`, `chooseUser(key)`, `setUserFilter`, `setVhostFilter`, `close`; `AccessGateway.users()` and
 * `permissionsOf(user)`; `FakeAccessGateway.internalUsers/setPermissions/setTopicPermissions/permissionsFailure/usersFailure`.
 *
 * Default world: the held connection "prod-eu" (virtual host "/orders", TLS on). The connection user is "ops-admin", an internal user with the tag
 * `administrator`. The broker has the internal users ops-admin, svc-orders and svc-billing; svc-orders has `/orders` configure `^queue\..*`, write `^ex\..*`,
 * read `.*` and a topic permission on `/orders` for `amq.topic`; the broker lists the virtual hosts `/`, `/orders`, `/billing` for the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PermissionsHarness(
    val test: TestScope,
    refresh: RefreshInterval = DEFAULT_REFRESH,
    tls: Boolean = true,
    declared: DeclaredPermissions? = null,
) {
    val h = Harness()
    val access: FakeAccessGateway = h.gateway.accessOf("prod-eu")
    val settings = FakeAppSettings(refresh)
    val reconnects = mutableListOf<String>()
    val profile: ConnectionProfile = h.prod.copy(virtualHost = "/orders", tls = tls, declaredPermissions = declared)
    val context = MutableStateFlow<AccessContext?>(AccessContext(profile))

    val vm = PermissionsViewModel(
        active = h.active,
        settings = settings,
        scope = test.backgroundScope,
        clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { name ->
            reconnects += name
            h.active.connect(profile)
        },
        context = context,
    )

    val state: PermissionsState get() = vm.state.value

    init {
        access.user = "ops-admin"
        access.tags = listOf("administrator")
        access.internalUser = true
        access.listedVhosts = listOf("/", "/orders", "/billing")
        access.addUser("ops-admin")
        access.addUser("svc-billing")
        access.setPermissions("svc-orders", "/orders", "^queue\\..*", "^ex\\..*", ".*")
        access.setTopicPermissions("svc-orders", "/orders", "amq.topic", "^sensors\\..*", ".*")
    }

    fun settle() = test.runCurrent()

    fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    suspend fun connect() {
        h.active.connect(profile)
        context.value = AccessContext(profile)
        settle()
    }

    /** Connected and the tab shown: the first load is done unless a call is held. */
    suspend fun open() {
        connect()
        vm.setShown(true)
        settle()
    }

    /** Opens the tab and chooses [user]. */
    suspend fun openAndChoose(user: String) {
        open()
        vm.chooseUser(user)
        settle()
    }

    fun requests(): List<String> = synchronized(access.requests) { access.requests.toList() }

    fun forgetRequests() = synchronized(access.requests) { access.requests.clear() }

    /** The permission or user requests only (whoami and vhosts are not what the lock rule is about). */
    fun permissionRequests(): List<String> = requests().filter { it == "users" || it.startsWith("permissions:") }

    fun cell(text: String) = PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))
}
