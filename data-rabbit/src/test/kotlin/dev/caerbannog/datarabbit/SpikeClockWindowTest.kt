package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fast, no container: the Q3 window must not depend on how long the test took after publishing. */
class SpikeClockWindowTest {
    private val publishedAt = 1_800_000_000_000L
    private val fifteenMinutes = 15 * 60_000L

    @Test
    fun `first message stamped right after publishedAt is inside the window even if the assert runs 15 minutes later`() {
        val firstTimestamp = publishedAt + 2_000
        val clockAtAssert = { publishedAt + fifteenMinutes }
        // The window is anchored on publishedAt, not on the clock at assert time.
        assertTrue(firstTimestamp in SpikeClockWindow.firstTimestampWindow(publishedAt), "clock at assert time was ${clockAtAssert()}")
    }

    @Test
    fun `a timestamp from before publishing is outside the window`() {
        assertFalse(publishedAt - 6_000 in SpikeClockWindow.firstTimestampWindow(publishedAt))
    }

    @Test
    fun `a timestamp later than the publishing budget is outside the window`() {
        assertFalse(publishedAt + 600_001 in SpikeClockWindow.firstTimestampWindow(publishedAt))
    }
}
