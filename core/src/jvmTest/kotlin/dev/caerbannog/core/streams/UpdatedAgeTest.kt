package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M2-06 follow-up F-D: the one age formatter shared by the stream list and the stream screen. */
class UpdatedAgeTest {
    @Test
    fun `it reads whole seconds since the last load`() {
        assertEquals("Updated 12 s ago", UpdatedAge.text(nowMillis = 22_000, updatedAtMillis = 10_000))
        assertEquals("Updated 0 s ago", UpdatedAge.text(nowMillis = 10_000, updatedAtMillis = 10_000))
    }

    @Test
    fun `seconds only, a part of a second is cut, never rounded up or shown as minutes`() {
        assertEquals("Updated 0 s ago", UpdatedAge.text(nowMillis = 10_999, updatedAtMillis = 10_000))
        assertEquals("Updated 1 s ago", UpdatedAge.text(nowMillis = 11_000, updatedAtMillis = 10_000))
        assertEquals("Updated 125 s ago", UpdatedAge.text(nowMillis = 135_000, updatedAtMillis = 10_000), "no minutes, no hours")
    }

    @Test
    fun `a clock that went back never gives a negative age`() {
        assertEquals("Updated 0 s ago", UpdatedAge.text(nowMillis = 5_000, updatedAtMillis = 10_000))
        assertEquals("Updated 0 s ago", UpdatedAge.text(nowMillis = Long.MIN_VALUE / 2, updatedAtMillis = Long.MAX_VALUE / 2))
    }

    @Test
    fun `there is no text before the first successful load`() {
        assertNull(UpdatedAge.text(nowMillis = 10_000, updatedAtMillis = null))
    }
}
