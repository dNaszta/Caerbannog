package dev.caerbannog.datarabbit

import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * M9-02: the planner's naming style in `settings.json` (key `plannerNamingStyle`, per machine, spec Q8). The value written is the label the
 * user sees ("kebab-case", "snake_case", "dot.separated", "camelCase", "No convention"), so the file can be read and edited by hand. A missing
 * key, an unknown value or a value of the wrong type reads as the default (kebab-case) and never throws; other keys of the file survive a write.
 */
class JsonFileAppSettingsPlannerStyleTest {
    @TempDir
    lateinit var dir: File

    private val file: File get() = File(dir, "settings.json")
    private fun settings() = JsonFileAppSettings(file) {}
    private fun parsed() = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `a missing file or key reads as kebab-case and reading creates nothing`() {
        assertEquals(NamingStyle.KEBAB, settings().plannerNamingStyle)
        assertFalse(file.exists())
        file.writeText("""{"streamListRefreshSeconds": 30}""")
        assertEquals(NamingStyle.KEBAB, settings().plannerNamingStyle)
    }

    @Test
    fun `an unknown value or a value of the wrong type reads as the default`() {
        listOf("\"Kebab\"", "\"\"", "\"UPPER\"", "7", "true", "null", "[\"snake_case\"]", "{\"a\":1}", "\"snake_case \"").forEach { body ->
            file.writeText("""{"plannerNamingStyle": $body}""")
            assertEquals(NamingStyle.KEBAB, settings().plannerNamingStyle, "for $body")
        }
    }

    @Test
    fun `a corrupt file reads as the default and is not repaired by reading`() {
        file.writeText("{ broken")
        assertEquals(NamingStyle.KEBAB, settings().plannerNamingStyle)
        assertEquals("{ broken", file.readText())
    }

    @Test
    fun `every style survives a write and a read by a new instance, stored as its label`() {
        NamingStyle.entries.forEach { style ->
            settings().plannerNamingStyle = style
            assertEquals(style, settings().plannerNamingStyle, style.label)
            assertEquals(style.label, parsed().getValue("plannerNamingStyle").jsonPrimitive.content)
        }
    }

    @Test
    fun `the instance that wrote keeps the value even when the write fails`() {
        val logged = mutableListOf<String>()
        val blocked = File(dir, "a-file-not-a-folder").also { it.writeText("x") }
        val s = JsonFileAppSettings(File(blocked, "settings.json")) { logged += it }
        s.plannerNamingStyle = NamingStyle.CAMEL
        assertEquals(NamingStyle.CAMEL, s.plannerNamingStyle)
        assertEquals(1, logged.size, "the failure goes to the log, not to the caller: $logged")
    }

    @Test
    fun `writing the style keeps the other keys and writing another key keeps the style`() {
        file.writeText("""{"streamListRefreshSeconds": 30, "migrationsFolder": "/work/migrations", "fromALaterVersion": {"a": [1, 2]}}""")
        val s = settings()
        s.plannerNamingStyle = NamingStyle.DOT
        s.streamListRefresh = RefreshInterval.Every10
        val tree = parsed()
        assertEquals("dot.separated", tree.getValue("plannerNamingStyle").jsonPrimitive.content)
        assertEquals("/work/migrations", tree.getValue("migrationsFolder").jsonPrimitive.content)
        assertEquals("10", tree.getValue("streamListRefreshSeconds").jsonPrimitive.content)
        assertEquals("""{"a":[1,2]}""", tree.getValue("fromALaterVersion").toString())
        assertEquals(NamingStyle.DOT, settings().plannerNamingStyle)
    }

    @Test
    fun `the file holds no secret`() {
        settings().plannerNamingStyle = NamingStyle.NONE
        assertFalse(file.readText().contains("password", ignoreCase = true))
    }
}
