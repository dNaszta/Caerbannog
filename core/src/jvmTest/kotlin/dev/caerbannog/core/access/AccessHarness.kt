package dev.caerbannog.core.access

import dev.caerbannog.core.access.fakes.FakeAccessGateway
import dev.caerbannog.core.connections.ConnectionProfile
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
 * M10-02 step 2: shared set-up of the Access view model tests. Everything is a fake and runs in VIRTUAL time (the view model gets the test's
 * `backgroundScope`, the clock reads the test scheduler). No sleeps anywhere.
 *
 * CONTRACT USED (test developer, brief docs/tasks/M10-02-secret-guard-and-who-am-i.md, step 3). Package `dev.caerbannog.core.access`, commonMain:
 *
 *   class AccessViewModel(active, settings: AppSettings, scope, clock: RefreshClock, reconnect: suspend (String) -> Unit,
 *       context: StateFlow<AccessContext?>) : AutoCloseable, RefreshTarget
 *     val state: StateFlow<AccessState>; setShown(Boolean), refreshNow(), retry(), noticeShown(), close()
 *   core/connections: `StreamSource.access: AccessGateway` (guarded like the other gateways: Unreachable once superseded), `HeldConnection.access`;
 *   core-testing: `FakeConnectionGateway.accessOf(profileName)`, `FakeAccessGateway.requests`
 *
 * The held connection "prod-eu" has the virtual host "/orders" (the one the connection itself uses) and TLS on, so by default no banner shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AccessHarness(
    val test: TestScope,
    refresh: RefreshInterval = DEFAULT_REFRESH,
    tls: Boolean = true,
) {
    val h = Harness()
    val access: FakeAccessGateway = h.gateway.accessOf("prod-eu")
    val settings = FakeAppSettings(refresh)
    val reconnects = mutableListOf<String>()
    val profile: ConnectionProfile = h.prod.copy(virtualHost = "/orders", tls = tls)
    val context = MutableStateFlow<AccessContext?>(AccessContext(profile))

    val vm = AccessViewModel(
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

    val state: AccessState get() = vm.state.value

    init {
        access.user = "svc-orders"
        access.tags = listOf("monitoring")
        access.listedVhosts = listOf("/", "/orders", "/billing")
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

    /** Connected and the screen shown: the first load is done unless a call is held. */
    suspend fun open() {
        connect()
        vm.setShown(true)
        settle()
    }

    fun requests(): List<String> = synchronized(access.requests) { access.requests.toList() }

    fun forgetRequests() = synchronized(access.requests) { access.requests.clear() }
}
