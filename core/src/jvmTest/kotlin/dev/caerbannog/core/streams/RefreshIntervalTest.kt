package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M2-04 step 2, decision D3 (product owner 2026-11-04): the refresh interval is a CLOSED set Off, 5, 10, 30, 60 seconds,
 * the default is 5 s in one constant (a trial, to be revisited), the value is per app and lives behind [AppSettings].
 * Contract (package `dev.caerbannog.core.streams`, commonMain):
 *   enum class RefreshInterval(val seconds: Int)  -- entries in this order: Off(0), Every5(5), Every10(10), Every30(30), Every60(60)
 *   companion fun RefreshInterval.fromSeconds(seconds: Int): RefreshInterval?   -- null for anything not in the set (7, -1, 3600)
 *   val DEFAULT_REFRESH: RefreshInterval                -- top level, seconds == 5
 *   interface AppSettings { var streamListRefresh: RefreshInterval }  (in-memory fake: FakeAppSettings; the file is M2-05)
 */
class RefreshIntervalTest {

    @Test
    fun `the allowed values are exactly Off 5 10 30 60`() {
        assertEquals(listOf(0, 5, 10, 30, 60), RefreshInterval.entries.map { it.seconds })
    }

    @Test
    fun `fromSeconds accepts the allowed values and nothing else`() {
        for (interval in RefreshInterval.entries) assertEquals(interval, RefreshInterval.fromSeconds(interval.seconds))
        for (bad in listOf(-1, 1, 7, 15, 59, 61, 3600, Int.MAX_VALUE)) {
            assertNull(RefreshInterval.fromSeconds(bad), "$bad is not an allowed interval")
        }
    }

    @Test
    fun `the default is 5 seconds`() {
        assertEquals(5, DEFAULT_REFRESH.seconds)
    }

    @Test
    fun `a fresh install has the default`() {
        assertEquals(DEFAULT_REFRESH, FakeAppSettings().streamListRefresh)
    }

    @Test
    fun `the setting is kept in memory`() {
        val settings = FakeAppSettings()
        settings.streamListRefresh = RefreshInterval.Off
        assertEquals(RefreshInterval.Off, settings.streamListRefresh)
    }
}
