package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-08a step 2 (no Docker): the topology parsers against the bodies recorded for spike S-A (`fixtures/m2-08/`, dev broker RabbitMQ 4.4.0,
 * node name replaced by `rabbit@node1`) and the exchange and binding bodies of M2-07. Contract (test developer; names pinned for the developer):
 *  - `ManagementJson.parseQueuePage(body, vhost): CatalogResult<QueuePage>`: the vhost comes from the CALLER (the list is asked with `columns`,
 *    which has no `vhost`); rows are the items whose `type` is not `stream`, in body order; `page`, `page_count`, `page_size` and `total_count`
 *    are the broker's (taken over all queues, streams included); a missing or non-numeric one is `Unexpected`.
 *  - `ManagementJson.parseQueueDetail(body): CatalogResult<QueueRow>`: one queue object (vhost from the body); a stream is `NotFound`.
 *  - queue fields (all recorded in S-A): `messages_ready` -> readyMessages, `messages_unacknowledged` -> unackedMessages, `consumers`
 *    (Long? and Int?: null when absent or not a number, never 0), `leader`, `members`, `online` (absent on classic: null / empty lists), `policy`
 *    (string, or null when JSON null OR absent: the full list leaves the key out when no policy applies, `columns=policy` sends null), `state`.
 *  - `ManagementJson.parseExchanges(body)` and `parseBindingList(body)`: plain arrays; the default exchange (`""`) and its implicit bindings are KEPT.
 *    `arguments` values become display text: a JSON string as is, anything else as its JSON text.
 *  - garbage (not JSON, wrong root, a row without `name`) is `Unexpected`, never an exception; unknown keys are ignored.
 */
class ManagementJsonTopologyTest {

