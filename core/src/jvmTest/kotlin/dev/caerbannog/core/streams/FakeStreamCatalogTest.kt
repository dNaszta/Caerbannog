package dev.caerbannog.core.streams

import dev.caerbannog.core.assertReadableWhileRecording
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-03 part 2: the fake must behave like the real management API or every view model test built on it lies.
 * Each test pins one measured fact (spec "Management API facts"). The fake is shared with the Gherkin steps.
 * Contract: `StreamListRequest(vhost = "/", page = 1, pageSize = 100, nameFilter: String? = null, useRegex = false)`,
 * `StreamPage(rows, page, pageCount, pageSize, filteredCount, itemCount, totalCount)`, `CatalogResult`.
 */
class FakeStreamCatalogTest {

    private fun mixed(streams: Int, queues: Int): FakeStreamCatalog {
        val catalog = FakeStreamCatalog()
        // The broker's own order: queues first, streams after, so a page can hold no stream at all.
        repeat(queues) { catalog.addQueue("q-$it") }
        repeat(streams) { catalog.addStream(stream("s-$it")) }
        return catalog
    }

    private fun page(catalog: FakeStreamCatalog, request: StreamListRequest): StreamPage {
        val result = runBlocking { catalog.listStreams(request) }
        assertIs<CatalogResult.Success<StreamPage>>(result, "expected a page, got $result")
        return result.value
    }

