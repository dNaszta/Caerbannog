package dev.caerbannog.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeSeekPresetsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `the presets are 5 minutes, 1 hour and 24 hours back from now`() {
        assertEquals(now - 300_000L, TimeSeekPresets.since(now, TimeSeekPresets.FIVE_MINUTES_MS))
        assertEquals(now - 3_600_000L, TimeSeekPresets.since(now, TimeSeekPresets.ONE_HOUR_MS))
        assertEquals(now - 86_400_000L, TimeSeekPresets.since(now, TimeSeekPresets.ONE_DAY_MS))
    }

    @Test
    fun `the Time start mode defaults to one hour back`() {
        assertEquals(3_600_000L, TimeSeekPresets.DEFAULT_LOOKBACK_MS)
        assertEquals(now - 3_600_000L, TimeSeekPresets.defaultStart(now))
    }

    @Test
    fun `a later clock reading moves the start point with it`() {
        assertEquals(TimeSeekPresets.defaultStart(now) + 1_000L, TimeSeekPresets.defaultStart(now + 1_000L))
    }
}
