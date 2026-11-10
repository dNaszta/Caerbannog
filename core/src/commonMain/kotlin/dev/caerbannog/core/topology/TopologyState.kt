package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.streams.DEFAULT_PAGE_SIZE
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval

/**
 * M2-08b contract (test developer; the REAL types, the view model behind them is a compile stub). What the Topology screen shows, all as
 * display text (what is shown is what is copied, UI1). Mockup: `docs/ux/Topology.dc.html`.
 */
sealed interface TopologyScreen {
    data object NotConnected : TopologyScreen

    data object Connecting : TopologyScreen

    data object Loading : TopologyScreen

    /** Rows of the current tab (possibly an empty page after a search that matches nothing). */
    data object Ready : TopologyScreen

    /** The current tab has nothing at all in the selected virtual host ("There are no queues in this virtual host yet."). */
    data object Empty : TopologyScreen

    /** No management tag: message, no tabs content, no switch. */
    data object CannotRead : TopologyScreen

    /** R2: the broker answered 404 for the selected virtual host (missing, or no permission). No rows; the switch stays. */
    data class VhostUnavailable(val vhost: String) : TopologyScreen

    data class Failed(val reason: String) : TopologyScreen
}

enum class TopologyTab { Queues, Exchanges, Bindings, Mqtt }

/** The two chips of the Queues tab; exclusive, [Quorum] by default. "Other types" is every non-quorum, non-stream queue. */
enum class QueueTypeFilter { Quorum, OtherTypes }

/** One MQTT client row on the MQTT tab of the Topology screen. */
data class MqttClientRowView(
    val clientId: String,
    val connectionName: String,
    val username: String,
    val peerAddress: String,
    val protocol: String,
    val connectedDuration: String,
    val ssl: Boolean,
    val cleanSession: String,
    val subscriptionCount: Int,
    val subscriptions: List<dev.caerbannog.core.mqtt.MqttSubscription>,
    val connected: Boolean,
)

/** One queue row. [membersOnline] is "2 of 3"; a classic queue has no leader and no members: both read [TopologyTexts.NOT_APPLICABLE]. A null count reads "n/a", never 0. */
data class QueueRowView(
    val name: String,
    val type: String,
    val ready: String,
    val unacked: String,
    val consumers: String,
    val leader: String,
    val membersOnline: String,
    /** True when the broker's `online` list is shorter than `members`. */
    val memberOffline: Boolean,
)

/** The detail of the selected queue, from the row already loaded (no per-queue call). [bindings] are the explicit bindings with this queue as destination (never the implicit default exchange one). */
data class QueueDetailView(
    val name: String,
    /** "Quorum queue", "Classic queue", else the broker's type text. */
    val typeText: String,
    val leader: String,
    /** Member names, a leading `rabbit@` removed, joined by ", " (mockup). */
    val members: String,
    val membersOnline: String,
    /** "1,240 / 12" */
    val readyUnacked: String,
    val consumers: String,
    /** The policy name, or the dash when none. */
    val policy: String,
    val bindings: List<BindingRowView>,
    val memberOffline: Boolean,
)

data class ExchangeRowView(
    /** The default exchange reads [TopologyTexts.DEFAULT_EXCHANGE]. */
    val name: String,
    val type: String,
    val durable: String,
    val autoDelete: String,
    val internal: String,
    /** A `direct` exchange with the JSON boolean `x-super-stream` = true (the M2-07 rule). */
    val superStream: Boolean,
    val isDefault: Boolean,
)

data class BindingRowView(
    /** The default exchange reads [TopologyTexts.DEFAULT_EXCHANGE]. */
    val source: String,
    val destination: String,
    /** "queue" or "exchange". */
    val destinationType: String,
    val routingKey: String,
    /** `{}` when none, else `{"key": value, "key2": value2}` (keys quoted, values as the catalog gives them). */
    val arguments: String,
)

enum class TopologyBannerKind { NoConfigure, DeclaredNotConfirmed, NotReadable, ReadOnlyLevel, OtherVhost, MigrationSource }

data class TopologyBanner(val kind: TopologyBannerKind, val headline: String, val detail: String?)

/** What the banner needs besides the link: the connected profile (user, level) and the session's permission result. */
data class TopologyContext(val profile: ConnectionProfile, val permission: PermissionCheckResult?)

