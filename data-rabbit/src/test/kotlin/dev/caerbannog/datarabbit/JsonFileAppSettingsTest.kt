package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * M2-05a step 2 (written test-first, docs/tasks/M2-05-stream-list-screen.md Scope item 3): the `settings.json` file behind
 * [dev.caerbannog.core.streams.AppSettings]. Format `{"streamListRefreshSeconds": 5}`. Real files in a JUnit `@TempDir`; never
 * `~/.caerbannog`, no sleeps, no broker.
 *
 * Contract (class [JsonFileAppSettings], package `dev.caerbannog.datarabbit`):
 *   JsonFileAppSettings(file: File, log: (String) -> Unit = <a stderr line writer>) : AppSettings
 *   companion fun defaultFile(): File            // ~/.caerbannog/settings.json
 *   var streamListRefresh: RefreshInterval       // get: what the file says; set: writes (and keeps the value in memory)
 * Read rules (architect ruling in the brief, amends D3): missing file, empty file, missing key, unknown number (7), wrong type
 * and corrupt JSON all read as DEFAULT_REFRESH (5 s) and never throw; the number 0 reads as Off. Write rules: atomic (temp file in
 * the same folder, then move; no temp file left), creates the folder, keeps unknown keys, a failed write does not throw, the
 * property keeps the new value in memory and the failure goes to `log` (never the value of a secret: there is none).
 *
 * The tests build a NEW instance after they prepare the file, so they hold whether the implementation reads on construction or
 * on every get.
 */
class JsonFileAppSettingsTest {

    @TempDir
    lateinit var dir: File

    private val file: File get() = File(dir, "settings.json")
    private fun settings(f: File = file, log: (String) -> Unit = {}) = JsonFileAppSettings(f, log)
    private fun parsed(f: File = file): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject

    // --- reads that fall back to the default ----------------------------------------------------------------------

    @Test
    fun `the default is 5 seconds`() {
        assertEquals(RefreshInterval.Every5, DEFAULT_REFRESH, "the brief fixes the default at 5 s")
    }

    @Test
    fun `a missing file reads as the default and is not created by reading`() {
        assertEquals(DEFAULT_REFRESH, settings().streamListRefresh)
        assertFalse(file.exists(), "reading must not create the file")
    }

    @Test
    fun `an empty file reads as the default`() {
        file.writeText("")
        assertEquals(DEFAULT_REFRESH, settings().streamListRefresh)
    }

    @Test
    fun `a file without the key reads as the default`() {
        file.writeText("""{"somethingElse": 30}""")
        assertEquals(DEFAULT_REFRESH, settings().streamListRefresh)
    }

    @Test
    fun `an unknown number reads as the default`() {
        file.writeText("""{"streamListRefreshSeconds": 7}""")
        assertEquals(DEFAULT_REFRESH, settings().streamListRefresh)
    }

    @Test
    fun `a negative number reads as the default`() {
        file.writeText("""{"streamListRefreshSeconds": -5}""")
        assertEquals(DEFAULT_REFRESH, settings().streamListRefresh)
    }

    @Test
    fun `a key of the wrong type reads as the default`() {
        for (body in listOf(
            """{"streamListRefreshSeconds": "30"}""",
            """{"streamListRefreshSeconds": 30.5}""",
            """{"streamListRefreshSeconds": null}""",
            """{"streamListRefreshSeconds": true}""",
            """{"streamListRefreshSeconds": [30]}""",
            """{"streamListRefreshSeconds": {"a": 30}}""",
        )) {
            file.writeText(body)
            assertEquals(DEFAULT_REFRESH, settings().streamListRefresh, "for $body")
        }
    }

    @Test
    fun `corrupt JSON reads as the default and does not throw`() {
        for (body in listOf("{", "not json at all", "[1, 2]", "\"text\"", "{\"streamListRefreshSeconds\": 30", "\u0000\u0001\u0002")) {
            file.writeText(body)
            assertEquals(DEFAULT_REFRESH, settings().streamListRefresh, "for <$body>")
        }
    }

    @Test
    fun `a corrupt file is not repaired by reading`() {
        file.writeText("{ broken")
        settings().streamListRefresh
        assertEquals("{ broken", file.readText(), "reading must not touch the file")
    }

    // --- the zero means Off ---------------------------------------------------------------------------------------

    @Test
    fun `the number 0 reads as Off, not as the default`() {
        file.writeText("""{"streamListRefreshSeconds": 0}""")
        assertEquals(RefreshInterval.Off, settings().streamListRefresh)
    }

    // --- round trips ----------------------------------------------------------------------------------------------

    @Test
    fun `every value survives a write and a read by a new instance`() {
        for (value in RefreshInterval.entries) {
            settings().streamListRefresh = value
            assertEquals(value, settings().streamListRefresh, "new instance after writing $value")
            assertEquals(value.seconds, parsed().getValue("streamListRefreshSeconds").jsonPrimitive.int, "the file stores the seconds of $value")
        }
    }

    @Test
    fun `the instance that wrote keeps the value`() {
        val s = settings()
        s.streamListRefresh = RefreshInterval.Every30
        assertEquals(RefreshInterval.Every30, s.streamListRefresh)
    }

