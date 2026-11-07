package dev.caerbannog.ui.topology

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import dev.caerbannog.core.topology.BindingRowView
import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.ExchangeRowView
import dev.caerbannog.core.topology.MqttClientRowView
import dev.caerbannog.core.topology.QueueDetailView
import dev.caerbannog.core.topology.QueueRowView
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyBanner
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.core.topology.TopologyScreen as Screen
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents

/** Shared builders of the M2-08c Compose tests: states as the view model builds them (`TopologyState`, core), and an event recorder. */
internal class RecordedTopologyEvents : TopologyEvents {
    val calls = mutableListOf<String>()

    /** The calls without the setShown bookkeeping, for tests about what the user pressed. */
    fun sent(): List<String> = calls.filterNot { it.startsWith("setShown:") }
    override fun setShown(shown: Boolean) { calls += "setShown:$shown" }
    override fun refreshNow() { calls += "refreshNow" }
    override fun retry() { calls += "retry" }
    override fun setTab(tab: TopologyTab) { calls += "setTab:$tab" }
    override fun setQueueFilter(filter: QueueTypeFilter) { calls += "setQueueFilter:$filter" }
    override fun setSearch(text: String) { calls += "setSearch:$text" }
    override fun selectVhost(name: String) { calls += "selectVhost:$name" }
    override fun selectQueue(name: String?) { calls += "selectQueue:$name" }
    override fun setShowDefaultExchange(show: Boolean) { calls += "setShowDefaultExchange:$show" }
    override fun setBindingsView(mode: BindingsViewMode) { calls += "setBindingsView:$mode" }
    override fun nextPage() { calls += "nextPage" }
    override fun previousPage() { calls += "previousPage" }
}

internal fun queue(
    name: String = "orders",
    type: String = "quorum",
    ready: String = "1,240",
    unacked: String = "12",
    consumers: String = "3",
    leader: String = "rabbit@node-1",
    members: String = "3 of 3",
    offline: Boolean = false,
) = QueueRowView(name, type, ready, unacked, consumers, leader, members, offline)

/** The mockup's Queues tab: orders, invoices, notifications (a member offline), legacy-orders. */
internal val mockupQueues = listOf(
    queue("orders"),
    queue("invoices", ready = "0", unacked = "0", consumers = "2", leader = "rabbit@node-2"),
    queue("notifications", ready = "86", unacked = "4", consumers = "1", leader = "rabbit@node-3", members = "2 of 3", offline = true),
    queue("legacy-orders", ready = "0", unacked = "0", consumers = "0"),
)

internal fun exchange(name: String, type: String = "direct", durable: String = "yes", autoDelete: String = "no", internal: String = "no", superStream: Boolean = false) =
    ExchangeRowView(if (name.isEmpty()) TopologyTexts.DEFAULT_EXCHANGE else name, type, durable, autoDelete, internal, superStream, isDefault = name.isEmpty())

internal fun binding(source: String, destination: String, type: String = "queue", key: String = "", arguments: String = "{}") =
    BindingRowView(if (source.isEmpty()) TopologyTexts.DEFAULT_EXCHANGE else source, destination, type, key, arguments)

internal val mockupDetail = QueueDetailView(
    name = "orders", typeText = "Quorum queue", leader = "rabbit@node-1", members = "node-1, node-2, node-3", membersOnline = "3 of 3",
    readyUnacked = "1,240 / 12", consumers = "3", policy = "quorum-default",
    bindings = listOf(BindingRowView("events", "orders", "queue", "order.created", "{}")), memberOffline = false,
)

internal fun banner(kind: TopologyBannerKind, headline: String, detail: String? = null) = TopologyBanner(kind, headline, detail)