    @Test
    fun `the list answers for the requested vhost only, counts included`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(FakeStreamCatalog.stream("here")).addStream(FakeStreamCatalog.stream("elsewhere", vhost = "other"))
            .addQueue("q-other", vhost = "other")
        val result = page(catalog, StreamListRequest(vhost = "/"))
        assertEquals(listOf("here"), result.rows.map { it.name })
        assertEquals(1, result.totalCount)
        assertEquals(1, result.filteredCount)
        assertEquals(listOf("elsewhere"), page(catalog, StreamListRequest(vhost = "other")).rows.map { it.name })
    }

    @Test
    fun `only streams are returned from a mixed broker`() = runBlocking<Unit> {
        val result = page(mixed(streams = 4, queues = 9), StreamListRequest(vhost = "/", pageSize = 100))
        assertEquals(listOf("s-0", "s-1", "s-2", "s-3"), result.rows.map { it.name })
    }

    @Test
    fun `the pagination numbers are the brokers, counted over all queues`() = runBlocking<Unit> {
        // Recorded shape: filtered 13, item_count 5, page_count 3, page_size 5, total 13 (queues-search-page.json).
        val result = page(mixed(streams = 3, queues = 10), StreamListRequest(vhost = "/", page = 1, pageSize = 5))
        assertEquals(1, result.page)
        assertEquals(3, result.pageCount)
        assertEquals(5, result.pageSize)
        assertEquals(13, result.filteredCount)
        assertEquals(5, result.itemCount)
        assertEquals(13, result.totalCount)
    }

    @Test
    fun `a page can hold fewer streams than the page size, even none`() = runBlocking<Unit> {
        val result = page(mixed(streams = 3, queues = 10), StreamListRequest(vhost = "/", page = 1, pageSize = 5))
        assertTrue(result.rows.isEmpty(), "the first 5 of the broker's order are queues: ${result.rows}")
        val last = page(mixed(streams = 3, queues = 10), StreamListRequest(vhost = "/", page = 3, pageSize = 5))
        assertEquals(listOf("s-0", "s-1", "s-2"), last.rows.map { it.name })
        assertEquals(3, last.itemCount)
    }

    @Test
    fun `page size 500 is allowed and 501, 0 and a negative one are bad paging`() = runBlocking<Unit> {
        val catalog = mixed(streams = 2, queues = 0)
        assertEquals(2, page(catalog, StreamListRequest(vhost = "/", pageSize = 500)).rows.size)
        for (size in listOf(501, 0, -1)) {
            assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(vhost = "/", pageSize = size)), "page size $size")
        }
    }

    @Test
    fun `page 0 and a page past the last one are bad paging`() = runBlocking<Unit> {
        val catalog = mixed(streams = 6, queues = 0)
        assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(vhost = "/", page = 0)))
        assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(vhost = "/", page = 3, pageSize = 5)))
        assertEquals(1, page(catalog, StreamListRequest(vhost = "/", page = 2, pageSize = 5)).rows.size)
    }

    @Test
    fun `an empty broker lists page 1 with no rows`() = runBlocking<Unit> {
        val result = page(FakeStreamCatalog(), StreamListRequest(vhost = "/"))
        assertEquals(emptyList(), result.rows)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun `the name filter is a substring, a regex only when asked`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("orders-events")).addStream(stream("audit-log")).addStream(stream("orders.v2"))
        assertEquals(listOf("orders-events", "orders.v2"), page(catalog, StreamListRequest(vhost = "/", nameFilter = "orders")).rows.map { it.name })
        assertEquals(emptyList(), page(catalog, StreamListRequest(vhost = "/", nameFilter = "^audit.*$")).rows.map { it.name }, "a regex is literal text without useRegex")
        assertEquals(listOf("audit-log"), page(catalog, StreamListRequest(vhost = "/", nameFilter = "^audit.*$", useRegex = true)).rows.map { it.name })
    }

    @Test
    fun `an invalid regex is no error and matches nothing`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("orders-events"))
        val result = page(catalog, StreamListRequest(vhost = "/", nameFilter = "([", useRegex = true))
        assertEquals(emptyList(), result.rows)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun `a user without the management tag is not authorised everywhere`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("s")).also { it.visibility = Visibility.NONE }
        assertEquals(CatalogResult.NotAuthorised, catalog.listStreams(StreamListRequest(vhost = "/")))
        assertEquals(CatalogResult.NotAuthorised, catalog.streamDetail("/", "s"))
        assertEquals(CatalogResult.NotAuthorised, catalog.publishers("/", "s"))
        assertEquals(CatalogResult.NotAuthorised, catalog.consumers("/", "s"))
    }

    @Test
    fun `capabilities of a user without the tag say none and plugin unknown`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().also { it.visibility = Visibility.NONE }
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.UNKNOWN, Visibility.NONE)), catalog.capabilities())
    }

    @Test
    fun `capabilities report the plugin and the visibility`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().also { it.visibility = Visibility.OWN_CONNECTIONS_ONLY }
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.PRESENT, Visibility.OWN_CONNECTIONS_ONLY)), catalog.capabilities())
        catalog.pluginPresent = false
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.MISSING, Visibility.OWN_CONNECTIONS_ONLY)), catalog.capabilities())
    }

    @Test
    fun `a missing plugin fails publishers and consumers but not the stream list`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("s")).also { it.pluginMissing() }
        assertEquals(CatalogResult.PluginMissing, catalog.publishers("/", "s"))
        assertEquals(CatalogResult.PluginMissing, catalog.consumers("/", "s"))
        assertEquals(1, page(catalog, StreamListRequest(vhost = "/")).rows.size)
    }

    private fun FakeStreamCatalog.pluginMissing() {
        pluginPresent = false
    }

    @Test
    fun `no publishers is an empty list, not an error`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("s"))
        assertEquals(CatalogResult.Success(emptyList<PublisherRow>()), catalog.publishers("/", "s"))
        assertEquals(CatalogResult.Success(emptyList<ConsumerRow>()), catalog.consumers("/", "s"))
    }

    @Test
    fun `a stream that does not exist is not found`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("s")).addQueue("q")
        assertEquals(CatalogResult.NotFound, catalog.streamDetail("/", "gone"))
        assertEquals(CatalogResult.NotFound, catalog.streamDetail("/", "q"), "a quorum queue is not a stream")
        assertEquals(CatalogResult.NotFound, catalog.streamDetail("other-vhost", "s"))
        assertEquals("s", (catalog.streamDetail("/", "s") as CatalogResult.Success).value.name)
    }

    @Test
    fun `an unreachable broker fails every call`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().also { it.unreachable = true }
        assertEquals(CatalogResult.Unreachable, catalog.listStreams(StreamListRequest(vhost = "/")))
        assertEquals(CatalogResult.Unreachable, catalog.capabilities())
    }

    @Test
    fun `a stream whose message count is not known yet keeps null, not zero`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("new", messages = null))
        assertEquals(null, page(catalog, StreamListRequest(vhost = "/")).rows.single().messages)
    }

    // --- M2-04: D8 publisherCounts and the test helpers ------------------------------------------------------

    private fun rows(count: Int) = List(count) { PublisherRow("c$it", "svc", it, null, 1, 1, 0) }

    @Test
    fun `publisherCounts groups the publishers of a vhost by stream, streams without any are absent`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("a")).addStream(stream("b")).addStream(stream("c", vhost = "other"))
        catalog.setPublishers("/", "a", rows(2))
        catalog.setPublishers("/", "b", emptyList())
        catalog.setPublishers("other", "c", rows(5))

        assertEquals(CatalogResult.Success(mapOf("a" to 2)), catalog.publisherCounts("/"))
        assertEquals(CatalogResult.Success(mapOf("c" to 5)), catalog.publisherCounts("other"))
        assertEquals(CatalogResult.Success(emptyMap<String, Int>()), catalog.publisherCounts("nowhere"))
    }

    @Test
    fun `publisherCounts is counted as one call named publisherCounts`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog()
        catalog.publisherCounts("/")
        assertEquals(listOf("publisherCounts"), catalog.calls.toList())
    }

    @Test
    fun `publisherCounts follows the same refusals as the other calls`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("a"))
        catalog.pluginPresent = false
        assertEquals(CatalogResult.PluginMissing, catalog.publisherCounts("/"))
        catalog.pluginPresent = true
        catalog.visibility = Visibility.NONE
        assertEquals(CatalogResult.NotAuthorised, catalog.publisherCounts("/"))
        catalog.visibility = Visibility.FULL
        catalog.unreachable = true
        assertEquals(CatalogResult.Unreachable, catalog.publisherCounts("/"))
    }

    @Test
    fun `adding a stream again replaces it, a name is unique in a vhost`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("a", messages = 1)).addStream(stream("a", messages = 9)).addStream(stream("a", vhost = "v"))
        val list = page(catalog, StreamListRequest(vhost = "/"))
        assertEquals(listOf(9L), list.rows.filter { it.vhost == "/" }.map { it.messages })
        assertEquals(1, list.totalCount, "the count is per vhost; the stream of vhost v is not in it")
        assertEquals(1, page(catalog, StreamListRequest(vhost = "v")).totalCount, "the same name in another vhost is a second stream")
    }

    @Test
    fun `one list page can be made to fail and the others still work`() = runBlocking<Unit> {
        val catalog = mixed(streams = 1, queues = 9)
        catalog.failListPage(2, CatalogResult.Unexpected)
        assertEquals(CatalogResult.Unexpected, catalog.listStreams(StreamListRequest(vhost = "/", page = 2, pageSize = 5)))
        assertIs<CatalogResult.Success<StreamPage>>(catalog.listStreams(StreamListRequest(vhost = "/", page = 1, pageSize = 5)))
        catalog.clearFailures()
        assertIs<CatalogResult.Success<StreamPage>>(catalog.listStreams(StreamListRequest(vhost = "/", page = 2, pageSize = 5)))
    }

    @Test
    fun `a held call answers only after the gate is released, with the answer it computed when it started`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("a"))
        val gate = catalog.holdNext("listStreams")
        val late = async { catalog.listStreams(StreamListRequest(vhost = "/")) }
        yield()
        assertEquals(false, late.isCompleted)
        assertEquals(listOf("listStreams"), catalog.calls.toList(), "the call is counted when it starts")
        catalog.addStream(stream("b")) // changes after the call started

        gate.release()

        assertEquals(listOf("a"), (late.await() as CatalogResult.Success<StreamPage>).value.rows.map { it.name })
        // one-shot: the next call is not held
        assertEquals(2, page(catalog, StreamListRequest(vhost = "/")).rows.size)
    }

    @Test
    fun `calls can be counted while another thread records calls`() {
        val catalog = FakeStreamCatalog()
        assertReadableWhileRecording(
            what = "FakeStreamCatalog.calls",
            record = { appends -> runBlocking { repeat(appends) { catalog.capabilities() } } },
            count = { catalog.calls.count { it == "capabilities" } },
        )
    }
}
