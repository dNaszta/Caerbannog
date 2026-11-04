package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.StreamListRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.URLDecoder

/**
 * M2-03 part 2 (no Docker): the URLs the real catalog will call. Contract, `internal class ManagementRequests`
 * in package `dev.caerbannog.datarabbit`:
 *   ManagementRequests(baseUrl: String)       throws IllegalArgumentException unless the scheme is https (TLS only)
 *   ManagementRequests.forProfile(profile: ConnectionProfile)   -> https://<host>:<managementPort>
 *   listStreams(request: StreamListRequest): String   GET /api/queues?page=&page_size=[&name=&use_regex=]&columns=...
 *   streamDetail(vhost, name): String                 GET /api/queues/{vhost}/{name}
 *   publishers(vhost, stream): String                 GET /api/stream/publishers/{vhost}/{stream}
 *   publisherCounts(vhost): String                    GET /api/stream/publishers/{vhost}   (M2-04 D8: every publisher of the vhost in ONE call; the caller groups by stream)
 *   consumers(vhost): String                          GET /api/stream/consumers/{vhost}   (no per-stream path exists, it answers 400)
 *   pluginProbe(): String                             GET /api/stream/publishers          (the top-level path; per-stream paths cannot detect the plugin)
 *   whoami(): String                                  GET /api/whoami
 * vhost and names are percent-encoded as one path segment ("/" becomes %2F, a space %20, never "+").
 */
class ManagementRequestsTest {

    private val requests = ManagementRequests("https://rabbit.example.com:15671")

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun `a plain http base is refused`() {
        assertThrows<IllegalArgumentException> { ManagementRequests("http://rabbit.example.com:15672") }
        assertThrows<IllegalArgumentException> { ManagementRequests("ws://rabbit.example.com") }
        assertThrows<IllegalArgumentException> { ManagementRequests("rabbit.example.com:15671") }
    }

    @Test
    fun `the base comes from the profile, always https`() {
        val profile = ConnectionProfile(name = "p", host = "rabbit.example.com", virtualHost = "/", username = "u", managementPort = 15999)
        assertEquals("https://rabbit.example.com:15999/api/whoami", ManagementRequests.forProfile(profile).whoami())
    }

    @Test
    fun `the list asks for the vhost of the request, path-encoded`() {
        assertEquals("/api/queues/orders%20v2", URI(requests.listStreams(StreamListRequest(vhost = "orders v2"))).rawPath)
        assertEquals("/api/queues/a%2Fb", URI(requests.listStreams(StreamListRequest(vhost = "a/b"))).rawPath)
    }

    @Test
    fun `the list asks for the page, the page size and the columns the rows need`() {
        val url = requests.listStreams(StreamListRequest(vhost = "/", page = 2, pageSize = 50))
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("rabbit.example.com", uri.host)
        assertEquals(15671, uri.port)
        assertEquals("/api/queues/%2F", uri.rawPath, "the default vhost / is %2F in the path")
        val q = query(url)
        assertEquals("2", q["page"])
        assertEquals("50", q["page_size"])
        val columns = q.getValue("columns").split(",").toSet()
        assertTrue(
            columns.containsAll(
                setOf("name", "vhost", "type", "messages", "consumers", "segments", "first_timestamp", "leader", "node", "members", "online", "arguments"),
            ),
            "columns must cover every field of StreamSummary (type is needed to keep only streams): $columns",
        )
    }

    @Test
    fun `no name filter means no name and no use_regex parameter`() {
        val q = query(requests.listStreams(StreamListRequest(vhost = "/")))
        assertFalse("name" in q || "use_regex" in q, "$q")
    }

    @Test
    fun `a name filter is sent encoded with use_regex false or true`() {
        val plain = query(requests.listStreams(StreamListRequest(vhost = "/", nameFilter = "orders events&x=1#frag")))
        assertEquals("orders events&x=1#frag", plain["name"])
        assertEquals("false", plain["use_regex"])
        val regex = query(requests.listStreams(StreamListRequest(vhost = "/", nameFilter = "^orders-.*$", useRegex = true)))
        assertEquals("^orders-.*$", regex["name"])
        assertEquals("true", regex["use_regex"])
        assertFalse(requests.listStreams(StreamListRequest(vhost = "/", nameFilter = "a&b")).contains("a&b"), "an ampersand in the filter must not split the query")
    }

    @Test
    fun `an empty name filter is not sent`() {
        assertFalse("name" in query(requests.listStreams(StreamListRequest(vhost = "/", nameFilter = ""))))
    }

    @Test
    fun `paths encode the vhost and the stream as single segments`() {
        assertEquals("https://rabbit.example.com:15671/api/queues/%2F/orders-events", requests.streamDetail("/", "orders-events"))
        assertEquals("https://rabbit.example.com:15671/api/stream/publishers/%2F/orders-events", requests.publishers("/", "orders-events"))
        assertEquals("https://rabbit.example.com:15671/api/stream/consumers/%2F", requests.consumers("/"))
        assertEquals("https://rabbit.example.com:15671/api/queues/my%20vhost/a%2Fb%20c%2Bd%25e", requests.streamDetail("my vhost", "a/b c+d%e"))
    }

    @Test
    fun `the plugin probe is the top level publishers path`() {
        assertEquals("https://rabbit.example.com:15671/api/stream/publishers", requests.pluginProbe())
    }

    @Test
    fun `a name with unicode is percent-encoded as UTF-8`() {
        val url = requests.streamDetail("/", "ordérs")
        assertTrue(url.endsWith("/ord%C3%A9rs"), url)
    }

    @Test
    fun `the publishers of a whole vhost are one call to the vhost path`() {
        assertEquals("https://rabbit.example.com:15671/api/stream/publishers/%2F", requests.publisherCounts("/"))
        assertEquals("https://rabbit.example.com:15671/api/stream/publishers/my%20vhost", requests.publisherCounts("my vhost"))
        assertFalse(requests.publisherCounts("/").contains("?"), "no paging or filter: the stream publishers path has none")
    }
}
