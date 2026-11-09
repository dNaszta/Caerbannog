package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.DEFAULT_REFRESH
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * M5-07 call site #9: a hand-edited `settings.json` nested ten thousand levels deep reads as "unreadable, use defaults" (limit 32), never a
 * StackOverflowError at start-up (not caught here on purpose). The log line must not contain the file text.
 */
class JsonFileAppSettingsNestingTest {

    @TempDir
    lateinit var dir: File

    private val file: File get() = File(dir, "settings.json")

    @Test
    fun `a deep settings file reads as the default and does not throw`() {
        file.writeText(DeepJson.arrays())
        assertEquals(DEFAULT_REFRESH, JsonFileAppSettings(file) {}.streamListRefresh)
        file.writeText(DeepJson.objects())
        assertEquals(DEFAULT_REFRESH, JsonFileAppSettings(file) {}.streamListRefresh)
    }

    @Test
    fun `a deep value next to a valid key is not trusted and the default is used`() {
        file.writeText("""{"streamListRefreshSeconds":10,"extra":${DeepJson.arrays()}}""")
        assertEquals(DEFAULT_REFRESH, JsonFileAppSettings(file) {}.streamListRefresh)
    }

    @Test
    fun `the log line says the file could not be read and does not echo it`() {
        file.writeText(DeepJson.arrays())
        val lines = mutableListOf<String>()
        JsonFileAppSettings(file) { lines += it }
        assertTrue(lines.isNotEmpty(), "a log line is expected")
        assertTrue(lines.none { it.contains("[[[") })
        assertFalse(lines.any { it.length > 300 })
    }

    @Test
    fun `setting a value over a deep file replaces it with a small valid file`() {
        file.writeText(DeepJson.arrays())
        val settings = JsonFileAppSettings(file) {}
        settings.streamListRefresh = dev.caerbannog.core.streams.RefreshInterval.Every5
        assertTrue(file.length() < 200, "the deep file must be replaced, size ${file.length()}")
    }

    @Test
    fun `32 levels are read and 33 are not`() {
        // root object + 31 arrays under "extra" = 32 levels
        file.writeText("""{"streamListRefreshSeconds":10,"extra":${DeepJson.arrays(31)}}""")
        assertEquals(10, JsonFileAppSettings(file) {}.streamListRefresh.seconds)
        file.writeText("""{"streamListRefreshSeconds":10,"extra":${DeepJson.arrays(32)}}""")
        assertEquals(DEFAULT_REFRESH, JsonFileAppSettings(file) {}.streamListRefresh)
    }
}
