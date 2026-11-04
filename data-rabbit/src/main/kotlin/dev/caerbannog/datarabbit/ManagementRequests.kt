package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.StreamListRequest
import java.net.URLEncoder

/** The URLs of the management API calls the catalog makes. https only: a plain-text base is refused (TLS only). */
internal class ManagementRequests(baseUrl: String) {
    private val base: String = baseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://")) { "The management API is reached over https only" }
    }

    fun listStreams(request: StreamListRequest): String {
        val parameters = mutableListOf("page=${request.page}", "page_size=${request.pageSize}")
        request.nameFilter?.takeIf { it.isNotEmpty() }?.let {
            parameters += "name=${encode(it)}"
            parameters += "use_regex=${request.useRegex}"
        }
        parameters += "columns=${encode(LIST_COLUMNS)}"
        return "$base/api/queues?${parameters.joinToString("&")}"
    }

    fun streamDetail(vhost: String, name: String): String = "$base/api/queues/${encode(vhost)}/${encode(name)}"

    fun publishers(vhost: String, stream: String): String = "$base/api/stream/publishers/${encode(vhost)}/${encode(stream)}"

    /** D8: every publisher of the vhost in one call; the caller counts them per stream. */
    fun publisherCounts(vhost: String): String = "$base/api/stream/publishers/${encode(vhost)}"

    /** The broker has no per-stream consumers path (it answers 400), so the rows of the whole vhost are read. */
    fun consumers(vhost: String): String = "$base/api/stream/consumers/${encode(vhost)}"

    /** The top-level path: per-stream paths answer 404 both for a missing plugin and a missing stream. */
    fun pluginProbe(): String = "$base/api/stream/publishers"

    fun whoami(): String = "$base/api/whoami"

    // URLEncoder makes a space "+", which a path segment does not understand.
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val LIST_COLUMNS =
            "name,vhost,type,messages,consumers,segments,first_timestamp,leader,node,members,online,arguments"

        fun forProfile(profile: ConnectionProfile): ManagementRequests =
            ManagementRequests("https://${profile.host}:${profile.managementPort}")
    }
}