    @Test
    fun `Off is written as 0 so a restart reads Off and not the default`() {
        settings().streamListRefresh = RefreshInterval.Off
        assertEquals(0, parsed().getValue("streamListRefreshSeconds").jsonPrimitive.int)
        assertEquals(RefreshInterval.Off, settings().streamListRefresh)
    }

    @Test
    fun `writing over a corrupt file leaves a valid file with the value`() {
        file.writeText("{ broken")
        settings().streamListRefresh = RefreshInterval.Every10
        assertEquals(RefreshInterval.Every10, settings().streamListRefresh)
        assertEquals(10, parsed().getValue("streamListRefreshSeconds").jsonPrimitive.int)
    }

    // --- write rules ----------------------------------------------------------------------------------------------

    @Test
    fun `the write creates the folder`() {
        val nested = File(dir, "a/b/.caerbannog/settings.json")
        settings(nested).streamListRefresh = RefreshInterval.Every60
        assertEquals(RefreshInterval.Every60, settings(nested).streamListRefresh)
    }

    @Test
    fun `unknown keys already in the file survive a write`() {
        file.writeText("""{"streamListRefreshSeconds": 10, "futureSetting": "keep me", "nested": {"a": [1, 2]}, "flag": true}""")
        settings().streamListRefresh = RefreshInterval.Every60
        val written = parsed()
        assertEquals(60, written.getValue("streamListRefreshSeconds").jsonPrimitive.int)
        assertEquals(JsonPrimitive("keep me"), written["futureSetting"], "an older build must not drop a later setting")
        assertEquals(Json.parseToJsonElement("""{"a": [1, 2]}"""), written["nested"])
        assertEquals(JsonPrimitive(true), written["flag"])
    }

    @Test
    fun `the write leaves no temp file behind`() {
        val s = settings()
        for (value in RefreshInterval.entries) s.streamListRefresh = value
        assertEquals(listOf("settings.json"), dir.list()!!.sorted(), "only the settings file may remain in the folder")
    }

    @Test
    fun `two quick writes leave one valid file with the last value`() {
        val s = settings()
        s.streamListRefresh = RefreshInterval.Every10
        s.streamListRefresh = RefreshInterval.Every60
        assertEquals(60, parsed().getValue("streamListRefreshSeconds").jsonPrimitive.int)
        assertEquals(RefreshInterval.Every60, settings().streamListRefresh)
        assertEquals(listOf("settings.json"), dir.list()!!.sorted())
    }

    /**
     * Atomic write, observed: a reader that polls the file while another thread writes 300 times must never see a half
     * written file (a plain `writeText` truncates first, so an empty or cut file shows up). A missing file is allowed before
     * the first write; after it, every read parses and holds one of the two values written.
     */
    @Test
    fun `a reader never sees a partial file while another thread writes`() {
        val s = settings()
        s.streamListRefresh = RefreshInterval.Every10 // the file exists from now on
        val done = AtomicBoolean(false)
        val problems = Collections.synchronizedList(ArrayList<String>())
        val reader = thread(name = "settings-reader") {
            try {
                while (!done.get()) {
                    val text = try { file.readText() } catch (e: java.io.IOException) { problems += "unreadable: $e"; continue }
                    val seconds = runCatching { Json.parseToJsonElement(text).jsonObject.getValue("streamListRefreshSeconds").jsonPrimitive.int }
                    if (seconds.getOrNull() !in setOf(10, 30)) problems += "saw <$text>"
                }
            } catch (t: Throwable) {
                problems += "reader failed: $t"
            }
        }
        val writer = thread(name = "settings-writer") {
            repeat(300) { s.streamListRefresh = if (it % 2 == 0) RefreshInterval.Every30 else RefreshInterval.Every10 }
            done.set(true)
        }
        writer.join(60_000)
        reader.join(60_000)
        assertTrue(problems.isEmpty(), "the reader saw a partial or unreadable file ${problems.size} times, first: ${problems.take(3)}")
        assertEquals(listOf("settings.json"), dir.list()!!.sorted(), "no temp file left")
    }

    // --- a failed write -------------------------------------------------------------------------------------------

    @Test
    fun `a failed write does not throw, keeps the value in memory and is logged`() {
        // The folder of the settings file is a regular file, so the folder cannot be created and the file cannot be written.
        val blocker = File(dir, "blocker").also { it.writeText("I am a file, not a folder") }
        val lines = Collections.synchronizedList(ArrayList<String>())
        val s = settings(File(blocker, "settings.json")) { lines += it }

        s.streamListRefresh = RefreshInterval.Every30 // must not throw

        assertEquals(RefreshInterval.Every30, s.streamListRefresh, "the value stays in memory for this run")
        assertTrue(lines.isNotEmpty(), "the failure must reach the logger")
        assertEquals("I am a file, not a folder", blocker.readText(), "nothing else is touched")
    }

    @Test
    fun `defaultFile is settings json next to the profiles file`() {
        assertEquals(File(System.getProperty("user.home"), ".caerbannog/settings.json"), JsonFileAppSettings.defaultFile())
        assertEquals(JsonFileConnectionProfileRepository.defaultFile().parentFile, JsonFileAppSettings.defaultFile().parentFile)
    }
}
