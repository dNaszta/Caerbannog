package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-07 step 2 (tests first): super streams in the stream list view model. Contract, written by the test developer and flagged in the
 * report (see the brief, scope items 1 to 7):
 *  - `StreamCatalog.superStreams(vhost)` is called once per full load, after the stream pages and before the offsets.
 *  - `state.rows` is FLAT, in display order: a super stream row (kind SuperStream, type "Super stream", `partitionCount`, `expanded`)
 *    and, only while expanded, its partition rows right behind it (kind Partition, type "partition", `partitionOf` = the super stream name).
 *    Partitions are never top-level entries; paging, `totalStreams` and `rangeText` count ENTRIES (a super stream is one).
 *  - Super stream cells: messages = sum (only when every partition has a count, else `n/a`); first and last offset = [StreamListTexts.NOT_APPLICABLE];
 *    publishers = sum when every partition's count is shown (D8) else `unavailable`; consumers = sum; retention = the common value or `mixed`.
 *  - `toggleExpanded(name)`, `setKindFilter(kind)`; `state.kindFilter`; `state.groupingNotice` set only when no grouping was ever read.
 */
class StreamListViewModelSuperStreamTest {

    private val orders = listOf("orders-0", "orders-1", "orders-2")

    /** "orders" with three partitions holding 31200, 32900 and 32000 messages (the mockup), publishers 1, 2, 1, consumers 2, 2, 2. */
    private fun StreamListHarness.ordersSuperStream() {
        superStream("orders", orders, messages = listOf(31_200L, 32_900L, 32_000L), publishers = listOf(1, 2, 1), consumers = listOf(2, 2, 2))
    }

    private fun StreamListHarness.superStream(
        name: String,
        partitions: List<String>,
        messages: List<Long?> = partitions.map { 10L },
        publishers: List<Int> = partitions.map { 0 },
        consumers: List<Int> = partitions.map { 0 },
        retention: List<Retention> = partitions.map { Retention(maxAge = "604800s") },
    ) {
        partitions.forEachIndexed { i, p ->
            catalog.addStream(stream(p, messages = messages[i], consumers = consumers[i], retention = retention[i]))
            offsets.setFirst("/", p, 0)
            if (publishers[i] > 0) catalog.setPublishers("/", p, List(publishers[i]) { PublisherRow("c$it", "svc", it, null, 1, 1, 0) })
        }
        catalog.superStream(name, partitions)
    }

    private fun StreamListHarness.topLevel(): List<StreamRow> = state.rows.filter { it.kind != StreamRowKind.Partition }

    private fun StreamListHarness.expand(name: String) {
        vm.toggleExpanded(name)
        settle()
    }

    private fun StreamListHarness.offsetCalls(): List<String> = synchronized(offsets.calls) { offsets.calls.map { it.removePrefix("//") } }

    // --- one entry ---------------------------------------------------------------------------------

    @Test
    fun `a super stream is one entry with its partition count and its partitions are not top-level`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        s.open()

