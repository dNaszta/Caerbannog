package dev.caerbannog.core.browser

import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamLogTapeTest {

    private fun consumer(
        key: String,
        name: String = key,
        offset: Long? = 100L,
        status: ConsumerStatus = ConsumerStatus.CaughtUp,
    ) = ConsumerRowState(
        key = key,
        name = name,
        connection = "127.0.0.1:50000",
        user = "admin",
        offset = offset?.toString() ?: "updating",
        behind = "0",
        status = status,
        browseFromOffset = offset,
    )

    @Test
    fun `returns null when first or last offset is missing or invalid`() {
        assertNull(StreamLogTape.from(firstOffset = null, lastOffset = 100L))
        assertNull(StreamLogTape.from(firstOffset = 0L, lastOffset = null))
        assertNull(StreamLogTape.from(firstOffset = 200L, lastOffset = 100L))
    }

    @Test
    fun `computes active window without truncation when firstOffset is 0`() {
        val tape = StreamLogTape.from(
            firstOffset = 0L,
            lastOffset = 999L,
            pageFirstOffset = 100L,
            pageLastOffset = 199L,
            selectedOffset = 150L,
        )
        assertNotNull(tape)
        assertEquals(0L, tape.firstOffset)
        assertEquals(999L, tape.lastOffset)
        assertEquals(1000L, tape.totalActiveMessages)
        assertEquals(0L, tape.truncatedCount)
        assertEquals(0f, tape.truncatedFraction)
        assertNull(tape.dangerZoneEndOffset)

        val slice = tape.pageSlice
        assertNotNull(slice)
        assertEquals(100L, slice.firstOffset)
        assertEquals(199L, slice.lastOffset)
        assertTrue(slice.startFraction in 0.09f..0.11f)
        assertTrue(slice.endFraction in 0.19f..0.21f)

        val selected = tape.selectedPin
        assertNotNull(selected)
        assertEquals(150L, selected.offset)
        assertTrue(selected.fraction in 0.14f..0.16f)
    }

    @Test
    fun `computes truncated zone and retention danger cliff when firstOffset is greater than 0`() {
        val tape = StreamLogTape.from(
            firstOffset = 2000L,
            lastOffset = 10000L,
            pageFirstOffset = 9900L,
            pageLastOffset = 10000L,
            consumers = listOf(
                consumer("c-risk", "analytics-worker", offset = 2200L, status = ConsumerStatus.Behind(7800L)),
                consumer("c-lag", "batch-etl", offset = 6000L, status = ConsumerStatus.Behind(4000L)),
                consumer("c-head", "realtime-sink", offset = 10000L, status = ConsumerStatus.CaughtUp),
                consumer("c-standby", "standby-node", offset = 5000L, status = ConsumerStatus.NotActive),
                consumer("c-settling", "settling-node", offset = null, status = ConsumerStatus.Updating),
            ),
        )
        assertNotNull(tape)
        assertEquals(2000L, tape.firstOffset)
        assertEquals(10000L, tape.lastOffset)
        assertEquals(8001L, tape.totalActiveMessages)
        assertEquals(2000L, tape.truncatedCount)
        assertTrue(tape.truncatedFraction > 0f)
        // 10% of 8000 span = 2000 + 800 = 2800
        assertEquals(2800L, tape.dangerZoneEndOffset)

        // Settling consumer (null offset) is excluded from pins
        assertEquals(4, tape.consumerPins.size)

        val riskPin = tape.consumerPins.first { it.key == "c-risk" }
        assertEquals(ConsumerPinHealth.AtRisk, riskPin.health)
        assertEquals(0.025f, riskPin.fraction, 0.005f)

        val lagPin = tape.consumerPins.first { it.key == "c-lag" }
        assertEquals(ConsumerPinHealth.Lagging, lagPin.health)
        assertEquals(0.5f, lagPin.fraction, 0.005f)

        val headPin = tape.consumerPins.first { it.key == "c-head" }
        assertEquals(ConsumerPinHealth.CaughtUp, headPin.health)
        assertEquals(1.0f, headPin.fraction, 0.005f)

        val standbyPin = tape.consumerPins.first { it.key == "c-standby" }
        assertEquals(ConsumerPinHealth.Inactive, standbyPin.health)
    }

    @Test
    fun `offsetAtTotalFraction maps canvas clicks accurately across truncated and active regions`() {
        val tapeNoTrunc = StreamLogTape.from(firstOffset = 0L, lastOffset = 1000L)!!
        assertEquals(0L, tapeNoTrunc.offsetAtTotalFraction(0f))
        assertEquals(500L, tapeNoTrunc.offsetAtTotalFraction(0.5f))
        assertEquals(1000L, tapeNoTrunc.offsetAtTotalFraction(1f))

        val tapeWithTrunc = StreamLogTape.from(firstOffset = 1000L, lastOffset = 2000L)!!
        // Clicking inside the truncated region clamps to firstOffset (1000L)
        assertEquals(1000L, tapeWithTrunc.offsetAtTotalFraction(0.05f))
        // Clicking at midpoint of active region (truncatedFraction + 0.5 * (1 - truncatedFraction)) -> 1500L
        val midTotal = tapeWithTrunc.truncatedFraction + 0.5f * (1f - tapeWithTrunc.truncatedFraction)
        assertEquals(1500L, tapeWithTrunc.offsetAtTotalFraction(midTotal))
        assertEquals(2000L, tapeWithTrunc.offsetAtTotalFraction(1f))
    }
}
