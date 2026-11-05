package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.StreamListRequest
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

    /** M2-07 (S-A): every binding of the vhost in one unpaged call (a plain array; includes the default exchange's bindings). */
    fun bindings(vhost: String): String = "$base/api/bindings/${encode(vhost)}"

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

    // URLEncoder makes a space "+", which a path segment does not understand.
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val QUEUE_PAGE_SIZE = dev.caerbannog.core.topology.TopologyCatalog.QUEUE_PAGE_SIZE
        private const val QUEUE_COLUMNS = "name,type,messages_ready,messages_unacknowledged,consumers,leader,members,online,policy,state"
        private const val LIST_COLUMNS =
            "name,vhost,type,messages,consumers,segments,first_timestamp,leader,node,members,online,arguments"

        fun forProfile(profile: ConnectionProfile, transport: BrokerTransport? = null): ManagementRequests {
            val resolved = transport ?: if (profile.tls) null else BrokerTransport.Plain
            val scheme = resolved?.scheme ?: "https"
            return ManagementRequests("$scheme://${profile.host}:${profile.managementPort}", resolved)
        }
    }
}
