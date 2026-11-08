package dev.caerbannog.core.streams

import kotlin.math.roundToInt

/**
 * Operational health classification of a partition inside an expanded Super Stream (M7-07).
 */
enum class PartitionHealth {
    Balanced,
    SkewedHot,
    OrphanedNoConsumers,
}

/**
 * Per-partition distribution and consumer health metrics for an expanded Super Stream partition row.
 *
 * Detects the two classic Super Stream operational issues:
 * 1. **Partition Key Skew (Hot Partition):** One partition holds a disproportionate share of the super stream's messages
 *    (`shareFraction > (1f / partitionCount) * 1.5f` when `partitionCount >= 2` and `totalMessages > 0`).
 * 2. **Orphaned Partition (No Consumer):** A partition has `0 consumers` while at least one sibling partition in the same
 *    super stream has active consumers (`consumers > 0`).
 */
data class PartitionBalanceInfo(
    val partitionName: String,
    val superStreamName: String,
    val partitionIndex: Int,
    val partitionCount: Int,
    val messages: Long?,
    val totalSuperStreamMessages: Long?,
    val shareFraction: Float,
    val sharePercent: Int,
    val shareLabel: String,
    val consumers: Int,
    val isSkewedHot: Boolean,
    val isOrphaned: Boolean,
    val health: PartitionHealth,
)

/**
 * Computes [PartitionBalanceInfo] keyed by partition stream name for all expanded [StreamEntry.Group] entries in [visibleEntries].
 */
internal fun computePartitionBalances(
    visibleEntries: List<StreamEntry>,
    expandedNames: Set<String>,
): Map<String, PartitionBalanceInfo> {
    val result = LinkedHashMap<String, PartitionBalanceInfo>()
    for (entry in visibleEntries) {
        if (entry !is StreamEntry.Group || entry.name !in expandedNames) continue
        val partitions = entry.partitions
        val count = partitions.size
        if (count == 0) continue

        val allCountsKnown = partitions.all { it.messages != null }
        val totalMessages: Long? = if (allCountsKnown) partitions.sumOf { it.messages ?: 0L } else null
        val anySiblingHasConsumers = partitions.any { it.consumers > 0 }
        val uniformShare = 1f / count.toFloat()
        // A partition is considered hot/skewed when there are at least 2 partitions, total messages > 0,
        // and its message share exceeds 1.5x the uniform share (e.g. >50% in a 3-partition super stream, >37.5% in 4-partition, >75% in 2-partition).
        val hotThreshold = (uniformShare * 1.5f).coerceAtLeast(0.50f)

        partitions.forEachIndexed { idx, summary ->
            val msgCount = summary.messages
            val shareFraction = when {
                totalMessages == null || msgCount == null -> 0f
                totalMessages <= 0L -> uniformShare
                else -> (msgCount.toDouble() / totalMessages.toDouble()).toFloat().coerceIn(0f, 1f)
            }
            val sharePercent = when {
                totalMessages == null || msgCount == null -> 0
                totalMessages <= 0L -> (uniformShare * 100f).roundToInt()
                else -> ((msgCount.toDouble() / totalMessages.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            }
            val isSkewedHot = count >= 2 &&
                totalMessages != null &&
                totalMessages > 0L &&
                shareFraction > hotThreshold
            val isOrphaned = count >= 2 &&
                summary.consumers == 0 &&
                anySiblingHasConsumers

            val health = when {
                isOrphaned -> PartitionHealth.OrphanedNoConsumers
                isSkewedHot -> PartitionHealth.SkewedHot
                else -> PartitionHealth.Balanced
            }
            val shareLabel = when {
                totalMessages == null || msgCount == null -> StreamListTexts.NOT_AVAILABLE
                isSkewedHot -> "Hot \u00b7 $sharePercent%"
                else -> "$sharePercent%"
            }

            result[summary.name] = PartitionBalanceInfo(
                partitionName = summary.name,
                superStreamName = entry.name,
                partitionIndex = idx,
                partitionCount = count,
                messages = msgCount,
                totalSuperStreamMessages = totalMessages,
                shareFraction = shareFraction,
                sharePercent = sharePercent,
                shareLabel = shareLabel,
                consumers = summary.consumers,
                isSkewedHot = isSkewedHot,
                isOrphaned = isOrphaned,
                health = health,
            )
        }
    }
    return result
}
