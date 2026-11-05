package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-08b step 2: the Queues tab: the rows and their cells, the Quorum / Other types chips, search by name, in-memory paging (R1).
 * Scenarios: "List quorum queues with statistics", "A queue with a member offline is flagged", "Other queue types are not the focus",
 * "Search queues by name", "Streams are not listed as queues", "Long queue lists are paged", "Another vhost's queues are not listed",
 * "Queue counts are labelled as approximate". Contract: [TopologyHarness].
 */
class TopologyViewModelQueuesTest {

    private val three = listOf("rabbit@node-1", "rabbit@node-2", "rabbit@node-3")

    // --- rows ---

    @Test
    fun `a quorum queue row has its counts with thousands separators, leader and members online`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("orders", ready = 1240, unacked = 12, consumers = 3, members = three, leader = "rabbit@node-1"))
        s.topology.addQueue(quorum("notifications", ready = 86, unacked = 4, consumers = 1, members = three, online = three.drop(1), leader = "rabbit@node-3"))
        s.open()

        assertEquals(
            listOf(
                QueueRowView("notifications", "quorum", "86", "4", "1", "rabbit@node-3", "2 of 3", memberOffline = true),
                QueueRowView("orders", "quorum", "1,240", "12", "3", "rabbit@node-1", "3 of 3", memberOffline = false),
            ),
            s.state.queues,
        )
    }

    @Test
    fun `a count the broker did not send reads n slash a, never 0`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("fresh", ready = null, unacked = null, consumers = null))
        s.open()
        val row = s.state.queues.single()
        assertEquals(listOf("n/a", "n/a", "n/a"), listOf(row.ready, row.unacked, row.consumers))
        assertEquals("n/a", TopologyTexts.NOT_AVAILABLE)
    }

    @Test
    fun `a classic queue has dashes for leader and members, the counts where the broker returns them`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(classic("legacy", ready = 7, unacked = 1, consumers = 2))
        s.open()
        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.settle()
        assertEquals(QueueRowView("legacy", "classic", "7", "1", "2", "–", "–", memberOffline = false), s.state.queues.single())
    }

    @Test
    fun `a queue with a member offline is flagged, one with all members online is not`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("notifications", members = three, online = three.take(2)))
        s.topology.addQueue(quorum("orders", members = three))
        s.open()
        assertEquals(mapOf("notifications" to true, "orders" to false), s.state.queues.associate { it.name to it.memberOffline })
    }

    @Test
    fun `a queue with no leader reads a dash`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("headless", leader = null, members = three, online = emptyList()))
        s.open()
        assertEquals("–", s.state.queues.single().leader)
        assertEquals("0 of 3", s.state.queues.single().membersOnline)
    }

    @Test
    fun `queue counts are labelled approximate`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("orders", ready = 1240))
        s.open()
        assertTrue(s.state.approximateNote.contains("approximate"), s.state.approximateNote)
    }

    @Test
    fun `rows come name ascending`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        listOf("m", "b", "z", "a").forEach { s.topology.addQueue(quorum(it)) }
        s.open()
        assertEquals(listOf("a", "b", "m", "z"), s.names())
    }

    // --- chips ---

    @Test
    fun `the Quorum chip lists only quorum queues, Other types the classic ones, and a stream is in neither`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("orders"))
        s.topology.addQueue(classic("legacy"))
        s.topology.addStream("audit-log")
        s.open()
        assertEquals(listOf("orders"), s.names())

        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.settle()
        assertEquals(listOf("legacy"), s.names(), "Streams are not listed as queues")
        assertEquals(QueueTypeFilter.OtherTypes, s.state.queueFilter)

        s.vm.setQueueFilter(QueueTypeFilter.Quorum)
        s.settle()
        assertEquals(listOf("orders"), s.names())
    }

    @Test
    fun `Other types lists every queue that is not quorum and not a stream, whatever its type text`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("q"))
        s.topology.addQueue(classic("c"))
        s.topology.addQueue(classic("m").copy(type = "mqtt"))
        s.open()
        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.settle()
        assertEquals(listOf("c", "m"), s.names())
    }

    @Test
    fun `changing the chip does not call the broker for the queues again, and goes back to page 1`() = runTest {
        val s = TopologyHarness(this, pageSize = 2)
        repeat(5) { s.topology.addQueue(quorum("q-$it")) }
        s.topology.addQueue(classic("c-1"))
        s.open()
        s.vm.nextPage()
        s.settle()
        val before = s.count("queues")

        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.settle()

        assertEquals(before, s.count("queues"), "the filter is in memory")
        assertEquals(1, s.state.page)
    }

    // --- search ---

    @Test
    fun `search by name lists the names that contain the text`() = runTest {
        val s = TopologyHarness(this)
        listOf("orders", "invoices", "legacy-orders").forEach { s.topology.addQueue(quorum(it)) }
        s.open()
        s.vm.setSearch("orders")
        s.settle()
        assertEquals(listOf("legacy-orders", "orders"), s.names())
        assertEquals("orders", s.state.search)
    }

    @Test
    fun `search ignores case like the stream list`() = runTest {
        val s = TopologyHarness(this)
        listOf("Orders", "invoices").forEach { s.topology.addQueue(quorum(it)) }
        s.open()
        s.vm.setSearch("ORDERS")
        s.settle()
        assertEquals(listOf("Orders"), s.names())
    }

    @Test
    fun `a search is a substring, not a pattern, and takes special characters literally`() = runTest {
        val s = TopologyHarness(this)
        listOf("a.b", "axb", "c(d").forEach { s.topology.addQueue(quorum(it)) }
        s.open()
        s.vm.setSearch("a.b")
        s.settle()
        assertEquals(listOf("a.b"), s.names())
        s.vm.setSearch("(")
        s.settle()
        assertEquals(listOf("c(d"), s.names())
    }

    @Test
    fun `search composes with the chip, never asks the broker, and goes back to page 1`() = runTest {
        val s = TopologyHarness(this, pageSize = 2)
        listOf("orders-1", "orders-2", "orders-3").forEach { s.topology.addQueue(quorum(it)) }
        s.topology.addQueue(classic("orders-legacy"))
        s.open()
        s.vm.nextPage()
        s.settle()
        val before = s.count("queues")

        s.vm.setSearch("orders")
        s.settle()
        assertEquals(1, s.state.page)
        assertEquals(listOf("orders-1", "orders-2"), s.names())
        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.settle()
        assertEquals(listOf("orders-legacy"), s.names())
        assertEquals(before, s.count("queues"))
    }

    // --- paging in memory, R1 ---

    @Test
    fun `12 quorum queues on pages of 5 are 1 to 5, 6 to 10, 11 to 12 of 12`() = runTest {
        val s = TopologyHarness(this, pageSize = 5)
        s.quorumQueues(12)
        s.open()
        assertEquals("1 to 5 of 12", s.state.rangeText)
        assertTrue(s.state.hasNextPage)
        assertFalse(s.state.hasPreviousPage)
        assertEquals(12, s.state.total)

        s.vm.nextPage()
        s.settle()
        assertEquals("6 to 10 of 12", s.state.rangeText)
        assertEquals(listOf("q-05", "q-06", "q-07", "q-08", "q-09"), s.names())
        assertTrue(s.state.hasPreviousPage)

        s.vm.nextPage()
        s.settle()
        assertEquals("11 to 12 of 12", s.state.rangeText)
        assertFalse(s.state.hasNextPage)
        assertEquals(3, s.state.page)
    }

    @Test
    fun `a page outside the range is clamped, page size 1 and a size larger than the list work`() = runTest {
        val s = TopologyHarness(this, pageSize = 5)
        s.quorumQueues(12)
        s.open()
        s.vm.setPage(99)
        s.settle()
        assertEquals(3, s.state.page)
        s.vm.setPage(-4)
        s.settle()
        assertEquals(1, s.state.page)
        s.vm.setPageSize(1)
        s.settle()
        assertEquals("1 to 1 of 12", s.state.rangeText)
        s.vm.setPageSize(0)
        s.settle()
        assertEquals(1, s.state.pageSize, "a size below 1 becomes 1")
        s.vm.setPageSize(500)
        s.settle()
        assertEquals("1 to 12 of 12", s.state.rangeText)
        assertFalse(s.state.hasNextPage)
    }

    @Test
    fun `paging is in memory, the broker is not asked again for a page`() = runTest {
        val s = TopologyHarness(this, pageSize = 5)
        s.quorumQueues(12)
        s.open()
        val before = s.count("queues")
        s.vm.nextPage()
        s.vm.setPageSize(3)
        s.vm.previousPage()
        s.settle()
        assertEquals(before, s.count("queues"))
    }

    @Test
    fun `R1 a vhost with 3 queues and 700 streams says 1 to 3 of 3, the brokers total is not shown`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        repeat(700) { s.topology.addStream("a-stream-%03d".format(it)) }
        s.quorumQueues(3, prefix = "z-")
        s.open()
        assertEquals(listOf("z-00", "z-01", "z-02"), s.names())
        assertEquals("1 to 3 of 3", s.state.rangeText)
        assertEquals(3, s.state.total)
    }

    @Test
    fun `every broker page is read, 1200 queues are 3 calls and the rows are all there`() = runTest {
        val s = TopologyHarness(this, pageSize = 500)
        s.quorumQueues(1200)
        s.open()
        assertEquals(
            listOf("queues /|1", "queues /|2", "queues /|3"),
            s.requests().filter { it.startsWith("queues") },
        )
        assertEquals(1200, s.state.total)
        assertEquals("1 to 500 of 1200", s.state.rangeText)
        s.vm.setPage(3)
        s.settle()
        assertEquals("1001 to 1200 of 1200", s.state.rangeText)
    }

    @Test
    fun `pages made of streams only are read through and the queues after them are found`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        repeat(600) { s.topology.addStream("a-%03d".format(it)) }
        s.topology.addQueue(quorum("z-last"))
        s.open()
        assertEquals(listOf("z-last"), s.names())
    }

    @Test
    fun `the page is clamped when a refresh brings fewer rows`() = runTest {
        val s = TopologyHarness(this, pageSize = 5)
        s.quorumQueues(12)
        s.open()
        s.vm.setPage(3)
        s.settle()
        repeat(8) { s.topology.deleteQueue("q-%02d".format(it + 4)) }
        s.pass(5)
        assertEquals(1, s.state.page)
        assertEquals("1 to 4 of 4", s.state.rangeText)
    }

    // --- the vhost scope of the rows ---

    @Test
    fun `another vhost's queues are not listed and not counted`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        s.quorumQueues(3)
        s.topology.addQueue(quorum("other-1", vhost = "billing"))
        s.topology.addQueue(quorum("other-2", vhost = "billing"))
        s.open()
        assertEquals(listOf("q-00", "q-01", "q-02"), s.names())
        assertEquals("1 to 3 of 3", s.state.rangeText)
    }
}
