package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperStreamPartitionBalanceTest {

    @Test
    fun `balanced partitions compute even share fractions and Balanced health`() {
        val group = StreamEntry.Group(
            name = "orders",
            vhost = "/",
            partitions = listOf(
                stream("orders-0", messages = 32_000L, consumers = 2),
                stream("orders-1", messages = 34_000L, consumers = 2),
                stream("orders-2", messages = 34_000L, consumers = 2),
            ),
        )
        val balances = computePartitionBalances(listOf(group), expandedNames = setOf("orders"))

        assertEquals(3, balances.size)
        val p0 = assertNotNull(balances["orders-0"])
        assertEquals("orders", p0.superStreamName)
        assertEquals(0, p0.partitionIndex)
        assertEquals(3, p0.partitionCount)
        assertEquals(32_000L, p0.messages)
        assertEquals(100_000L, p0.totalSuperStreamMessages)
        assertEquals(32, p0.sharePercent)
        assertEquals("32%", p0.shareLabel)
        assertFalse(p0.isSkewedHot)
        assertFalse(p0.isOrphaned)
        assertEquals(PartitionHealth.Balanced, p0.health)
    }

    @Test
    fun `hot partition skew is detected when one partition holds disproportionate share`() {
        val group = StreamEntry.Group(
            name = "orders",
            vhost = "/",
            partitions = listOf(
                stream("orders-0", messages = 85_000L, consumers = 2),
                stream("orders-1", messages = 8_000L, consumers = 2),
                stream("orders-2", messages = 7_000L, consumers = 2),
            ),
        )
        val balances = computePartitionBalances(listOf(group), expandedNames = setOf("orders"))

        val hot = assertNotNull(balances["orders-0"])
        assertTrue(hot.isSkewedHot)
        assertFalse(hot.isOrphaned)
        assertEquals(85, hot.sharePercent)
        assertEquals("Hot \u00b7 85%", hot.shareLabel)
        assertEquals(PartitionHealth.SkewedHot, hot.health)

        val cold = assertNotNull(balances["orders-1"])
        assertFalse(cold.isSkewedHot)
        assertEquals("8%", cold.shareLabel)
        assertEquals(PartitionHealth.Balanced, cold.health)
    }

    @Test
    fun `orphaned partition is detected when consumers is 0 while a sibling partition has active consumers`() {
        val group = StreamEntry.Group(
            name = "orders",
            vhost = "/",
            partitions = listOf(
                stream("orders-0", messages = 33_000L, consumers = 1),
                stream("orders-1", messages = 33_000L, consumers = 1),
                stream("orders-2", messages = 34_000L, consumers = 0),
            ),
        )
        val balances = computePartitionBalances(listOf(group), expandedNames = setOf("orders"))

        val p0 = assertNotNull(balances["orders-0"])
        assertFalse(p0.isOrphaned)
        assertEquals(PartitionHealth.Balanced, p0.health)

        val orphaned = assertNotNull(balances["orders-2"])
        assertTrue(orphaned.isOrphaned)
        assertEquals(PartitionHealth.OrphanedNoConsumers, orphaned.health)
    }

    @Test
    fun `when no partition has consumers none are marked orphaned`() {
        val group = StreamEntry.Group(
            name = "orders",
            vhost = "/",
            partitions = listOf(
                stream("orders-0", messages = 100L, consumers = 0),
                stream("orders-1", messages = 100L, consumers = 0),
            ),
        )
        val balances = computePartitionBalances(listOf(group), expandedNames = setOf("orders"))

        assertFalse(assertNotNull(balances["orders-0"]).isOrphaned)
        assertFalse(assertNotNull(balances["orders-1"]).isOrphaned)
    }

    @Test
    fun `collapsed super streams do not emit partition balances`() {
        val group = StreamEntry.Group(
            name = "orders",
            vhost = "/",
            partitions = listOf(
                stream("orders-0", messages = 100L, consumers = 1),
                stream("orders-1", messages = 100L, consumers = 1),
            ),
        )
        val balances = computePartitionBalances(listOf(group), expandedNames = emptySet())
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `StreamListViewModel populates partitionBalances when super stream is expanded`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.addStream(stream("orders-0", messages = 80_000L, consumers = 2))
        s.catalog.addStream(stream("orders-1", messages = 10_000L, consumers = 2))
        s.catalog.addStream(stream("orders-2", messages = 10_000L, consumers = 0))
        s.offsets.setFirst("/", "orders-0", 0)
        s.offsets.setFirst("/", "orders-1", 0)
        s.offsets.setFirst("/", "orders-2", 0)
        s.catalog.superStream("orders", listOf("orders-0", "orders-1", "orders-2"))
        s.open()

        assertTrue(s.state.partitionBalances.isEmpty(), "collapsed super stream has no partition balances in state")

        s.vm.toggleExpanded("orders")
        s.settle()

        assertEquals(3, s.state.partitionBalances.size)
        val p0 = assertNotNull(s.state.partitionBalances["orders-0"])
        assertTrue(p0.isSkewedHot)
        assertEquals("Hot \u00b7 80%", p0.shareLabel)

        val p2 = assertNotNull(s.state.partitionBalances["orders-2"])
        assertTrue(p2.isOrphaned)
        assertEquals(PartitionHealth.OrphanedNoConsumers, p2.health)
    }
}
