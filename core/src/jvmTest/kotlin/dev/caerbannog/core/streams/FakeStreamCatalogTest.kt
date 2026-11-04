package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-03 part 2: the fake must behave like the real management API or every view model test built on it lies.
 * Each test pins one measured fact (spec "Management API facts"). The fake is shared with the Gherkin steps.
 * Contract: `StreamListRequest(page = 1, pageSize = 100, nameFilter: String? = null, useRegex = false)`,
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
    fun `only streams are returned from a mixed broker`() = runBlocking<Unit> {
        val result = page(mixed(streams = 4, queues = 9), StreamListRequest(pageSize = 100))
        assertEquals(listOf("s-0", "s-1", "s-2", "s-3"), result.rows.map { it.name })
    }

    @Test
    fun `the pagination numbers are the brokers, counted over all queues`() = runBlocking<Unit> {
        // Recorded shape: filtered 13, item_count 5, page_count 3, page_size 5, total 13 (queues-search-page.json).
        val result = page(mixed(streams = 3, queues = 10), StreamListRequest(page = 1, pageSize = 5))
        assertEquals(1, result.page)
        assertEquals(3, result.pageCount)
        assertEquals(5, result.pageSize)
        assertEquals(13, result.filteredCount)
        assertEquals(5, result.itemCount)
        assertEquals(13, result.totalCount)
    }

    @Test
    fun `a page can hold fewer streams than the page size, even none`() = runBlocking<Unit> {
        val result = page(mixed(streams = 3, queues = 10), StreamListRequest(page = 1, pageSize = 5))
        assertTrue(result.rows.isEmpty(), "the first 5 of the broker's order are queues: ${result.rows}")
        val last = page(mixed(streams = 3, queues = 10), StreamListRequest(page = 3, pageSize = 5))
        assertEquals(listOf("s-0", "s-1", "s-2"), last.rows.map { it.name })
        assertEquals(3, last.itemCount)
    }

    @Test
    fun `page size 500 is allowed and 501, 0 and a negative one are bad paging`() = runBlocking<Unit> {
        val catalog = mixed(streams = 2, queues = 0)
        assertEquals(2, page(catalog, StreamListRequest(pageSize = 500)).rows.size)
        for (size in listOf(501, 0, -1)) {
            assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(pageSize = size)), "page size $size")
        }
    }

    @Test
    fun `page 0 and a page past the last one are bad paging`() = runBlocking<Unit> {
        val catalog = mixed(streams = 6, queues = 0)
        assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(page = 0)))
        assertEquals(CatalogResult.BadPaging, catalog.listStreams(StreamListRequest(page = 3, pageSize = 5)))
        assertEquals(1, page(catalog, StreamListRequest(page = 2, pageSize = 5)).rows.size)
    }

    @Test
    fun `an empty broker lists page 1 with no rows`() = runBlocking<Unit> {
        val result = page(FakeStreamCatalog(), StreamListRequest())
        assertEquals(emptyList(), result.rows)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun `the name filter is a substring, a regex only when asked`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("orders-events")).addStream(stream("audit-log")).addStream(stream("orders.v2"))
        assertEquals(listOf("orders-events", "orders.v2"), page(catalog, StreamListRequest(nameFilter = "orders")).rows.map { it.name })
        assertEquals(emptyList(), page(catalog, StreamListRequest(nameFilter = "^audit.*$")).rows.map { it.name }, "a regex is literal text without useRegex")
        assertEquals(listOf("audit-log"), page(catalog, StreamListRequest(nameFilter = "^audit.*$", useRegex = true)).rows.map { it.name })
    }

    @Test
    fun `an invalid regex is no error and matches nothing`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("orders-events"))
        val result = page(catalog, StreamListRequest(nameFilter = "([", useRegex = true))
        assertEquals(emptyList(), result.rows)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun `a user without the management tag is not authorised everywhere`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("s")).also { it.visibility = Visibility.NONE }
        assertEquals(CatalogResult.NotAuthorised, catalog.listStreams(StreamListRequest()))
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
        assertEquals(1, page(catalog, StreamListRequest()).rows.size)
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
        assertEquals(CatalogResult.Unreachable, catalog.listStreams(StreamListRequest()))
        assertEquals(CatalogResult.Unreachable, catalog.capabilities())
    }

    @Test
    fun `a stream whose message count is not known yet keeps null, not zero`() = runBlocking<Unit> {
        val catalog = FakeStreamCatalog().addStream(stream("new", messages = null))
        assertEquals(null, page(catalog, StreamListRequest()).rows.single().messages)
    }
}
