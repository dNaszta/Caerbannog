package dev.caerbannog.core.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-06 follow-up F-F: the lag rule as an invariant over a whole state, in both directions (lag ruling 2026-11-05).
 * Never "Behind" or "<n> behind" for lag 0, never "Caught up" for lag above 0. The mutation `row.offsetLag <= 0` -> `< 0`
 * (Behind at lag 0) and the reverse `<= 0` -> `<= 1` (Caught up at lag 1) are each caught here.
 */
class StreamClientsLagInvariantTest {
    @Test
    fun `in one state only the lag 0 consumers are Caught up and only the others read behind`() = runTest {
        val lags = listOf(0L, 1L, 0L, 2L, 3L, 0L, 349L, 1_234_567L)
        val s = ClientsHarness(this)
        s.consumers(*lags.mapIndexed { i, lag -> s.consumerAt("c$i", 100, lag = lag, id = i) }.toTypedArray())
        s.open()

        val rows = s.state.consumers
        assertEquals(lags.size, rows.size)
        rows.forEachIndexed { i, row ->
            val lag = lags[i]
            if (lag == 0L) {
                assertEquals("Caught up", row.status.text, "lag 0 reads Caught up")
                assertFalse(row.status.text.contains("behind", ignoreCase = true), "lag 0 never reads behind: ${row.status.text}")
                assertEquals("0", row.behind)
            } else {
                assertTrue(row.status.text.endsWith(" behind"), "lag $lag reads '<n> behind', got ${row.status.text}")
                assertFalse(row.status.text == "Caught up", "lag $lag is never Caught up")
            }
        }
    }
}
