package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationFolderStore
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M5-08b: the `migrationsFolder` key of settings.json behind [MigrationFolderStore]. A bad file means "ask again" and is not touched by reading. */
class MigrationFolderStoreTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "settings.json")
    private fun store(): MigrationFolderStore = JsonFileAppSettings(file) {}
    private fun keys() = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `the settings file is owner-only, mode 600`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here")
        store().folder = "/work/migrations"

        assertEquals(
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            java.nio.file.Files.getPosixFilePermissions(file.toPath()),
        )
    }

    @Test
    fun `a fresh machine has no folder and reading creates no file`() {
        assertNull(store().folder)
        assertTrue(!file.exists())
    }

    @Test
    fun `the folder survives a restart`() {
        store().folder = "/work/migrations"

        assertEquals("/work/migrations", store().folder, "a new instance (a restart) must read the saved folder")
        assertEquals("/work/migrations", keys()["migrationsFolder"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a folder with spaces and non-ASCII letters round-trips`() {
        val odd = "/work/Müller & Söhne \"x\"/migrations"
        store().folder = odd
        assertEquals(odd, store().folder)
    }

    @Test
    fun `setting the folder keeps unknown keys and the refresh interval`() {
        file.writeText("""{"streamListRefreshSeconds":10,"futureKey":{"a":[1,2]},"other":"x"}""")

        store().folder = "/work/migrations"

        val k = keys()
        assertEquals("/work/migrations", k["migrationsFolder"]?.jsonPrimitive?.content)
        assertEquals(10, k["streamListRefreshSeconds"]?.jsonPrimitive?.content?.toInt())
        assertNotNull(k["futureKey"], "an unknown key of a later version must survive")
        assertEquals("x", k["other"]?.jsonPrimitive?.content)
    }

    @Test
    fun `changing the refresh interval keeps the folder`() {
        val settings = JsonFileAppSettings(file) {}
        settings.folder = "/work/migrations"

        settings.streamListRefresh = RefreshInterval.fromSeconds(10)!!

        assertEquals("/work/migrations", JsonFileAppSettings(file) {}.folder)
    }

    @Test
    fun `clearing the folder removes the key`() {
        val s = store()
        s.folder = "/work/migrations"
        s.folder = null

        assertNull(store().folder)
        assertTrue(keys()["migrationsFolder"] == null || keys()["migrationsFolder"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a corrupt file gives no folder and reading leaves its bytes alone`() {
        val bytes = "{ not json SENTINEL".toByteArray()
        file.writeBytes(bytes)
        val lines = mutableListOf<String>()

        assertNull(JsonFileAppSettings(file) { lines += it }.folder)

        assertTrue(bytes.contentEquals(file.readBytes()), "reading must not rewrite a corrupt settings file")
        assertTrue(lines.none { it.contains("SENTINEL") }, "the log must not echo the file: $lines")
    }

    @Test
    fun `a file nested ten thousand levels deep gives no folder`() {
        file.writeText(DeepJson.objects())
        assertNull(store().folder)
        file.writeText("""{"migrationsFolder":${DeepJson.arrays()}}""")
        assertNull(store().folder)
    }

    @Test
    fun `a folder value that is not text is ignored`() {
        file.writeText("""{"migrationsFolder":42}""")
        assertNull(store().folder)
        file.writeText("""{"migrationsFolder":["/a"]}""")
        assertNull(store().folder)
        file.writeText("""{"migrationsFolder":""}""")
        assertNull(store().folder, "an empty path is no folder")
    }

    @Test
    fun `a valid settings file keeps working next to the folder key`() {
        file.writeText("""{"migrationsFolder":"/a/b","streamListRefreshSeconds":0}""")
        val s = JsonFileAppSettings(file) {}
        assertEquals("/a/b", s.folder)
        assertEquals(0, s.streamListRefresh.seconds)
        assertEquals(JsonPrimitive("/a/b"), keys()["migrationsFolder"])
    }
}