        assertEquals(listOf("audit-log", "orders"), s.names(), "partitions are not listed as separate entries")
        val row = s.state.rows.first { it.name == "orders" }
        assertEquals(StreamRowKind.SuperStream, row.kind)
        assertEquals("Super stream", row.type)
        assertEquals(StreamListTexts.SUPER_STREAM, row.type)
        assertEquals(3, row.partitionCount)
        assertFalse(row.expanded, "collapsed until the user expands it")
        assertNull(row.partitionOf)
        assertEquals(StreamRowKind.Stream, s.state.rows.first { it.name == "audit-log" }.kind)
        assertEquals(0, s.state.rows.first { it.name == "audit-log" }.partitionCount)
        assertEquals("1 to 2 of 2", s.state.rangeText)
        assertEquals(2, s.state.totalStreams)
        assertNull(s.state.groupingNotice)
    }

    @Test
    fun `a plain stream row has kind Stream, no partition of and is not expanded`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.open()

        val row = s.state.rows.single()
        assertEquals(StreamRowKind.Stream, row.kind)
        assertNull(row.partitionOf)
        assertFalse(row.expanded)
    }

    @Test
    fun `an expanded super stream shows its partitions right behind it in the broker's order, each carrying partition of`() = runTest {
        val s = StreamListHarness(this)
        s.superStream("regions", listOf("regions-us", "regions-eu"))
        s.open()
        s.expand("regions")

        assertEquals(listOf("regions", "regions-us", "regions-eu"), s.names(), "partition order is the broker's, not alphabetical")
        assertTrue(s.state.rows.first().expanded)
        val partitions = s.state.rows.drop(1)
        assertTrue(partitions.all { it.kind == StreamRowKind.Partition }, "kinds: ${partitions.map { it.kind }}")
        assertEquals(listOf("partition", "partition"), partitions.map { it.type })
        assertEquals(listOf("regions", "regions"), partitions.map { it.partitionOf })
        assertEquals(listOf(0, 0), partitions.map { it.partitionCount })
    }

    @Test
    fun `a partition row is an ordinary row with its own cells`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()
        s.expand("orders")

        val p = s.state.rows.first { it.name == "orders-1" }
        assertEquals(
            StreamRow(
                name = "orders-1", type = "partition", messages = "32,900", firstOffset = "0", lastOffset = "32899", publishers = "2",
                consumers = "2", retention = "7D", kind = StreamRowKind.Partition, partitionCount = 0, partitionOf = "orders", expanded = false,
            ),
            p,
        )
    }

    // --- aggregate cells ---------------------------------------------------------------------------

    @Test
    fun `the super stream row sums messages, publishers and consumers and shows no offsets and the common retention`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()

        val row = s.state.rows.single()
        assertEquals("96,100", row.messages)
        assertEquals("4", row.publishers)
        assertEquals("6", row.consumers)
        assertEquals("7D", row.retention)
        assertEquals(StreamListTexts.NOT_APPLICABLE, row.firstOffset)
        assertEquals(StreamListTexts.NOT_APPLICABLE, row.lastOffset)
        assertEquals("–", StreamListTexts.NOT_APPLICABLE, "a fixed dash, as in the mockup")
    }

    @Test
    fun `one partition without a count gives n_a for the super stream, never a partial total`() = runTest {
        val s = StreamListHarness(this)
        s.superStream("orders", orders, messages = listOf(31_200L, null, 32_000L))
        s.open()

        assertEquals("n/a", s.state.rows.single().messages)
        s.expand("orders")
        assertEquals(listOf("31,200", "n/a", "32,000"), s.state.rows.drop(1).map { it.messages }, "partitions keep their own cells")
        assertEquals("n/a", s.state.rows.first().messages)
    }

    @Test
    fun `publishers of a super stream are unavailable when the counts are not shown for the partitions`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.catalog.visibility = Visibility.OWN_CONNECTIONS_ONLY
        s.open()

        assertEquals("unavailable", s.state.rows.single().publishers)
        assertEquals("6", s.state.rows.single().consumers, "consumers do not depend on D8")
        assertEquals(0, s.count("publisherCounts"), "the broker is not asked (D8)")
    }

    @Test
    fun `publishers of a super stream count partitions without a publisher as zero`() = runTest {
        val s = StreamListHarness(this)
        s.superStream("orders", orders, publishers = listOf(0, 3, 0))
        s.open()

        assertEquals("3", s.state.rows.single().publishers)
    }

    @Test
    fun `retention reads mixed when the partitions differ and the common value when they agree`() = runTest {
        val s = StreamListHarness(this)
        s.superStream("same", listOf("same-0", "same-1"), retention = listOf(Retention(maxAge = "604800s"), Retention(maxAge = "604800s")))
        s.superStream("differs", listOf("differs-0", "differs-1"), retention = listOf(Retention(maxAge = "604800s"), Retention(maxAge = "86400s")))
        s.superStream("unset", listOf("unset-0", "unset-1"), retention = listOf(Retention(), Retention()))
        s.superStream("half", listOf("half-0", "half-1"), retention = listOf(Retention(maxAge = "604800s"), Retention()))
        s.open()

        val byName = s.state.rows.associate { it.name to it.retention }
        assertEquals("7D", byName["same"])
        assertEquals("mixed", byName["differs"])
        assertEquals(StreamListTexts.MIXED, byName["differs"])
        assertEquals("", byName["unset"], "no retention set anywhere is blank, as for a stream")
        assertEquals("mixed", byName["half"], "a set value next to an unset one differs")
    }

    @Test
    fun `no size member on any row, super stream or partition`() = runTest {
        val members = StreamRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(members.none { "size" in it || "bytes" in it }, "N1: members $members")
    }

    // --- expand ------------------------------------------------------------------------------------

    @Test
    fun `toggle expands and collapses, and an unknown or plain stream name does nothing`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        s.open()

        s.expand("orders")
        assertEquals(listOf("audit-log", "orders", "orders-0", "orders-1", "orders-2"), s.names())
        s.expand("orders")
        assertEquals(listOf("audit-log", "orders"), s.names())
        s.expand("audit-log")
        s.expand("no-such")
        s.expand("orders-1")
        assertEquals(listOf("audit-log", "orders"), s.names(), "only a super stream can expand")
        assertTrue(s.state.rows.none { it.expanded })
    }

    @Test
    fun `expanding calls no list endpoint, only the offsets of its partitions`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()
        val lists = s.count("listStreams")
        val grouping = s.count("superStreams")

        s.expand("orders")

        assertEquals(lists, s.count("listStreams"))
        assertEquals(grouping, s.count("superStreams"))
        assertEquals(orders.sorted(), s.offsetCalls().sorted(), "exactly the partitions, once")
        assertEquals(listOf("0", "0", "0"), s.state.rows.drop(1).map { it.firstOffset })
    }

    @Test
    fun `a partition still loading its first offset reads loading`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()
        val gate = s.offsets.hold("orders-1")
        s.expand("orders")

        assertEquals(StreamListTexts.LOADING, s.state.rows.first { it.name == "orders-1" }.firstOffset)
        assertEquals("0", s.state.rows.first { it.name == "orders-0" }.firstOffset)
        gate.release()
        s.settle()
        assertEquals("0", s.state.rows.first { it.name == "orders-1" }.firstOffset)
    }

    @Test
    fun `a refresh keeps a super stream expanded`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()
        s.expand("orders")

        s.vm.refreshNow()
        s.settle()
        s.pass(5)

        assertEquals(listOf("orders", "orders-0", "orders-1", "orders-2"), s.names())
        assertTrue(s.state.rows.first().expanded)
    }

    @Test
    fun `the expanded set is dropped when the connection changes, and on disconnect`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.prodCatalog.addStream(stream("orders-0"))
        s.prodCatalog.addStream(stream("orders-1"))
        s.prodCatalog.superStream("orders", listOf("orders-0", "orders-1"))
        s.prodOffsets.setFirst("/", "orders-0", 0)
        s.prodOffsets.setFirst("/", "orders-1", 0)
        s.open()
        s.expand("orders")
        assertEquals(4, s.state.rows.size)

        s.h.active.connect(s.h.prod)
        s.settle()
        assertEquals(listOf("orders"), s.names(), "the other connection's \"orders\" starts collapsed")
        assertFalse(s.state.rows.single().expanded)

        s.expand("orders")
        s.h.active.disconnect()
        s.settle()
        assertEquals(emptyList(), s.state.rows)
        s.connect()
        assertEquals(listOf("orders"), s.names(), "collapsed again after disconnect and connect")
    }

    // --- offsets only where visible ----------------------------------------------------------------

    @Test
    fun `a collapsed super stream costs no offset call, the plain streams do`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        s.open()

        assertEquals(listOf("audit-log"), s.offsetCalls())
    }

    @Test
    fun `offsets are asked for the partitions of an expanded super stream on the visible page only`() = runTest {
        val s = StreamListHarness(this, pageSize = 2)
        s.addStream("a-plain")
        s.addStream("b-plain")
        s.superStream("z-super", listOf("z-super-0", "z-super-1"))
        s.open()
        s.expand("z-super")
        assertEquals(listOf("a-plain", "b-plain"), s.offsetCalls().sorted(), "z-super is on page 2, expanded or not")

        s.vm.nextPage()
        s.settle()
        assertEquals(listOf("z-super", "z-super-0", "z-super-1"), s.names())
        assertEquals(listOf("a-plain", "b-plain", "z-super-0", "z-super-1"), s.offsetCalls().sorted())
    }

    // --- grouping rules ----------------------------------------------------------------------------

    @Test
    fun `a partition that is not among the loaded streams is skipped and the count is the partitions found`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("orders-0")
        s.addStream("orders-2")
        s.catalog.superStream("orders", orders)
        s.open()

        assertEquals(2, s.state.rows.single().partitionCount)
        s.expand("orders")
        assertEquals(listOf("orders", "orders-0", "orders-2"), s.names())
    }

    @Test
    fun `a partition bound to two super streams is shown under the first by name and nowhere else`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("shared")
        s.addStream("only-b")
        s.catalog.superStream("b-super", listOf("only-b", "shared"))
        s.catalog.superStream("a-super", listOf("shared"))
        s.open()
        s.expand("a-super")
        s.expand("b-super")

        assertEquals(listOf("a-super", "shared", "b-super", "only-b"), s.names())
        assertEquals(1, s.state.rows.count { it.name == "shared" }, "no duplicate row")
        assertEquals(1, s.state.rows.first { it.name == "b-super" }.partitionCount)
        assertEquals("a-super", s.state.rows.first { it.name == "shared" }.partitionOf)
    }

    @Test
    fun `the layout of another virtual host does not group these streams`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("orders-0")
        s.catalog.superStream("orders", listOf("orders-0"), vhost = "other-vhost")
        s.open()

        assertEquals(listOf("orders-0"), s.names())
        assertEquals(StreamRowKind.Stream, s.state.rows.single().kind)
    }

    // --- paging, filter, sort, kind ----------------------------------------------------------------

    @Test
    fun `a super stream counts as one entry in paging, expanded or not`() = runTest {
        val s = StreamListHarness(this, pageSize = 5)
        repeat(4) { s.addStream("p-$it") }
        s.superStream("q-super", listOf("q-super-0", "q-super-1", "q-super-2"))
        s.addStream("r-last")
        s.open()
        s.expand("q-super")

        assertEquals(5, s.topLevel().size, "a page of 5 holds 5 entries")
        assertEquals(8, s.state.rows.size, "plus the three partitions of the expanded super stream")
        assertEquals("1 to 5 of 6", s.state.rangeText)
        assertEquals(6, s.state.totalStreams)
        assertTrue(s.state.hasNextPage)
        s.vm.nextPage()
        s.settle()
        assertEquals(listOf("r-last"), s.names())
        assertEquals("6 to 6 of 6", s.state.rangeText)
    }

    @Test
    fun `11 plain streams and a super stream with page size 12 read 1 to 12 of 12`() = runTest {
        val s = StreamListHarness(this, pageSize = 12)
        s.streams(11)
        s.ordersSuperStream()
        s.open()

        assertEquals("1 to 12 of 12", s.state.rangeText)
        assertFalse(s.state.hasNextPage)
    }

    @Test
    fun `the partitions of an expanded super stream stay on its page, whatever the page size`() = runTest {
        val s = StreamListHarness(this, pageSize = 1)
        s.addStream("a-plain")
        s.ordersSuperStream()
        s.open()
        s.vm.nextPage()
        s.settle()
        s.expand("orders")

        assertEquals(listOf("orders", "orders-0", "orders-1", "orders-2"), s.names())
        assertEquals("2 to 2 of 2", s.state.rangeText)
    }

    @Test
    fun `the name filter finds a super stream by its own name or by a partition name, and does not expand it`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        s.open()

        s.vm.setFilter("orders-1")
        s.settle()
        assertEquals(listOf("orders"), s.names(), "found by a partition name, partitions not shown")
        assertFalse(s.state.rows.single().expanded)
        assertEquals(1, s.state.totalStreams)

        s.vm.setFilter("ORDERS")
        s.settle()
        assertEquals(listOf("orders"), s.names(), "by its own name, case-insensitive")

        s.vm.setFilter("audit")
        s.settle()
        assertEquals(listOf("audit-log"), s.names())

        s.vm.setFilter("nothing-like-this")
        s.settle()
        assertEquals(emptyList(), s.names())
        assertIs<StreamListScreen.Rows>(s.state.screen, "a filter matching nothing keeps the table")
    }

    @Test
    fun `the kind filter shows only streams, only super streams, or all, and starts at All`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        s.open()
        assertEquals(StreamKindFilter.All, s.state.kindFilter)

        s.vm.setKindFilter(StreamKindFilter.SuperStreams)
        s.settle()
        assertEquals(listOf("orders"), s.names())
        assertEquals(StreamKindFilter.SuperStreams, s.state.kindFilter)
        assertEquals(1, s.state.totalStreams)

        s.vm.setKindFilter(StreamKindFilter.Streams)
        s.settle()
        assertEquals(listOf("audit-log"), s.names(), "partitions are not streams at the top level")

        s.vm.setKindFilter(StreamKindFilter.All)
        s.settle()
        assertEquals(listOf("audit-log", "orders"), s.names())
    }

    @Test
    fun `the kind filter composes with the name filter and returns to page 1`() = runTest {
        val s = StreamListHarness(this, pageSize = 1)
        s.addStream("orders-audit")
        s.addStream("payments")
        s.ordersSuperStream()
        s.open()
        s.vm.nextPage()
        s.settle()
        assertEquals(2, s.state.page)

        s.vm.setKindFilter(StreamKindFilter.Streams)
        s.settle()
        assertEquals(1, s.state.page)
        s.vm.setFilter("orders")
        s.settle()
        assertEquals(listOf("orders-audit"), s.names(), "the super stream \"orders\" matches the name but is not a plain stream")

        s.vm.setKindFilter(StreamKindFilter.SuperStreams)
        s.settle()
        assertEquals(listOf("orders"), s.names())
        s.vm.setFilter("payments")
        s.settle()
        assertEquals(emptyList(), s.names())
    }

    @Test
    fun `sorting orders the entries by name and the partitions keep the broker's order`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a-plain")
        s.superStream("m-super", listOf("m-super-b", "m-super-a"))
        s.open()
        s.expand("m-super")
        s.vm.setSort(StreamSort.NameDescending)
        s.settle()

        assertEquals(listOf("m-super", "m-super-b", "m-super-a", "a-plain"), s.names())
    }

    // --- degraded grouping -------------------------------------------------------------------------

    @Test
    fun `the grouping is read once per load, after the stream pages and before the first offsets`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log")
        s.ordersSuperStream()
        val gate = s.catalog.holdNext("superStreams")
        s.open()

        assertEquals(emptyList(), s.offsetCalls(), "no offset is asked before the grouping is known")
        val calls = synchronized(s.catalog.calls) { s.catalog.calls.toList() }
        assertTrue(calls.indexOf("superStreams") > calls.lastIndexOf("listStreams"), "calls: $calls")
        gate.release()
        s.settle()
        assertEquals(1, s.count("superStreams"))
        assertTrue(s.offsetCalls().isNotEmpty())

        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.count("superStreams"), "one per full load")
    }

    @Test
    fun `when the grouping cannot be read the partitions are listed as separate streams and the list says so`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.catalog.failSuperStreams()
        s.open()

        assertEquals(orders, s.names())
        assertTrue(s.state.rows.all { it.kind == StreamRowKind.Stream && it.type == "stream" })
        assertEquals("Super stream grouping is unavailable. Partitions are listed as separate streams.", s.state.groupingNotice)
        assertEquals(StreamListTexts.GROUPING_UNAVAILABLE, s.state.groupingNotice)
        assertIs<StreamListScreen.Rows>(s.state.screen, "never a failed list")
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value, "not a lost connection")
        assertNull(s.state.banner)
        assertFalse(s.state.stale)
        assertEquals("1 to 3 of 3", s.state.rangeText)
    }

    @Test
    fun `an unexpected body from the grouping call degrades the same way`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.catalog.failSuperStreams(CatalogResult.Unexpected)
        s.open()

        assertEquals(orders, s.names())
        assertEquals(StreamListTexts.GROUPING_UNAVAILABLE, s.state.groupingNotice)
    }

    @Test
    fun `a grouping that fails after a good one keeps the last known grouping and shows no notice`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()
        s.expand("orders")

        s.catalog.failSuperStreams()
        s.pass(5)

        assertEquals(listOf("orders", "orders-0", "orders-1", "orders-2"), s.names(), "still grouped, still expanded")
        assertNull(s.state.groupingNotice, "the notice is for a grouping that was never read")
        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value)
    }

    @Test
    fun `the flat list regroups and the notice goes away when the grouping can be read again`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.catalog.failSuperStreams()
        s.open()
        assertEquals(StreamListTexts.GROUPING_UNAVAILABLE, s.state.groupingNotice)

        s.catalog.clearFailures()
        s.pass(5)

        assertEquals(listOf("orders"), s.names())
        assertNull(s.state.groupingNotice)
    }

    @Test
    fun `the last known grouping is dropped with the connection, so the other connection starts flat when its grouping fails`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.prodCatalog.addStream(stream("orders-0"))
        s.prodOffsets.setFirst("/", "orders-0", 0)
        s.prodCatalog.superStream("orders", listOf("orders-0"))
        s.prodCatalog.failSuperStreams()
        s.open()
        assertEquals(listOf("orders"), s.names())

        s.h.active.connect(s.h.prod)
        s.settle()

        assertEquals(listOf("orders-0"), s.names(), "the grouping of uat-eu must not be applied to prod-eu")
        assertEquals(StreamListTexts.GROUPING_UNAVAILABLE, s.state.groupingNotice)
    }

    @Test
    fun `no grouping call fails the stream load when the stream calls work, whatever the failure`() = runTest {
        for (failure in listOf(CatalogResult.NotAuthorised, CatalogResult.Unexpected, CatalogResult.PluginMissing, CatalogResult.NotFound)) {
            val s = StreamListHarness(this)
            s.ordersSuperStream()
            s.catalog.failSuperStreams(failure)
            s.open()
            assertIs<StreamListScreen.Rows>(s.state.screen, "failure $failure")
            assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value, "failure $failure")
            s.vm.close()
        }
    }

    @Test
    fun `a broker that is really gone is still a lost connection, driven by the stream calls`() = runTest {
        val s = StreamListHarness(this)
        s.ordersSuperStream()
        s.open()

        s.catalog.unreachable = true
        s.pass(5)

        assertEquals(LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertEquals(listOf("orders"), s.names(), "the last rows stay, grouped")
        assertTrue(s.state.stale)
    }
}
