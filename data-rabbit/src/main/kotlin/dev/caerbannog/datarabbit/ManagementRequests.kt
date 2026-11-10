package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.topology.TopologyCatalog.Companion.QUEUE_PAGE_SIZE
import java.net.URLEncoder

/**
 * The URLs of the management API calls the catalog makes. The scheme of [baseUrl] must match [transport]
 * (https for [BrokerTransport.Tls], http for [BrokerTransport.Plain]); a mismatch is refused, so a plain base can only come from a
 * profile with TLS off. The one-argument form means TLS (https) as before.
 */
internal class ManagementRequests(baseUrl: String, private val transport: BrokerTransport? = null) {
    private val base: String = baseUrl.trimEnd('/')

    init {
        val scheme = transport?.scheme ?: "https"
        require(base.startsWith("$scheme://")) { "The management API base must use $scheme to match the connection's TLS setting" }
    }

    fun listStreams(request: StreamListRequest): String {
        val parameters = mutableListOf("page=${request.page}", "page_size=${request.pageSize}")
        request.nameFilter?.takeIf { it.isNotEmpty() }?.let {
            parameters += "name=${encode(it)}"
            parameters += "use_regex=${request.useRegex}"
        }
        parameters += "columns=${encode(LIST_COLUMNS)}"
        return "$base/api/queues/${encode(request.vhost)}?${parameters.joinToString("&")}"
    }

    fun streamDetail(vhost: String, name: String): String = "$base/api/queues/${encode(vhost)}/${encode(name)}"

    fun publishers(vhost: String, stream: String): String = "$base/api/stream/publishers/${encode(vhost)}/${encode(stream)}"

    /** D8: every publisher of the vhost in one call; the caller counts them per stream. */
    fun publisherCounts(vhost: String): String = "$base/api/stream/publishers/${encode(vhost)}"

    /** The broker has no per-stream consumers path (it answers 400), so the rows of the whole vhost are read. */
    fun consumers(vhost: String): String = "$base/api/stream/consumers/${encode(vhost)}"

    /** The top-level path: per-stream paths answer 404 both for a missing plugin and a missing stream. */
    fun pluginProbe(): String = "$base/api/stream/publishers"

    /** M2-07 (S-A): every exchange of the vhost, unpaged (a plain array, `arguments` present on each). */
    fun exchanges(vhost: String): String = "$base/api/exchanges/${encode(vhost)}"

    /** M5-01: publish a message to an exchange on the vhost. */
    fun publishExchange(vhost: String, exchange: String): String =
        "$base/api/exchanges/${encode(vhost)}/${encode(exchange)}/publish"

    /** M2-07 (S-A): every binding of the vhost in one unpaged call (a plain array; includes the default exchange's bindings). */
    fun bindings(vhost: String): String = "$base/api/bindings/${encode(vhost)}"

    /** M5-08a: the URL of [path] (which starts with `/api`) on this connection's base; the migration executor checks the path first. */
    fun absolute(path: String): String = "$base$path"

    /** M5-08a: the broker's overview, read once per connection for its version (`rabbitmq_version`). */
    fun overview(): String = "$base/api/overview"

    /** M8-02b: the full definitions export of a 3.x source (a GET; administrator only). */
    fun definitions(): String = "$base/api/definitions"

    /**
     * M8-02b: one page of the queues of the vhost with ALL fields, because `storage_version` is a statistics field. No `columns` filter
     * on purpose (the live test records what `columns=` would do).
     */
    fun queuesWithStorageVersion(vhost: String, page: Int): String =
        "$base/api/queues/${encode(vhost)}?page=$page&page_size=$QUEUE_PAGE_SIZE"

    /** M8-02b: the deprecated features this 3.x broker is using. */
    fun deprecatedFeaturesUsed(): String = "$base/api/deprecated-features/used"

    fun whoami(): String = "$base/api/whoami"

    /**
     * M2-08a (spike S-B): one page of the queues of the vhost, the broker's maximum page size and only the fields the parser reads
     * (119 KB instead of 546 KB at 500 queues). No name filter: the search is in memory.
     */
    fun queues(vhost: String, page: Int): String =
        "$base/api/queues/${encode(vhost)}?page=$page&page_size=$QUEUE_PAGE_SIZE&columns=${encode(QUEUE_COLUMNS)}"

    /** M2-08a: one queue (a plain GET of the queue object; never the message paths under it). */
    fun queueDetail(vhost: String, name: String): String = "$base/api/queues/${encode(vhost)}/${encode(name)}"

    /** M2-08a (spike S-E): the vhosts the broker lists for this user. Never `/api/vhosts/{name}`, which only an administrator may read. */
    fun vhosts(): String = "$base/api/vhosts"

    /** M6-02: every connection of the broker (client connections, including MQTT). */
    fun connections(): String = "$base/api/connections"

    /** M7-08: non-destructive queue message inspection endpoint (`POST /api/queues/{vhost}/{name}/get`). */
    fun getQueueMessages(vhost: String, name: String): String = "$base/api/queues/${encode(vhost)}/${encode(name)}/get"

    companion object {
        /** One URL path segment; URLEncoder makes a space "+", which a path segment does not understand. */
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private const val QUEUE_COLUMNS = "name,type,messages_ready,messages_unacknowledged,consumers,leader,members,online,policy,state"
        private const val LIST_COLUMNS =
            "name,vhost,type,messages,consumers,segments,first_timestamp,leader,node,members,online,arguments"

        /**
         * Formats a host for use in a URI authority (RFC 3986 section 3.2.2). IPv6 literal addresses containing
         * colons (e.g. `::1` or `2001:db8::1`) are enclosed in square brackets `[::1]`. Already bracketed inputs
         * `[::1]` are kept as-is. Host names and IPv4 addresses remain unbracketed.
         *
         * Scoped IPv6 addresses with zone identifiers (e.g. `fe80::1%eth0`) are not supported.
         */
        fun formatHost(host: String): String = when {
            host.startsWith("[") && host.endsWith("]") -> host
            host.contains(":") -> "[$host]"
            else -> host
        }

        fun formatAuthority(host: String, port: Int): String = "${formatHost(host)}:$port"

        fun forProfile(profile: ConnectionProfile, transport: BrokerTransport? = null): ManagementRequests {
            val resolved = transport ?: if (profile.tls) null else BrokerTransport.Plain
            val scheme = resolved?.scheme ?: "https"
            return ManagementRequests("$scheme://${formatAuthority(profile.host, profile.managementPort)}", resolved)
        }
    }
}
