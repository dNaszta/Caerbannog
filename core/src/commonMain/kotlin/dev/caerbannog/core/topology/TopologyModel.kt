package dev.caerbannog.core.topology

/** [rows] are the non-stream queues of the page; [pageCount], [pageSize] and [totalCount] are the broker's, taken over ALL queues of the vhost. */
data class QueuePage(
    val rows: List<QueueRow>,
    val page: Int,
    val pageCount: Int,
    val pageSize: Int,
    val totalCount: Int,
)

/**
 * One queue as the management API shows it (spike S-A, `fixtures/m2-08/`). A count is null when the broker sent none yet (a screen shows
 * "n/a", never 0). [type] is the broker's text (`quorum`, `classic`, ...). [leader], [members] and [online] exist for quorum queues only
 * (a classic queue has none: null and empty lists). [policy] is the name of the policy that applies, null when none. [state] is the broker's text (`running`).
 */
data class QueueRow(
    val name: String,
    val vhost: String,
    val type: String,
    val readyMessages: Long?,
    val unackedMessages: Long?,
    val consumers: Int?,
    val leader: String?,
    val members: List<String>,
    val online: List<String>,
    val policy: String?,
    val state: String?,
) {
    val isQuorum: Boolean get() = type == "quorum"

    /** Members the broker says are up, of all members: "2 of 3". Taken from the broker's own `online` list, never inferred from names. */
    val membersOnline: Int get() = online.size
    val membersTotal: Int get() = members.size

    /** Flagged when the broker reports fewer online members than members. */
    val hasOfflineMember: Boolean get() = online.size < members.size
}

/** One exchange. [arguments] values are display text: a string as is, any other JSON value as its JSON text (`true`, `1`, `[1,2]`). */
data class ExchangeRow(
    val name: String,
    val vhost: String,
    val type: String,
    val durable: Boolean,
    val autoDelete: Boolean,
    val internal: Boolean,
    val arguments: Map<String, String> = emptyMap(),
    /**
     * M2-08b ruling 6: a super stream exchange. Set by the management parser with ONE rule, the one of the stream list (M2-07): a `direct`
     * exchange whose `x-super-stream` argument is the JSON boolean `true` (the string "true", false and a fanout are not). The text in
     * [arguments] cannot tell the boolean from the string, so screens read this flag and never the argument.
     */
    val superStream: Boolean = false,
) {
    /** The default exchange has the name `""`. */
    val isDefault: Boolean get() = name.isEmpty()
}

enum class BindingDestinationType { QUEUE, EXCHANGE }

/** One binding; [source] is `""` for the implicit binding of the default exchange. [arguments] as in [ExchangeRow]. */
data class BindingRow(
    val source: String,
    val vhost: String,
    val destination: String,
    val destinationType: BindingDestinationType,
    val routingKey: String,
    val arguments: Map<String, String> = emptyMap(),
)

/** One virtual host of `GET /api/vhosts`; the name is all the app needs (`/` is the default vhost). */
data class VhostRow(val name: String)