/** The Queues tab with rows, as the view model builds it (message null, no banner). */
internal fun queuesState(
    rows: List<QueueRowView> = mockupQueues,
    filter: QueueTypeFilter = QueueTypeFilter.Quorum,
    search: String = "",
    vhost: String = "/",
    vhosts: List<String> = listOf("/", "billing", "many"),
    showSwitch: Boolean = true,
    homeVhost: String? = null,
    total: Int = rows.size,
    rangeText: String = if (rows.isEmpty()) "" else "1 to ${rows.size} of $total",
    hasNext: Boolean = false,
    hasPrevious: Boolean = false,
    selected: QueueDetailView? = null,
    stale: Boolean = false,
    connectionBanner: String? = null,
    action: String? = null,
    banners: List<TopologyBanner> = emptyList(),
    updatedAtMillis: Long? = null,
) = TopologyState(
    screen = Screen.Ready, message = null, action = action, tab = TopologyTab.Queues, queueFilter = filter, search = search, vhost = vhost, vhosts = vhosts,
    showVhostSwitch = showSwitch, homeVhost = homeVhost, queues = rows, selectedQueue = selected, total = total, rangeText = rangeText, hasNextPage = hasNext, hasPreviousPage = hasPrevious,
    stale = stale, connectionBanner = connectionBanner, banners = banners, updatedAtMillis = updatedAtMillis,
)

internal fun exchangesState(rows: List<ExchangeRowView>, search: String = "") = TopologyState(
    screen = Screen.Ready, message = null, tab = TopologyTab.Exchanges, search = search, vhost = "/", vhosts = listOf("/", "billing"), showVhostSwitch = true,
    exchanges = rows, total = rows.size, rangeText = "1 to ${rows.size} of ${rows.size}",
)

internal fun bindingsState(rows: List<BindingRowView>, showDefault: Boolean = false, search: String = "") = TopologyState(
    screen = Screen.Ready, message = null, tab = TopologyTab.Bindings, search = search, vhost = "/", vhosts = listOf("/", "billing"), showVhostSwitch = true,
    bindings = rows, showDefaultExchange = showDefault, total = rows.size, rangeText = "1 to ${rows.size} of ${rows.size}",
)

internal fun mqttState(rows: List<MqttClientRowView>, search: String = "", hasMqtt: Boolean = true) = TopologyState(
    screen = Screen.Ready, message = null, tab = TopologyTab.Mqtt, search = search, vhost = "/", vhosts = listOf("/", "billing"), showVhostSwitch = true,
    hasMqtt = hasMqtt, mqttClients = rows, total = rows.size, rangeText = "1 to ${rows.size} of ${rows.size}",
)

/** A state without rows: [screen] with the message and action the view model gives it. The tab and the switch as given. */
internal fun messageState(
    screen: Screen,
    message: String?,
    tab: TopologyTab = TopologyTab.Queues,
    action: String? = null,
    showSwitch: Boolean = false,
    vhosts: List<String> = emptyList(),
    vhost: String = "/",
) = TopologyState(screen = screen, message = message, action = action, tab = tab, vhost = vhost, vhosts = vhosts, showVhostSwitch = showSwitch)

internal val notConnected = messageState(Screen.NotConnected, TopologyTexts.NOT_CONNECTED, vhost = "")
internal val connecting = messageState(Screen.Connecting, TopologyTexts.CONNECTING, vhost = "")
internal val loading = messageState(Screen.Loading, TopologyTexts.LOADING, showSwitch = true, vhosts = listOf("/", "billing"))
internal val emptyQueues = messageState(Screen.Empty, TopologyTexts.NO_QUEUES, showSwitch = true, vhosts = listOf("/", "billing"))
internal val noTag = messageState(Screen.CannotRead, TopologyTexts.NO_MANAGEMENT_TAG)
internal val unavailable = messageState(
    Screen.VhostUnavailable("billing"), TopologyTexts.vhostUnavailable("billing"), showSwitch = true, vhosts = listOf("/", "billing", "many"), vhost = "billing",
)
internal val failed = messageState(Screen.Failed(TopologyTexts.LOAD_FAILED), TopologyTexts.LOAD_FAILED, action = "Retry")

/** What the screen is showing now; assign to change it in a running test (then `waitForIdle()`). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal class Shown(state: TopologyState, now: Long, val events: RecordedTopologyEvents) {
    var state by mutableStateOf(state)
    var now by mutableStateOf(now)
}

/** Sets the content to the screen once; change it later through the returned [Shown]. Tags: see the KDoc of [TopologyScreenUiTest]. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.screen(
    state: TopologyState,
    events: RecordedTopologyEvents = RecordedTopologyEvents(),
    nowMillis: Long = 0,
    onNavigate: (AppDestination) -> Unit = {},
    wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
): Shown {
    val shown = Shown(state, nowMillis, events)
    setContent {
        wrap {
            TopologyScreen(shown.state, shown.now, events, connected, noControlEvents, onNavigate)
        }
    }
    return shown
}
