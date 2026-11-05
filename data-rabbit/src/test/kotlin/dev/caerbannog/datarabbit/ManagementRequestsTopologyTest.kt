package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

/**
 * M2-08a step 2 (no Docker): the URLs of the topology catalog. Contract, `internal class ManagementRequests` (new members; `exchanges(vhost)` and
 * `bindings(vhost)` exist from M2-07 and are pinned in `ManagementRequestsSuperStreamTest`):
 *   queues(vhost, page): String        GET /api/queues/{vhost}?page={page}&page_size=500&columns=name,type,messages_ready,messages_unacknowledged,consumers,leader,members,online,policy,state
 *   queueDetail(vhost, name): String   GET /api/queues/{vhost}/{name}
 * Why exactly these columns (spike S-B, 1000 queues, page size 500): without `columns` a page is 546 KB, with them 119 KB, every parsed field is
 * in the list (no second call per queue), and `policy` is in the list only through `columns` when no policy applies (null) or in either form when
 * one does. No `name` or `use_regex` parameter: the search is in memory (brief M2-08 technical points). No query on exchanges and bindings.
 * The vhost and the name are one path segment each: "/" is %2F, a space %20, never "+".
 */
class ManagementRequestsTopologyTest {

    private val requests = ManagementRequests("https://rabbit.example.com:15671")

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun `the queue list is the vhost path with the page, the maximum page size and the column list`() {
        val url = requests.queues("/", 2)
        assertEquals("https://rabbit.example.com:15671/api/queues/%2F", url.substringBefore('?'))
        assertEquals("2", query(url)["page"])
        assertEquals("500", query(url)["page_size"])
    }

    @Test
    fun `the columns are exactly the fields the parser reads`() {
        val columns = query(requests.queues("/", 1)).getValue("columns").split(",").toSet()
        assertEquals(
            setOf("name", "type", "messages_ready", "messages_unacknowledged", "consumers", "leader", "members", "online", "policy", "state"),
            columns,
        )
    }

    @Test
    fun `the list is never filtered by the broker`() {
        val q = query(requests.queues("/", 1))
        assertFalse("name" in q, "query: $q")
        assertFalse("use_regex" in q, "query: $q")
        assertEquals(setOf("page", "page_size", "columns"), q.keys)
    }

    @Test
    fun `the page number is the one asked for`() {
        assertEquals("1", query(requests.queues("/", 1))["page"])
        assertEquals("37", query(requests.queues("/", 37))["page"])
    }

    @Test
    fun `the detail is the single queue path`() {
        assertEquals("https://rabbit.example.com:15671/api/queues/%2F/dev-jobs", requests.queueDetail("/", "dev-jobs"))
    }

    @Test
    fun `a vhost or a queue name with a space, a slash, a plus or a non ASCII letter is one encoded segment`() {
        assertEquals("https://rabbit.example.com:15671/api/queues/my%20vhost%2Fx/a%2Fb%20c%2Bd", requests.queueDetail("my vhost/x", "a/b c+d"))
        assertEquals("https://rabbit.example.com:15671/api/queues/%C3%A9t%C3%A9/%C3%A9", requests.queueDetail("été", "é"))
        assertEquals("https://rabbit.example.com:15671/api/queues/a%2Bb", requests.queues("a+b", 1).substringBefore('?'))
    }

    @Test
    fun `a plain profile asks over http`() {
        val plain = ManagementRequests("http://rabbit.example.com:15672", BrokerTransport.Plain)
        assertEquals("http://rabbit.example.com:15672/api/queues/%2F", plain.queues("/", 1).substringBefore('?'))
        assertEquals("http://rabbit.example.com:15672/api/queues/%2F/q", plain.queueDetail("/", "q"))
    }

    @Test
    fun `the vhost list is the one path without a query, over https or http`() {
        assertEquals("https://rabbit.example.com:15671/api/vhosts", requests.vhosts())
        assertEquals("http://rabbit.example.com:15672/api/vhosts", ManagementRequests("http://rabbit.example.com:15672", BrokerTransport.Plain).vhosts())
    }

    // The two paths RabbitMQ has to read messages: POST .../get and DELETE .../contents. A queue NAME may end in "/get" only inside one segment.
    @Test
    fun `no topology URL ends in get or contents or contains purge`() {
        val urls = listOf(requests.queues("/", 1), requests.queues("many", 3), requests.queueDetail("/", "q"), requests.exchanges("/"), requests.bindings("/"), requests.vhosts())
        urls.forEach { url ->
            val path = url.substringBefore('?')
            assertFalse(path.endsWith("/get"), url)
            assertFalse(path.endsWith("/contents"), url)
            assertFalse(url.contains("purge"), url)
        }
    }
}
