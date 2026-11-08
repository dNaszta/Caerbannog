package dev.caerbannog.core.browser

import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.groupDigits
import kotlin.math.roundToLong

enum class ConsumerPinHealth {
    /** Consumer is at the stream head (`offsetLag <= 0` or `offset >= lastOffset`). */
    CaughtUp,

    /** Consumer is behind the head, within safe active window. */
    Lagging,

    /** Stream has retention truncation active (`firstOffset > 0`) and consumer is within the first 10% cliff zone. */
    AtRisk,

    /** Single-active-consumer standby (`NotActive`). */
    Inactive,
}

data class ConsumerPin(
    val key: String,
    val name: String,
    val offset: Long,
    /** Normalized position `0f..1f` within the active stream window `[firstOffset..lastOffset]`. */
    val fraction: Float,
    val health: ConsumerPinHealth,
    val statusText: String,
    val label: String,
)

data class PageViewportSlice(
    val firstOffset: Long,
    val lastOffset: Long,
    /** Normalized start `0f..1f` within the active stream window. */
    val startFraction: Float,
    /** Normalized end `0f..1f` within the active stream window. */
    val endFraction: Float,
)

data class SelectedMessagePin(
    val offset: Long,
    /** Normalized position `0f..1f` within the active stream window. */
    val fraction: Float,
)

/**
 * Pure model for the visual Stream Log Tape diagram.
 * Maps the stream's `[0 .. firstOffset - 1]` truncated history, `[firstOffset .. lastOffset]` active retention window,
 * retention cliff danger zone, current Message Browser page slice, selected message cursor, and active consumer pins.
 */