    private fun page(fixture: String, vhost: String = "/"): QueuePage {
        val result = ManagementJson.parseQueuePage(FixtureFiles.text(fixture), vhost)
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a page, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<QueuePage>).value
    }

    private fun row(fixture: String, name: String, vhost: String = "/"): QueueRow = page(fixture, vhost).rows.single { it.name == name }

    private val columns = "m2-08/queues-quorum-classic-stream-columns.json"
    private val full = "m2-08/queues-quorum-classic-stream-full.json"

    // --- recorded bodies ---

    @Test
    fun `the columns list drops the stream and keeps the quorum and the classic queue in body order`() {
        val result = page(columns)
        assertEquals(listOf("dev-jobs", "spike-m208-classic", "spike-m208-quorum"), result.rows.map { it.name })
        assertEquals(listOf("quorum", "classic", "quorum"), result.rows.map { it.type })
    }

    @Test
    fun `the page numbers are the brokers, taken over all queues of the vhost`() {
        val result = page(columns)
        assertEquals(1, result.page)
        assertEquals(1, result.pageCount)
        assertEquals(500, result.pageSize)
        assertEquals(11, result.totalCount, "total_count counts the streams and the other queues too")
    }

    @Test
    fun `a quorum queue carries the recorded statistics, leader, members and policy`() {
        val q = row(columns, "spike-m208-quorum")
        assertEquals(
            QueueRow(
                name = "spike-m208-quorum", vhost = "/", type = "quorum", readyMessages = 3, unackedMessages = 0, consumers = 0,
                leader = "rabbit@node1", members = listOf("rabbit@node1"), online = listOf("rabbit@node1"), policy = "spike-m208-pol", state = "running",
            ),
            q,
        )
        assertTrue(q.isQuorum)
        assertFalse(q.hasOfflineMember)
    }

    @Test
    fun `a classic queue has counts and state but no leader, members or online list`() {
        val q = row(columns, "spike-m208-classic")
        assertEquals("classic", q.type)
        assertEquals(3L, q.readyMessages)
        assertEquals(0L, q.unackedMessages)
        assertNull(q.leader)
        assertEquals(emptyList<String>(), q.members)
        assertEquals(emptyList<String>(), q.online)
        assertEquals("running", q.state)
        assertFalse(q.hasOfflineMember)
    }

    @Test
    fun `the vhost of every row is the one the caller asked for`() {
        assertEquals(setOf("my vhost"), page(columns, vhost = "my vhost").rows.map { it.vhost }.toSet())
    }

    @Test
    fun `the full list without columns parses the same rows, and a queue with no policy has no policy key`() {
        val withColumns = page(columns).rows
        val withoutColumns = page(full).rows
        assertEquals(withColumns, withoutColumns, "the two bodies describe the same three queues")
        assertNull(withoutColumns.single { it.name == "dev-jobs" }.policy)
        assertEquals("spike-m208-pol", withoutColumns.single { it.name == "spike-m208-quorum" }.policy)
    }

    @Test
    fun `a page in the middle of a 1000 queue vhost reads the brokers page numbers`() {
        val result = page("m2-08/queues-many-columns-page-size-3.json", vhost = "many")
        assertEquals(listOf("many-queue-00000003", "many-queue-00000004", "many-queue-00000005"), result.rows.map { it.name })
        assertEquals(2, result.page)
        assertEquals(334, result.pageCount)
        assertEquals(3, result.pageSize)
        assertEquals(1000, result.totalCount)
    }

    @Test
    fun `queueDetail of a quorum queue carries the policy name and the vhost from the body`() {
        val result = ManagementJson.parseQueueDetail(FixtureFiles.text("m2-08/queue-single-quorum-with-policy.json"))
        assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
        val q = (result as CatalogResult.Success<QueueRow>).value
        assertEquals("spike-m208-quorum", q.name)
        assertEquals("/", q.vhost)
        assertEquals("spike-m208-pol", q.policy)
        assertEquals(3L, q.readyMessages)
        assertEquals(listOf("rabbit@node1"), q.members)
    }

    @Test
    fun `queueDetail of a classic queue has a null policy and no members`() {
        val q = (ManagementJson.parseQueueDetail(FixtureFiles.text("m2-08/queue-single-classic.json")) as CatalogResult.Success<QueueRow>).value
        assertNull(q.policy)
        assertEquals(emptyList<String>(), q.members)
        assertNull(q.leader)
    }

    @Test
    fun `queueDetail of a stream is NotFound, a stream is no queue here`() {
        val stream = """{"name":"dev-orders","vhost":"/","type":"stream","messages_ready":0,"consumers":0,"state":"running"}"""
        assertEquals(CatalogResult.NotFound, ManagementJson.parseQueueDetail(stream))
    }

    // --- hand-made: unverified ---

    @Test
    fun `HAND-MADE not measured, an online list shorter than the members flags the queue, 2 of 3`() {
        val rows = page("m2-08/queues-quorum-offline-member-hand-made.json").rows.associateBy { it.name }
        val degraded = rows.getValue("hand-degraded")
        assertEquals(2, degraded.membersOnline)
        assertEquals(3, degraded.membersTotal)
        assertTrue(degraded.hasOfflineMember)
        assertEquals(1L, degraded.unackedMessages)
        assertFalse(rows.getValue("hand-healthy").hasOfflineMember)
        val down = rows.getValue("hand-no-leader")
        assertNull(down.leader, "a JSON null leader is no leader")
        assertEquals("down", down.state)
        assertEquals(0, down.membersOnline)
        assertTrue(down.hasOfflineMember)
    }

    // --- missing and odd values ---

    private fun one(item: String): QueueRow {
        val body = """{"items":[$item],"page":1,"page_count":1,"page_size":500,"filtered_count":1,"item_count":1,"total_count":1}"""
        val result = ManagementJson.parseQueuePage(body, "/")
        assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
        return (result as CatalogResult.Success<QueuePage>).value.rows.single()
    }

    @Test
    fun `a queue the broker has sent no statistics for yet has null counts, not zero`() {
        val q = one("""{"name":"fresh","type":"quorum"}""")
        assertNull(q.readyMessages)
        assertNull(q.unackedMessages)
        assertNull(q.consumers)
        assertNull(q.state)
        assertNull(q.policy)
    }

    @Test
    fun `a count that is not a number is null, a numeric string is not a number`() {
        val q = one("""{"name":"odd","type":"classic","messages_ready":"7","messages_unacknowledged":null,"consumers":"x"}""")
        assertNull(q.readyMessages)
        assertNull(q.unackedMessages)
        assertNull(q.consumers)
    }

    @Test
    fun `an unusual queue name survives untouched`() {
        val name = "a/b c%d+é\"q"
        assertEquals(name, one("""{"name":"a/b c%d+é\"q","type":"quorum"}""").name)
    }

    @Test
    fun `an unknown type is kept as the brokers text, only stream is dropped`() {
        val body = """{"items":[{"name":"m","type":"mqtt"},{"name":"s","type":"stream"},{"name":"t"}],"page":1,"page_count":1,"page_size":500,"filtered_count":3,"item_count":3,"total_count":3}"""
        val rows = (ManagementJson.parseQueuePage(body, "/") as CatalogResult.Success<QueuePage>).value.rows
        assertEquals(listOf("m", "t"), rows.map { it.name })
        assertEquals("mqtt", rows[0].type)
        assertFalse(rows[0].isQuorum)
    }

    // --- garbage ---

    @Test
    fun `garbage is Unexpected, never an exception`() {
        val ok = """{"items":[],"page":1,"page_count":0,"page_size":500,"filtered_count":0,"item_count":0,"total_count":0}"""
        assertEquals(CatalogResult.Success(QueuePage(emptyList(), 1, 0, 500, 0)), ManagementJson.parseQueuePage(ok, "/"))
        listOf("", "<html>proxy</html>", "[]", "null", """{"items":"x"}""", """{"items":[{"type":"quorum"}],"page":1,"page_count":1,"page_size":500,"total_count":1}""", """{"items":[]}""")
            .forEach { assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueuePage(it, "/"), "body: $it") }
        listOf("", "[]", "<html/>", """{"type":"quorum"}""").forEach { assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueueDetail(it), "body: $it") }
        listOf("", "{}", "<html/>", """[{"type":"direct"}]""", """["x"]""").forEach { assertEquals(CatalogResult.Unexpected, ManagementJson.parseExchanges(it), "body: $it") }
        listOf("", "{}", "<html/>", """[{"source":"a"}]""", """[{"source":"a","destination":"b","destination_type":"queue","routing_key":"k"}, 3]""")
            .forEach { assertEquals(CatalogResult.Unexpected, ManagementJson.parseBindingList(it), "body: $it") }
    }

    // --- exchanges ---

    @Test
    fun `the exchanges of the dev vhost keep the default exchange and the super stream marker as text`() {
        val result = ManagementJson.parseExchanges(FixtureFiles.text("m2-07/exchanges-vhost-root.json"))
        assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
        val rows = (result as CatalogResult.Success<List<ExchangeRow>>).value
        assertEquals(11, rows.size)
        val default = rows.first { it.name.isEmpty() }
        assertTrue(default.isDefault)
        assertEquals(ExchangeRow("", "/", "direct", durable = true, autoDelete = false, internal = false), default)
        assertEquals(mapOf("x-super-stream" to "true"), rows.single { it.name == "orders" }.arguments)
        assertTrue(rows.single { it.name == "amq.rabbitmq.trace" }.internal)
        assertEquals("fanout", rows.single { it.name == "dev.fanout" }.type)
    }

    @Test
    fun `exchange argument values are display text, a string as is and anything else as its JSON text`() {
        val body = """[{"name":"x","vhost":"/","type":"topic","durable":false,"auto_delete":true,"internal":false,
            "arguments":{"s":"plain","n":3,"b":false,"l":[1,2],"z":null}}]"""
        val row = (ManagementJson.parseExchanges(body) as CatalogResult.Success<List<ExchangeRow>>).value.single()
        assertEquals(mapOf("s" to "plain", "n" to "3", "b" to "false", "l" to "[1,2]", "z" to "null"), row.arguments)
        assertFalse(row.durable)
        assertTrue(row.autoDelete)
    }

    // --- bindings ---

    @Test
    fun `the bindings of the dev vhost keep the implicit default exchange rows and the stream ones`() {
        val result = ManagementJson.parseBindingList(FixtureFiles.text("m2-07/bindings-vhost-root.json"))
        assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
        val rows = (result as CatalogResult.Success<List<BindingRow>>).value
        assertEquals(16, rows.size)
        assertEquals(BindingRow("", "/", "dev-audit", BindingDestinationType.QUEUE, "dev-audit"), rows.first())
        val partition = rows.single { it.source == "orders" && it.destination == "orders-1" }
        assertEquals(BindingRow("orders", "/", "orders-1", BindingDestinationType.QUEUE, "1", mapOf("x-stream-partition-order" to "1")), partition)
        assertEquals("", rows.single { it.source == "dev.fanout" && it.destination == "dev-retry" }.routingKey)
    }

    @Test
    fun `a binding to an exchange is an exchange destination`() {
        val rows = (ManagementJson.parseBindingList(FixtureFiles.text("m2-07/bindings-vhost-hand-made.json")) as CatalogResult.Success<List<BindingRow>>).value
        val toExchange = rows.single { it.destinationType == BindingDestinationType.EXCHANGE }
        assertEquals("hand", toExchange.source)
        assertEquals("hand2", toExchange.destination)
        assertEquals("x", toExchange.routingKey)
    }

    @Test
    fun `a binding without a routing key reads an empty one, an unknown destination type is Unexpected`() {
        val ok = """[{"source":"a","vhost":"/","destination":"b","destination_type":"queue","arguments":{}}]"""
        assertEquals("", (ManagementJson.parseBindingList(ok) as CatalogResult.Success<List<BindingRow>>).value.single().routingKey)
        val odd = """[{"source":"a","vhost":"/","destination":"b","destination_type":"stream","routing_key":"","arguments":{}}]"""
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseBindingList(odd))
    }
}
