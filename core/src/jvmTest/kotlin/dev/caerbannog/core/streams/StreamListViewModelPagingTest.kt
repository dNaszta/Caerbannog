package dev.caerbannog.core.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-04 step 2: the paging strategy over ALL queues (the broker ignores `type=stream` and pages over every queue, so a page
 * of the broker can hold fewer streams than asked, even none, and its counts are queue counts) and the in-memory page, filter
 * and sort. Contract: see [StreamListHarness]. Rules pinned here:
 *  - the view model reads broker pages of 500 (`StreamListRequest(page = n, pageSize = 500)`, no name filter) until the last page,
 *    keeps what the catalog returns (streams only) and pages the streams in memory; the displayed total is the stream count;
 *  - one list call per 500 queues per load; a failure on any page fails the load as a whole (no half list);
 *  - first offsets are asked only for the streams of the visible page.
 */
class StreamListViewModelPagingTest {

    @Test
    fun `12 streams among 700 quorum queues read 1 to 5 of 12`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        repeat(700) { s.catalog.addQueue("q-$it") }
        s.streams(12)
        s.open()

        assertEquals("1 to 5 of 12", s.state.rangeText)
        assertEquals(12, s.state.totalStreams)
        assertEquals(listOf("s-00", "s-01", "s-02", "s-03", "s-04"), s.names())
        assertTrue(s.state.rows.none { it.name.startsWith("q-") }, "no quorum queue is listed")
        assertTrue(s.state.hasNextPage)
        assertFalse(s.state.hasPreviousPage)
    }

    @Test
    fun `the list calls are ceil of queues over 500 per load, with page size 500 and no name filter`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        repeat(1200) { s.catalog.addQueue("q-$it") }
        s.streams(12) // 1212 entries: 3 broker pages
        s.open()
        assertEquals(3, s.count("listStreams"))

        s.vm.refreshNow()
        s.settle()
        assertEquals(6, s.count("listStreams"), "3 more for the second load")
    }

    @Test
    fun `a small broker needs one list call`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        assertEquals(1, s.count("listStreams"))
    }

    @Test
    fun `an exact multiple of 500 does not ask for a page past the end`() = runTest {
        val s = StreamListHarness(this)
        repeat(488) { s.catalog.addQueue("q-$it") }
        s.streams(12) // exactly 500 entries: one page
        s.open()

        assertEquals(1, s.count("listStreams"))
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `a failure on page 2 fails the load as a whole and keeps the old rows, stale`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        repeat(700) { s.catalog.addQueue("q-$it") }
        s.streams(12)
        s.open()
        val before = s.state.rows
        assertEquals(5, before.size)

        s.catalog.failListPage(2, CatalogResult.Unexpected)
        s.vm.refreshNow()
        s.settle()

        assertEquals(before, s.state.rows, "no half list: the rows of the last complete load stay")
        assertTrue(s.state.stale)
        assertEquals("1 to 5 of 12", s.state.rangeText)
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `a failure on page 2 of the first load is Failed, not a list of the streams of page 1`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        repeat(700) { s.catalog.addQueue("q-$it") }
        s.streams(12)
        s.catalog.failListPage(2, CatalogResult.Unexpected)
        s.open()

        assertIs<StreamListScreen.Failed>(s.state.screen)
        assertEquals(emptyList(), s.state.rows)
    }

    @Test
    fun `12 streams are paged 5 5 2 and the page moves through the loaded list without a new broker call`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.streams(12)
        s.open()
        val listCalls = s.count("listStreams")

        s.vm.nextPage()
        s.settle()
        assertEquals("6 to 10 of 12", s.state.rangeText)
        assertEquals(listOf("s-05", "s-06", "s-07", "s-08", "s-09"), s.names())
        assertTrue(s.state.hasNextPage && s.state.hasPreviousPage)

        s.vm.nextPage()
        s.settle()
        assertEquals("11 to 12 of 12", s.state.rangeText)
        assertEquals(listOf("s-10", "s-11"), s.names())
        assertFalse(s.state.hasNextPage)

        s.vm.previousPage()
        s.settle()
        assertEquals("6 to 10 of 12", s.state.rangeText)
        assertEquals(listCalls, s.count("listStreams"), "paging is in memory: the broker list is not read again")
    }

    @Test
    fun `first offsets are asked only for the visible page`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.streams(12)
        s.open()
        // The fake logs "<vhost>/<stream>", the vhost here is "/".
        assertEquals(setOf("//s-00", "//s-01", "//s-02", "//s-03", "//s-04"), s.offsets.calls.toSet())
        assertEquals(5, s.offsets.calls.size, "one call per visible row")

        s.vm.nextPage()
        s.settle()
        assertEquals(10, s.offsets.calls.size, "5 more for the second page, none for the third")
        assertTrue(s.offsets.calls.none { it.endsWith("s-10") || it.endsWith("s-11") })
    }

    @Test
    fun `page size 1 and a page size larger than the list`() = runTest {
        val s = StreamListHarness(this, pageSize = 1)
        s.streams(3)
        s.open()
        assertEquals("1 to 1 of 3", s.state.rangeText)
        assertEquals(listOf("s-00"), s.names())

        s.vm.setPageSize(100)
        s.settle()
        assertEquals("1 to 3 of 3", s.state.rangeText)
        assertFalse(s.state.hasNextPage)
    }

    @Test
    fun `a page past the last or before the first is clamped, never an exception or an empty page`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.streams(12)
        s.open()

        s.vm.setPage(99)
        s.settle()
        assertEquals("11 to 12 of 12", s.state.rangeText)
        s.vm.setPage(0)
        s.settle()
        assertEquals("1 to 5 of 12", s.state.rangeText)
        s.vm.setPage(-4)
        s.settle()
        assertEquals("1 to 5 of 12", s.state.rangeText)
    }

    @Test
    fun `the default page size is the constant 50`() = runTest {
        assertEquals(50, DEFAULT_PAGE_SIZE)
    }

    // --- filter and sort ---------------------------------------------------------------------------------

    @Test
    fun `the filter is a case-insensitive substring of the name and the range counts the filtered streams`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.addStream("orders-events")
        s.addStream("audit-log")
        s.addStream("payments-stream")
        s.addStream("Orders-Archive")
        s.open()

        s.vm.setFilter("orders")
        s.settle()

        assertEquals(listOf("Orders-Archive", "orders-events"), s.names().sorted())
        assertEquals("1 to 2 of 2", s.state.rangeText)
        assertEquals(2, s.state.totalStreams)
        assertEquals("orders", s.state.filter)
    }

    @Test
    fun `a filter is taken literally, a regex character is no regex and never throws`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a.b")
        s.addStream("axb")
        s.addStream("c(d")
        s.open()

        s.vm.setFilter("a.b")
        s.settle()
        assertEquals(listOf("a.b"), s.names())
        s.vm.setFilter("(")
        s.settle()
        assertEquals(listOf("c(d"), s.names())
        s.vm.setFilter("[")
        s.settle()
        assertEquals(emptyList(), s.names())
    }

    @Test
    fun `a filter with no match leaves an empty page, not the Empty screen and not an error`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a")
        s.open()

        s.vm.setFilter("zzz")
        s.settle()

        assertEquals(emptyList(), s.names())
        assertEquals("", s.state.rangeText)
        assertIs<StreamListScreen.Rows>(s.state.screen, "the broker HAS streams: the screen is not the 'no streams yet' one")
    }

    @Test
    fun `changing the filter goes back to page 1`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        s.streams(12)
        s.open()
        s.vm.setPage(3)
        s.settle()

        s.vm.setFilter("s-0")
        s.settle()

        assertEquals(1, s.state.page)
    }

    @Test
    fun `the filter does not go to the broker`() = runTest {
        val s = StreamListHarness(this)
        s.streams(3)
        s.open()
        s.vm.setFilter("s-01")
        s.settle()

        assertEquals(1, s.count("listStreams"), "the app holds all streams and filters in memory")
    }

    @Test
    fun `sort by name ascending and descending`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("b-stream")
        s.addStream("a-stream")
        s.addStream("c-stream")
        s.open()

        s.vm.setSort(StreamSort.NameAscending)
        s.settle()
        assertEquals(listOf("a-stream", "b-stream", "c-stream"), s.names())
        s.vm.setSort(StreamSort.NameDescending)
        s.settle()
        assertEquals(listOf("c-stream", "b-stream", "a-stream"), s.names())
    }

    @Test
    fun `sorting happens before paging, over the whole list`() = runTest {
        val s = StreamListHarness(this, pageSize = 2)
        s.addStream("b")
        s.addStream("a")
        s.addStream("d")
        s.addStream("c")
        s.open()

        s.vm.setSort(StreamSort.NameDescending)
        s.settle()
        assertEquals(listOf("d", "c"), s.names())
        s.vm.nextPage()
        s.settle()
        assertEquals(listOf("b", "a"), s.names())
    }

    @Test
    fun `a name that sorts like a number is sorted as text`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("s10")
        s.addStream("s2")
        s.addStream("s1")
        s.open()
        s.vm.setSort(StreamSort.NameAscending)
        s.settle()

        assertEquals(listOf("s1", "s10", "s2"), s.names())
    }
}