data class StreamLogTape(
    val firstOffset: Long,
    val lastOffset: Long,
    val totalActiveMessages: Long,
    val truncatedCount: Long,
    /** Fraction of the horizontal bar reserved for the truncated prefix (`0f` when `firstOffset == 0`). */
    val truncatedFraction: Float,
    /** End of the retention danger zone (first 10% of active window when `firstOffset > 0`), or null. */
    val dangerZoneEndOffset: Long?,
    /** Fraction of the active window covered by the retention danger zone (`0.10f` when active, else `0f`). */
    val dangerZoneActiveFraction: Float,
    val pageSlice: PageViewportSlice?,
    val selectedPin: SelectedMessagePin?,
    val consumerPins: List<ConsumerPin>,
) {
    val rangeSummaryText: String =
        "Offsets ${groupDigits(firstOffset)} .. ${groupDigits(lastOffset)} (${groupDigits(totalActiveMessages)} msgs)"

    val truncatedBadgeText: String? =
        if (truncatedCount > 0L) "Truncated: 0 .. ${groupDigits(firstOffset - 1)}" else null

    val cliffBadgeText: String? =
        dangerZoneEndOffset?.let { "Retention cliff <= ${groupDigits(it)}" }

    val leftAxisText: String =
        if (truncatedCount > 0L) {
            "0 (expired) | First: ${groupDigits(firstOffset)}"
        } else {
            "First: ${groupDigits(firstOffset)}"
        }

    val rightAxisText: String = "Head: ${groupDigits(lastOffset)}"

    val viewingSliceText: String? =
        pageSlice?.let { "Viewing ${groupDigits(it.firstOffset)}..${groupDigits(it.lastOffset)} (click tape to jump)" }

    /**
     * Converts a horizontal click position `totalFraction` (`0f..1f` across the entire canvas width,
     * including any left-hand `truncatedFraction`) into a valid stream offset in `firstOffset..lastOffset`.
     */
    fun offsetAtTotalFraction(totalFraction: Float): Long {
        val clampedTotal = totalFraction.coerceIn(0f, 1f)
        if (clampedTotal <= truncatedFraction) return firstOffset
        val activeWidth = (1f - truncatedFraction).coerceAtLeast(0.0001f)
        val activeFraction = ((clampedTotal - truncatedFraction) / activeWidth).coerceIn(0f, 1f)
        return offsetAtActiveFraction(activeFraction)
    }

    /**
     * Converts a normalized position `activeFraction` (`0f..1f` within the active window) into a stream offset.
     */
    fun offsetAtActiveFraction(activeFraction: Float): Long {
        val clamped = activeFraction.coerceIn(0f, 1f)
        val span = (lastOffset - firstOffset).coerceAtLeast(0L)
        return (firstOffset + (span.toDouble() * clamped.toDouble()).roundToLong()).coerceIn(firstOffset, lastOffset)
    }

    companion object {
        private const val TRUNCATED_VISUAL_FRACTION = 0.14f
        private const val DANGER_ZONE_FRACTION = 0.10f
        private const val MIN_SPAN_FOR_DANGER_ZONE = 10L

        fun from(
            firstOffset: Long?,
            lastOffset: Long?,
            pageFirstOffset: Long? = null,
            pageLastOffset: Long? = null,
            selectedOffset: Long? = null,
            consumers: List<ConsumerRowState> = emptyList(),
        ): StreamLogTape? {
            if (firstOffset == null || lastOffset == null || lastOffset < firstOffset) return null

            val span = (lastOffset - firstOffset).coerceAtLeast(1L)
            val totalActive = lastOffset - firstOffset + 1L
            val truncatedCount = firstOffset.coerceAtLeast(0L)
            val truncatedFraction = if (truncatedCount > 0L) TRUNCATED_VISUAL_FRACTION else 0f

            val hasDangerZone = truncatedCount > 0L && (lastOffset - firstOffset) >= MIN_SPAN_FOR_DANGER_ZONE
            val dangerEndOffset = if (hasDangerZone) {
                firstOffset + ((lastOffset - firstOffset) * DANGER_ZONE_FRACTION).roundToLong()
            } else {
                null
            }
            val dangerActiveFraction = if (hasDangerZone) DANGER_ZONE_FRACTION else 0f

            fun toFraction(offset: Long): Float =
                ((offset - firstOffset).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)

            val pageSlice = if (pageFirstOffset != null && pageLastOffset != null && pageLastOffset >= pageFirstOffset) {
                PageViewportSlice(
                    firstOffset = pageFirstOffset,
                    lastOffset = pageLastOffset,
                    startFraction = toFraction(pageFirstOffset),
                    endFraction = toFraction(pageLastOffset),
                )
            } else {
                null
            }

            val selectedPin = selectedOffset?.let { off ->
                SelectedMessagePin(
                    offset = off,
                    fraction = toFraction(off),
                )
            }

            val pins = consumers.mapNotNull { c ->
                val off = c.browseFromOffset ?: return@mapNotNull null
                val frac = toFraction(off)
                val health = when {
                    c.status is ConsumerStatus.NotActive -> ConsumerPinHealth.Inactive
                    c.status is ConsumerStatus.CaughtUp || off >= lastOffset -> ConsumerPinHealth.CaughtUp
                    dangerEndOffset != null && off <= dangerEndOffset -> ConsumerPinHealth.AtRisk
                    else -> ConsumerPinHealth.Lagging
                }
                ConsumerPin(
                    key = c.key,
                    name = c.name,
                    offset = off,
                    fraction = frac,
                    health = health,
                    statusText = c.status.text,
                    label = "${c.name} @ ${groupDigits(off)}",
                )
            }

            return StreamLogTape(
                firstOffset = firstOffset,
                lastOffset = lastOffset,
                totalActiveMessages = totalActive,
                truncatedCount = truncatedCount,
                truncatedFraction = truncatedFraction,
                dangerZoneEndOffset = dangerEndOffset,
                dangerZoneActiveFraction = dangerActiveFraction,
                pageSlice = pageSlice,
                selectedPin = selectedPin,
                consumerPins = pins,
            )
        }
    }
}