/** The whole state of the Topology screen. Rows are the VISIBLE page of the current tab only; the other tabs' lists are empty. */
data class TopologyState(
    val screen: TopologyScreen = TopologyScreen.NotConnected,
    val message: String? = TopologyTexts.NOT_CONNECTED,
    val action: String? = null,
    val tab: TopologyTab = TopologyTab.Queues,
    val queueFilter: QueueTypeFilter = QueueTypeFilter.Quorum,
    /** The search text of the CURRENT tab (each tab keeps its own). */
    val search: String = "",
    /** The selected virtual host; empty before the first connection. */
    val vhost: String = "",
    /** The virtual hosts offered: the broker's list, the connection's own first, then alphabetical. Empty when it could not be read. */
    val vhosts: List<String> = emptyList(),
    val showVhostSwitch: Boolean = false,
    /** M2-08f1: the connection's own virtual host, set only when [vhosts] contains it; null when omitted or CannotRead. */
    val homeVhost: String? = null,
    val hasMqtt: Boolean = false,
    val queues: List<QueueRowView> = emptyList(),
    val exchanges: List<ExchangeRowView> = emptyList(),
    val bindings: List<BindingRowView> = emptyList(),
    val mqttClients: List<MqttClientRowView> = emptyList(),
    val showDefaultExchange: Boolean = false,
    val bindingsView: BindingsViewMode = BindingsViewMode.Table,
    val graph: BindingGraph? = null,
    val graphTooLargeMessage: String? = null,
    val selectedQueue: QueueDetailView? = null,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** Rows of the current tab after the filters (never the broker's total, which counts streams, R1). */
    val total: Int = 0,
    /** "1 to 5 of 12", counted over the loaded rows; empty when the page has no rows. */
    val rangeText: String = "",
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    /** D2: the rows are the last ones read. */
    val stale: Boolean = false,
    /** D2: [dev.caerbannog.core.connections.ConnectionFailureReason.CONNECTION_LOST] while the link is lost with rows on screen; else null. */
    val connectionBanner: String? = null,
    /**
     * Every reason the screen is read-only that applies, in this order (owner decision O1 = b): ReadOnlyLevel (only at the Read-only level), NoConfigure,
     * DeclaredNotConfirmed or NotReadable, OtherVhost; the screen draws the first. On another vhost the broker and declared reasons are hidden (R6).
     */
    val banners: List<TopologyBanner> = emptyList(),
    val refreshInterval: RefreshInterval = DEFAULT_REFRESH,
    val updatedAtMillis: Long? = null,
    val approximateNote: String = TopologyTexts.APPROXIMATE,
)

/** Wording lives in core so Cucumber and Compose tests assert the same strings. */
object TopologyTexts {
    const val NOT_CONNECTED: String = "Not connected"
    const val CONNECTING: String = "Connecting..."
    const val LOADING: String = "loading..."
    const val RETRY: String = "Retry"
    const val NOT_AVAILABLE: String = "n/a"
    const val NOT_APPLICABLE: String = "–"
    const val DEFAULT_EXCHANGE: String = "(default exchange)"
    const val NO_MANAGEMENT_TAG: String = "This user cannot read topology. Ask for the management tag."
    const val NO_QUEUES: String = "There are no queues in this virtual host yet."
    const val NO_EXCHANGES: String = "There are no exchanges in this virtual host yet."
    const val NO_BINDINGS: String = "There are no bindings in this virtual host yet."
    const val NO_MQTT_CLIENTS: String = "There are no MQTT client connections in this virtual host."
    const val LOAD_FAILED: String = "Topology could not be loaded. Check that the broker is reachable and that this user may use it."
    const val APPROXIMATE: String = "Queue counts are approximate: the broker updates them every few seconds."
    const val HOME_VHOST_CAPTION: String = "This connection's virtual host"
    const val GRAPH_TOO_LARGE: String = "There are more bindings than can be drawn. Choose an exchange or narrow the filter."
    fun graphTooLarge(count: Int): String = "There are more bindings than can be drawn ($count bindings). Choose an exchange or narrow the filter."
    const val LOCKED_HEADLINE: String = "Topology is locked on this connection."
    const val READ_ONLY_HEADLINE: String = "This connection is set to read-only."
    const val READ_ONLY_DETAIL: String =
        "Its topology level is Read-only, so nothing here can be changed whatever the broker allows. Change the level in the connection's settings."

    fun vhostUnavailable(vhost: String): String =
        "Virtual host $vhost is not available to this user. It may have been deleted, or the user may have no permission on it."

    fun noConfigure(user: String, vhost: String): String =
        "Topology is locked on this connection. The permission check found no configure permission for $user on virtual host $vhost, " +
            "so queues, exchanges and bindings are read-only."

    fun otherVhost(profileVhost: String): String =
        "Permissions were checked on virtual host $profileVhost only. This virtual host is shown read-only."
}
