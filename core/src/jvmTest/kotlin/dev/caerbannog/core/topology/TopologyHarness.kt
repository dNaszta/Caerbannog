package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.Harness
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * M2-08b step 2: shared set-up of the topology view model tests. Everything is a fake and runs in VIRTUAL time (the view model gets the
 * test's `backgroundScope`, the clock reads the test scheduler). No sleeps anywhere.
 *
 * CONTRACT USED (test developer, brief docs/tasks/M2-08-topology-read-only.md, 08b step 2). Package `dev.caerbannog.core.topology`, commonMain:
 *
 *   class TopologyViewModel(active, settings: AppSettings, scope, clock: RefreshClock, reconnect: suspend (String) -> Unit,
 *       context: StateFlow<TopologyContext?>, pageSize: Int = DEFAULT_PAGE_SIZE) : AutoCloseable, RefreshTarget
 *     val state: StateFlow<TopologyState>; setShown, refreshNow, retry, setTab, setQueueFilter, setSearch, selectVhost, selectQueue,
 *     setShowDefaultExchange, setPage, nextPage, previousPage, setPageSize, close    (the types are in TopologyState.kt, already in the branch)
 *   core/connections: `StreamSource.topology` (constructor parameter without default), `HeldConnection.topology`, `GuardedTopology` (Unreachable once superseded)
 *   core-testing: `FakeConnectionGateway.topologyOf(profileName)`, `FakeTopologyCatalog.requests`
 *
 * The harness connects "uat-eu" with the topology level DECLARE_AND_EDIT and a Checked(unlocked) permission, so by default NO banner shows
 * (the default level of a real profile is READ_ONLY, which is itself a banner reason, T5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TopologyHarness(
    val test: TestScope,
    pageSize: Int = 5,
    refresh: RefreshInterval = DEFAULT_REFRESH,
    vhost: String = "/",
    level: TopologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
    permission: PermissionCheckResult? = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true),
    /** False: the connection's own vhost does not exist on the broker (the 404 case, R2). */
    declareProfileVhost: Boolean = true,
) {
    val h = Harness()
    val topology: FakeTopologyCatalog = h.gateway.topologyOf("uat-eu")
    val settings = FakeAppSettings(refresh)
    val reconnects = mutableListOf<String>()
    val profile: ConnectionProfile = h.uat.copy(virtualHost = vhost, topologyControlLevel = level)
    val context = MutableStateFlow<TopologyContext?>(TopologyContext(profile, permission))

    val vm = TopologyViewModel(
        active = h.active,
        settings = settings,
        scope = test.backgroundScope,
        clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { name ->
            reconnects += name
            h.active.connect(profile)
        },
        context = context,
        pageSize = pageSize,
    )

    val state: TopologyState get() = vm.state.value

    init {
        if (declareProfileVhost) topology.declareVhost(vhost)
    }

    fun settle() = test.runCurrent()

    fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    suspend fun connect() {
        h.active.connect(profile)
        settle()
    }

    /** Connected and the screen shown: the first load is done unless a call is held. */
    suspend fun open() {
        connect()
        vm.setShown(true)
        settle()
    }

    fun quorumQueues(count: Int, prefix: String = "q-", vhost: String = profile.virtualHost) {
        for (i in 0 until count) topology.addQueue(quorum("$prefix${"%02d".format(i)}", vhost = vhost))
    }

    fun names(): List<String> = state.queues.map { it.name }

    /** Calls of one kind by name (`queues`, `exchanges`, `bindings`, `vhosts`, `queueDetail`). */
    fun count(call: String): Int = synchronized(topology.calls) { topology.calls.count { it == call } }

    fun requests(): List<String> = synchronized(topology.requests) { topology.requests.toList() }

    fun forgetRequests() = synchronized(topology.requests) { topology.requests.clear(); topology.calls.clear() }
}
