package dev.caerbannog.core.streams

import kotlin.math.roundToLong

/** Direction of change between the two most recent refresh ticks. */
enum class TrendDirection {
    Growing,
    Draining,
    Steady,
}

/** Semantic meaning of the metric: throughput velocity vs consumer backlog lag. */
enum class SparklineSemantic {
    Velocity,
    ConsumerLag,
}

/**
 * Pure domain model for a zero-cost rolling sparkline and trend indicator computed across refresh ticks.
 *
 * @property samples Raw metric values ordered chronologically (oldest to newest, at least 2 points).
 * @property normalizedPoints Values normalized to `0f..1f` for direct Canvas rendering (`0.5f` when all samples are equal).
 * @property deltaPerTick Difference between the latest sample and the immediately preceding sample.
 * @property deltaPerSecond Estimated rate per second between the last two samples, or null if timestamps are identical.
 * @property direction Whether the metric is growing, draining, or steady on the latest tick.
 * @property label Human-readable compact badge text (e.g. `"+45/s"`, `"↘ Draining (-120)"`, `"↗ Growing (+85)"`).
 * @property semantic Whether up/growing is normal throughput ([SparklineSemantic.Velocity]) or rising backlog ([SparklineSemantic.ConsumerLag]).
 */
data class MetricSparkline(
    val samples: List<Long>,
    val normalizedPoints: List<Float>,
    val deltaPerTick: Long,
    val deltaPerSecond: Long?,
    val direction: TrendDirection,
    val label: String,
    val semantic: SparklineSemantic,
)

/**
 * Bounded in-memory ring buffer of metric samples per entity key (stream name, publisher key, or consumer key).
 * Requires zero extra broker calls by piggybacking on existing refresh loop ticks.
 */
class RollingMetricHistory(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    private data class Sample(val value: Long, val timestampMillis: Long)

    private val history = HashMap<String, ArrayDeque<Sample>>()

    fun record(key: String, value: Long?, timestampMillis: Long) {
        if (value == null) return
        val deque = history.getOrPut(key) { ArrayDeque(maxSamples) }
        if (deque.size >= maxSamples) {
            deque.removeFirst()
        }
        deque.addLast(Sample(value = value, timestampMillis = timestampMillis))
    }

    fun retainKeys(activeKeys: Set<String>) {
        val it = history.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in activeKeys) {
                it.remove()
            }
        }
    }

    fun clear() {
        history.clear()
    }

    fun snapshot(keys: Iterable<String>, semantic: SparklineSemantic): Map<String, MetricSparkline> {
        val result = LinkedHashMap<String, MetricSparkline>()
        for (key in keys) {
            val deque = history[key] ?: continue
            if (deque.size < 2) continue
            val values = deque.map { it.value }
            val min = values.minOrNull() ?: 0L
            val max = values.maxOrNull() ?: 0L
            val span = max - min
            val normalized = if (span <= 0L) {
                List(values.size) { 0.5f }
            } else {
                values.map { ((it - min).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f) }
            }
            val prev = deque[deque.size - 2]
            val last = deque.last()
            val delta = last.value - prev.value
            val dtMillis = last.timestampMillis - prev.timestampMillis
            val perSec = if (dtMillis > 0L) {
                ((delta * 1000.0) / dtMillis.toDouble()).roundToLong()
            } else {
                null
            }
            val direction = when {
                delta > 0L -> TrendDirection.Growing
                delta < 0L -> TrendDirection.Draining
                else -> TrendDirection.Steady
            }
            val label = formatLabel(delta, perSec, direction, semantic)
            result[key] = MetricSparkline(
                samples = values,
                normalizedPoints = normalized,
                deltaPerTick = delta,
                deltaPerSecond = perSec,
                direction = direction,
                label = label,
                semantic = semantic,
            )
        }
        return result
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES: Int = 16

        fun formatLabel(
            deltaPerTick: Long,
            deltaPerSecond: Long?,
            direction: TrendDirection,
            semantic: SparklineSemantic,
        ): String = when (semantic) {
            SparklineSemantic.Velocity -> when (direction) {
                TrendDirection.Growing -> {
                    if (deltaPerSecond != null && deltaPerSecond > 0L) {
                        "+${groupDigits(deltaPerSecond)}/s"
                    } else {
                        "+${groupDigits(deltaPerTick)}"
                    }
                }
                TrendDirection.Draining -> groupDigits(deltaPerTick)
                TrendDirection.Steady -> "0/s"
            }
            SparklineSemantic.ConsumerLag -> when (direction) {
                TrendDirection.Draining -> "↘ Draining (${groupDigits(deltaPerTick)})"
                TrendDirection.Growing -> "↗ Growing (+${groupDigits(deltaPerTick)})"
                TrendDirection.Steady -> "→ Steady"
            }
        }
    }
}
