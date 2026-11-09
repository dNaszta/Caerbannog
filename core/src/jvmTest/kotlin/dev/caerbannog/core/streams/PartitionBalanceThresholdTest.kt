package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M7-07 edge cases of the partition balance rules (threshold = max(1.5 x even share, 50 percent), orphan wins over hot). */
class PartitionBalanceThresholdTest {

    private fun balances(vararg partitions: Pair<Long?, Int>): Map<String, PartitionBalanceInfo> {
        val group = StreamEntry.Group(
            name = "ss", vhost = "/",
            partitions = partitions.mapIndexed { i, (messages, consumers) -> stream("ss-$i", messages = messages, consumers = consumers) },
        )
        return computePartitionBalances(listOf(group), expandedNames = setOf("ss"))
    }

    private fun Map<String, PartitionBalanceInfo>.hot(i: Int) = assertNotNull(this["ss-$i"]).isSkewedHot

    @Test
    fun `two partitions need more than 75 percent to be hot`() {
        assertFalse(balances(70L to 1, 30L to 1).hot(0), "70 percent")
        assertFalse(balances(75L to 1, 25L to 1).hot(0), "exactly 75 percent is not above the threshold")
        assertTrue(balances(80L to 1, 20L to 1).hot(0), "80 percent")
    }

    @Test
    fun `with four or more partitions the 50 percent floor applies, not 1,5 times the even share`() {
        assertFalse(balances(45L to 1, 20L to 1, 20L to 1, 15L to 1).hot(0), "45 percent is above 1,5 x 25 but below the floor")
        assertTrue(balances(55L to 1, 15L to 1, 15L to 1, 15L to 1).hot(0), "55 percent")
    }

    @Test
    fun `an unknown message count anywhere means no share and no hot flag for any partition`() {
        val b = balances(80L to 1, null to 1, 20L to 1)
        for (info in b.values) {
            assertEquals(0, info.sharePercent)
            assertEquals(StreamListTexts.NOT_AVAILABLE, info.shareLabel)
            assertFalse(info.isSkewedHot)
            assertEquals(null, info.totalSuperStreamMessages)
        }
    }

    @Test
    fun `a super stream with no messages shows the even share and nothing is hot`() {
        val b = balances(0L to 1, 0L to 1, 0L to 1, 0L to 1)
        assertTrue(b.values.all { it.sharePercent == 25 && !it.isSkewedHot })
    }

    @Test
    fun `a partition that is both hot and without consumers is reported as orphaned, the more urgent state`() {
        val b = balances(90L to 0, 5L to 2, 5L to 2)
        val p0 = assertNotNull(b["ss-0"])
        assertTrue(p0.isSkewedHot && p0.isOrphaned)
        assertEquals(PartitionHealth.OrphanedNoConsumers, p0.health)
    }

    @Test
    fun `a single partition is never hot or orphaned`() {
        val only = assertNotNull(balances(100L to 0)["ss-0"])
        assertFalse(only.isSkewedHot)
        assertFalse(only.isOrphaned)
        assertEquals(PartitionHealth.Balanced, only.health)
    }
}
