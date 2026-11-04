package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-03 part 2 (no Docker): the recorded bodies of `fixtures/m2-02/` parse into the core data classes. The same
 * values feed `FakeStreamCatalog.stream(...)` in core, so fake and parser agree. Contract proposed by the test
 * developer, all in package `dev.caerbannog.datarabbit`, `internal object ManagementJson`:
 *   parseStreamPage(body: String): CatalogResult<StreamPage>       (keeps only `type` "stream")
 *   parseStreamDetail(body: String): CatalogResult<StreamSummary>
 *   parsePublishers(body: String): CatalogResult<List<PublisherRow>>
 *   parseConsumers(body: String, stream: String): CatalogResult<List<ConsumerRow>>   (keeps rows whose `queue.name` is [stream])
 *   parseWhoamiTags(body: String): CatalogResult<List<String>>
 * A body that cannot be parsed is `CatalogResult.Unexpected`, never an exception. `ignoreUnknownKeys`; `messages` optional.
 * Core data classes (package `dev.caerbannog.core.streams`), see docs/tasks/M2-03-stream-gateway.md Status part 2.
 */
class ManagementJsonTest {

    private fun <T> success(result: CatalogResult<T>): T {
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a parsed value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<T>).value
    }

    private val recordedStream = StreamSummary(
        name = "<name>", vhost = "/", messages = 3, consumers = 0, segments = 1, firstTimestamp = 1_793_799_253_178,
        leader = "rabbit@node1", node = "rabbit@node1", members = listOf("rabbit@node1"), online = listOf("rabbit@node1"),
        retention = Retention(),
    )

    @Test
    fun `a page with one stream gives the stream and the brokers pagination numbers`() {
        val page = success(ManagementJson.parseStreamPage(FixtureFiles.text("m2-02/queues-page-one-stream.json")))
        assertEquals(
            StreamPage(
                rows = listOf(recordedStream), page = 1, pageCount = 1, pageSize = 100,
                filteredCount = 1, itemCount = 1, totalCount = 2,
            ),
            page,
        )
    }

    @Test
    fun `a page of only classic queues gives no rows but keeps the numbers`() {
        val page = success(ManagementJson.parseStreamPage(FixtureFiles.text("m2-02/queues-search-page.json")))
        assertEquals(emptyList<StreamSummary>(), page.rows)
        assertEquals(StreamPage(emptyList(), page = 1, pageCount = 3, pageSize = 5, filteredCount = 13, itemCount = 5, totalCount = 13), page)
    }

    @Test
    fun `a mixed page keeps only the streams, in the brokers order`() {
        val body = """{"filtered_count":4,"item_count":4,"page":1,"page_count":1,"page_size":100,"total_count":4,"items":[
            {"name":"q1","vhost":"/","type":"quorum","consumers":0,"messages":9},
            {"name":"s1","vhost":"/","type":"stream","consumers":2,"messages":10,"segments":1,"first_timestamp":5,"arguments":{"x-queue-type":"stream"}},
            {"name":"c1","vhost":"/","type":"classic","consumers":0,"messages":0},
            {"name":"s2","vhost":"other","type":"stream","consumers":0,"messages":0,"arguments":{"x-queue-type":"stream"}}]}"""
        val page = success(ManagementJson.parseStreamPage(body))
        assertEquals(listOf("s1" to "/", "s2" to "other"), page.rows.map { it.name to it.vhost })
        assertEquals(4, page.itemCount, "item_count is the brokers, not the number of streams")
    }

    @Test
    fun `partition streams without statistics columns parse, messages stay unknown`() {
        val page = success(ManagementJson.parseStreamPage(FixtureFiles.text("m2-02/queues-super-stream-partitions.json")))
        assertEquals(3, page.rows.size)
        assertTrue(page.rows.all { it.messages == null }, "no messages column was returned: ${page.rows}")
    }

    @Test
    fun `a stream detail has its retention parsed from the arguments`() {
        val detail = success(ManagementJson.parseStreamDetail(FixtureFiles.text("m2-02/queue-stream-with-data.json")))
        assertEquals(
            recordedStream.copy(
                messages = 1000, firstTimestamp = 1_793_799_283_656,
                retention = Retention(maxAge = "7D", maxLengthBytes = 1_000_000_000, segmentSizeBytes = 50_000_000),
            ),
            detail,
        )
    }

    @Test
    fun `a max age the broker reports as 604800s is passed through unchanged, not normalised`() {
        val body = """{"name":"s","vhost":"/","type":"stream","messages":1,"arguments":{"x-queue-type":"stream","x-max-age":"604800s"}}"""
        assertEquals("604800s", success(ManagementJson.parseStreamDetail(body)).retention.maxAge)
    }

    @Test
    fun `a stream without arguments has no retention`() {
        val page = success(ManagementJson.parseStreamPage(FixtureFiles.text("m2-02/queues-page-one-stream.json")))
        assertEquals(Retention(), page.rows.single().retention)
    }

    @Test
    fun `messages missing in a brand-new stream is null, not 0`() {
        val body = """{"filtered_count":1,"item_count":1,"page":1,"page_count":1,"page_size":100,"total_count":1,"items":[
            {"name":"new","vhost":"/","type":"stream","consumers":0,"arguments":{"x-queue-type":"stream"}}]}"""
        assertNull(success(ManagementJson.parseStreamPage(body)).rows.single().messages)
    }

    @Test
    fun `leader, node, members and online may be missing, and unknown keys are ignored`() {
        val body = """{"filtered_count":1,"item_count":1,"page":1,"page_count":1,"page_size":100,"total_count":1,"items":[
            {"name":"odd","vhost":"/","type":"stream","consumers":1,"messages":2,"some_future_key":{"a":[1,2]}}]}"""
        val row = success(ManagementJson.parseStreamPage(body)).rows.single()
        assertNull(row.leader)
        assertNull(row.node)
        assertEquals(emptyList<String>(), row.members)
        assertEquals(emptyList<String>(), row.online)
    }

    @Test
    fun `unusual names survive parsing unchanged`() {
        val name = "ordérs/events %2F + ünï \"quoted\""
        val body = """{"filtered_count":1,"item_count":1,"page":1,"page_count":1,"page_size":100,"total_count":1,"items":[
            {"name":${kotlinx.serialization.json.JsonPrimitive(name)},"vhost":"/","type":"stream","consumers":0,"messages":1}]}"""
        assertEquals(name, success(ManagementJson.parseStreamPage(body)).rows.single().name)
    }

    @Test
    fun `garbage is Unexpected and never throws`() {
        for (body in listOf("", "null", "[]", "{}", "42", "not json", """{"items":"x"}""", """{"items":[{"name":1}],"page":"a"}""")) {
            assertEquals(CatalogResult.Unexpected, ManagementJson.parseStreamPage(body), "body <$body>")
            assertEquals(CatalogResult.Unexpected, ManagementJson.parseStreamDetail(body), "detail body <$body>")
        }
        for (body in listOf("", "null", "{}", "42", "not json", """[{"queue":1}]""")) {
            assertEquals(CatalogResult.Unexpected, ManagementJson.parsePublishers(body), "publishers body <$body>")
            assertEquals(CatalogResult.Unexpected, ManagementJson.parseConsumers(body, "s"), "consumers body <$body>")
        }
    }

    // --- publishers and consumers -----------------------------------------------------------------

    @Test
    fun `a publisher row has its connection and counters`() {
        val rows = success(ManagementJson.parsePublishers(FixtureFiles.text("m2-02/stream-publishers.json")))
        assertEquals(
            listOf(
                PublisherRow(
                    connectionName = "192.168.65.1:42769 -> 172.17.0.4:5551", user = "guest", publisherId = 0,
                    reference = "spike-pub", published = 10, confirmed = 10, errored = 0,
                ),
            ),
            rows,
        )
    }

    @Test
    fun `no publishers is an empty list, not an error`() {
        assertEquals(emptyList<PublisherRow>(), success(ManagementJson.parsePublishers(FixtureFiles.text("m2-02/stream-publishers-empty.json"))))
    }

    @Test
    fun `an unnamed publisher keeps a null reference`() {
        val body = """[{"publisher_id":1,"published":2,"confirmed":2,"errored":0,"queue":{"name":"s","vhost":"/"},
            "connection_details":{"name":"c","user":"u"}}]"""
        assertNull(success(ManagementJson.parsePublishers(body)).single().reference)
    }

    @Test
    fun `a consumer row has the consumer reference, the offset and the lag`() {
        val rows = success(ManagementJson.parseConsumers(FixtureFiles.text("m2-02/stream-consumers.json"), "<name>"))
        assertEquals(
            listOf(
                ConsumerRow(
                    connectionName = "192.168.65.1:49661 -> 172.17.0.4:5551", user = "guest", subscriptionId = 0,
                    name = "spike-cons", active = true, activityStatus = "up", consumed = 10, credits = 10,
                    offset = 9, offsetLag = 1,
                ),
            ),
            rows,
        )
    }

    @Test
    fun `consumers are filtered on the queue name client side`() {
        val body = FixtureFiles.text("m2-02/stream-consumers.json")
        assertEquals(1, success(ManagementJson.parseConsumers(body, "<name>")).size)
        assertEquals(emptyList<ConsumerRow>(), success(ManagementJson.parseConsumers(body, "other-stream")))
        assertEquals(emptyList<ConsumerRow>(), success(ManagementJson.parseConsumers(body, "<NAME>")), "names are exact")
    }

    @Test
    fun `no consumers is an empty list`() {
        assertEquals(emptyList<ConsumerRow>(), success(ManagementJson.parseConsumers(FixtureFiles.text("m2-02/stream-consumers-empty.json"), "s")))
    }

    @Test
    fun `an unnamed consumer keeps a null name`() {
        val body = """[{"subscription_id":3,"consumed":0,"credits":10,"offset":0,"offset_lag":0,"active":true,
            "activity_status":"up","queue":{"name":"s","vhost":"/"},"connection_details":{"name":"c","user":"u"}}]"""
        assertNull(success(ManagementJson.parseConsumers(body, "s")).single().name)
    }

    // --- whoami (shape of RabbitMQ 4: {"name": ..., "tags": [...]}; confirmed against a real broker by RealBrokerCatalogTest) ---

    @Test
    fun `whoami tags are read as a list`() {
        assertEquals(listOf("monitoring"), success(ManagementJson.parseWhoamiTags("""{"name":"mon","tags":["monitoring"]}""")))
        assertEquals(listOf("administrator", "management"), success(ManagementJson.parseWhoamiTags("""{"name":"a","tags":["administrator","management"]}""")))
        assertEquals(emptyList<String>(), success(ManagementJson.parseWhoamiTags("""{"name":"plain","tags":[]}""")))
    }
}
