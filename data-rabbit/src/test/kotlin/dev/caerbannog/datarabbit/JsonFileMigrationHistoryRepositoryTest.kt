package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.HistoryKeyResult
import dev.caerbannog.core.migrations.HistoryMoveResult
import dev.caerbannog.core.migrations.HistoryResult
import dev.caerbannog.core.migrations.NowProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M5-08b: `migration-history.json` on a temp folder: round trip, never overwritten while unreadable, moved aside and never deleted. */
class JsonFileMigrationHistoryRepositoryTest {

    @TempDir
    lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")
    private val file: Path get() = root.toPath().resolve("data/migration-history.json")
    private var clock = LocalDateTime.of(2026, 11, 9, 14, 30, 5)
    private val now = NowProvider { clock.atZone(zone).toInstant().toEpochMilli() }

    private fun repo(mover: FileMover = NioFileMover) = JsonFileMigrationHistoryRepository(file, now, mover, zone)
    private val crash = FileMover { _, _ -> throw IOException("simulated crash between the temp file and the move") }

    private fun put(text: String) {
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
    }

    private fun bytes() = Files.readAllBytes(file)
    private fun names(): Set<String> = Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
    private fun load(profile: String = "uat-eu") = runBlocking { repo().load(profile) }

    // ---- round trip ----

    @Test
    fun `a missing file is an empty history and reading creates nothing`() {
        assertEquals(HistoryResult.Loaded(emptyMap()), load())
        assertFalse(Files.exists(file))
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `a recorded version round-trips through a new instance and the file has the documented shape`() = runBlocking {
        repo().recordApplied("uat-eu", "0001", "abc123")

        assertEquals(HistoryResult.Loaded(mapOf("0001" to "abc123")), repo().load("uat-eu"))
        assertEquals(mapOf("0001" to "abc123"), repo().getAppliedMigrations("uat-eu"))
        val tree = Json.parseToJsonElement(String(bytes())).jsonObject
        assertEquals("1", tree["schema"]!!.jsonPrimitive.content)
        assertEquals("abc123", tree["profiles"]!!.jsonObject["uat-eu"]!!.jsonObject["0001"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the repository creates its own parent folder`() = runBlocking {
        assertFalse(Files.exists(file.parent))
        repo().recordApplied("uat-eu", "0001", "abc")
        assertTrue(Files.isRegularFile(file))
    }

    @Test
    fun `two profiles keep separate histories and a record keeps the others`() = runBlocking {
        val r = repo()
        r.recordApplied("uat-eu", "0001", "a1")
        r.recordApplied("prod-eu", "0001", "b1")
        r.recordApplied("uat-eu", "0002", "a2")

        assertEquals(mapOf("0001" to "a1", "0002" to "a2"), repo().getAppliedMigrations("uat-eu"))
        assertEquals(mapOf("0001" to "b1"), repo().getAppliedMigrations("prod-eu"))
        assertEquals(emptyMap(), repo().getAppliedMigrations("other"))
    }

    @Test
    fun `profile names with unusual characters round-trip`() = runBlocking {
        val odd = "prod \"eu\" / ü\\x.json"
        repo().recordApplied(odd, "0001", "c")
        assertEquals(mapOf("0001" to "c"), repo().getAppliedMigrations(odd))
    }

    @Test
    fun `the history file is owner-only, mode 600`() = runBlocking<Unit> {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here")
        repo().recordApplied("uat-eu", "0001", "a")

        assertEquals(
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    // ---- rename and delete return a result (round 1, F1) ----

    @Test
    fun `rename and delete return Done when the write works, also when there is nothing to do`() = runBlocking<Unit> {
        assertEquals(HistoryKeyResult.Done, repo().rename("a", "b"))
        assertEquals(HistoryKeyResult.Done, repo().delete("a"))
        repo().recordApplied("uat-eu", "0001", "a")
        assertEquals(HistoryKeyResult.Done, repo().rename("uat-eu", "x"))
        assertEquals(HistoryKeyResult.Done, repo().delete("x"))
        assertEquals(emptyMap(), repo().getAppliedMigrations("x"))
    }

    @Test
    fun `a failing write makes rename and delete return Failed with fixed text and leave the entries where they were`() = runBlocking<Unit> {
        repo().recordApplied("uat-eu", "0001", "a")
        val before = bytes()

        val renamed = repo(crash).rename("uat-eu", "uat-eu-2")
        val deleted = repo(crash).delete("uat-eu")

        for (r in listOf(renamed, deleted)) {
            val failed = assertIs<HistoryKeyResult.Failed>(r)
            assertTrue(failed.reason.isNotBlank())
            assertFalse(failed.reason.contains("simulated") || failed.reason.contains(root.toString()), "the reason is fixed text: ${failed.reason}")
        }
        assertTrue(before.contentEquals(bytes()))
        assertEquals(mapOf("0001" to "a"), repo().getAppliedMigrations("uat-eu"))
        assertEquals(setOf("migration-history.json"), names())
    }

    @Test
    fun `an unreadable history counts as Done for rename and delete and stays untouched`() {
        put("{ broken SENTINEL")
        val before = bytes()

        assertEquals(HistoryKeyResult.Done, repo().rename("uat-eu", "x"))
        assertEquals(HistoryKeyResult.Done, repo().delete("uat-eu"))

        assertTrue(before.contentEquals(bytes()))
    }

    // ---- rename ----

    @Test
    fun `renaming a profile moves its history and persists it`() = runBlocking {
        val r = repo()
        r.recordApplied("uat-eu", "0001", "a1")
        r.recordApplied("prod-eu", "0001", "b1")

        r.rename("uat-eu", "uat-eu-2")

        assertEquals(mapOf("0001" to "a1"), repo().getAppliedMigrations("uat-eu-2"))
        assertEquals(emptyMap(), repo().getAppliedMigrations("uat-eu"))
        assertEquals(mapOf("0001" to "b1"), repo().getAppliedMigrations("prod-eu"))
    }

    @Test
    fun `renaming onto a leftover history replaces it with the renamed profile's entries`() = runBlocking {
        val r = repo()
        r.recordApplied("uat-eu", "0001", "a1")
        r.recordApplied("leftover", "0001", "old")
        r.recordApplied("leftover", "0002", "old2")
        r.recordApplied("prod-eu", "0009", "p9")

        r.rename("uat-eu", "leftover")

        assertEquals(mapOf("0001" to "a1"), repo().getAppliedMigrations("leftover"), "the leftover must be replaced, not merged")
        assertEquals(emptyMap(), repo().getAppliedMigrations("uat-eu"))
        assertEquals(mapOf("0009" to "p9"), repo().getAppliedMigrations("prod-eu"))
    }

    @Test
    fun `renaming a profile that has no history removes a leftover under the new name`() = runBlocking {
        val r = repo()
        r.recordApplied("leftover", "0001", "old")

        r.rename("uat-eu", "leftover")

        assertEquals(emptyMap(), repo().getAppliedMigrations("leftover"), "the new name must not inherit the old leftover")
    }

    @Test
    fun `deleting a profile removes its entries, keeps the others and persists`() = runBlocking {
        val r = repo()
        r.recordApplied("uat-eu", "0001", "a1")
        r.recordApplied("prod-eu", "0001", "b1")

        r.delete("uat-eu")

        assertEquals(emptyMap(), repo().getAppliedMigrations("uat-eu"))
        assertEquals(mapOf("0001" to "b1"), repo().getAppliedMigrations("prod-eu"))
    }

    @Test
    fun `deleting a profile with no history writes nothing`() = runBlocking {
        repo().delete("uat-eu")
        assertFalse(Files.exists(file))
        repo().recordApplied("prod-eu", "0001", "b1")
        val before = bytes()
        repo().delete("uat-eu")
        assertTrue(before.contentEquals(bytes()))
    }

    @Test
    fun `deleting a profile never writes over an unreadable history`() {
        put("{ broken SENTINEL")
        val before = bytes()

        repo().delete("uat-eu")

        assertTrue(before.contentEquals(bytes()))
        assertEquals(setOf("migration-history.json"), names())
    }

    @Test
    fun `renaming a profile without history and renaming while the file is missing change nothing`() {
        repo().rename("uat-eu", "x")
        assertFalse(Files.exists(file), "nothing to move, nothing to write")
    }

    @Test
    fun `renaming never writes over an unreadable history`() {
        put("{ broken SENTINEL")
        val before = bytes()

        repo().rename("uat-eu", "x")

        assertTrue(before.contentEquals(bytes()))
    }

    // ---- unreadable ----

    private val corruptTexts = listOf("{ not json SENTINEL", "", "[]", """{"schema":1,"profiles":[]}""", """{"schema":1,"profiles":{"p":{"0001":5}}}""")

    @Test
    fun `a corrupt or wrongly shaped file is unreadable with the path and a reason, never an empty history`() {
        for (text in corruptTexts) {
            put(text)
            val r = load()
            val un = assertIs<HistoryResult.Unreadable>(r, "content <$text> must be unreadable, was $r")
            assertEquals(file.toString(), un.path)
            assertTrue(un.reason.isNotBlank())
            assertFalse(un.reason.contains("SENTINEL"), "the reason must not echo the file: ${un.reason}")
        }
    }

    @Test
    fun `a file nested ten thousand levels deep is unreadable and does not overflow the stack`() {
        put(DeepJson.objects())
        assertIs<HistoryResult.Unreadable>(load())
        put(DeepJson.arrays())
        assertIs<HistoryResult.Unreadable>(load())
        put("""{"schema":1,"profiles":{"p":${DeepJson.objects()}}}""")
        assertIs<HistoryResult.Unreadable>(load())
    }

    @Test
    fun `nothing is ever written while the file is unreadable`() = runBlocking {
        for (text in corruptTexts) {
            put(text)
            val before = bytes()

            runCatching { repo().recordApplied("uat-eu", "0001", "abc") }

            assertTrue(before.contentEquals(bytes()), "recordApplied overwrote an unreadable history <$text>")
            assertEquals(setOf("migration-history.json"), names(), "no temp or other file may be left beside it")
        }
    }

    @Test
    fun `the old getAppliedMigrations is a wrapper of load and does not throw on an unreadable file`() = runBlocking<Unit> {
        put("{ broken")
        val r = runCatching { repo().getAppliedMigrations("uat-eu") }
        assertTrue(r.isSuccess)
        assertTrue(r.getOrThrow().isEmpty())
    }

    // ---- atomic ----

    @Test
    fun `a crash between the temp file and the move leaves the old history and no temp file`() = runBlocking {
        repo().recordApplied("uat-eu", "0001", "a1")
        val before = bytes()

        runCatching { repo(crash).recordApplied("uat-eu", "0002", "a2") }

        assertTrue(before.contentEquals(bytes()))
        assertEquals(setOf("migration-history.json"), names())
        assertEquals(mapOf("0001" to "a1"), repo().getAppliedMigrations("uat-eu"))
    }

    @Test
    fun `a crash on the first record leaves no history file`() = runBlocking {
        runCatching { repo(crash).recordApplied("uat-eu", "0001", "a1") }
        assertFalse(Files.exists(file))
    }

    // ---- moveAside ----

    @Test
    fun `moveAside renames the file with the clock's timestamp, keeps every byte and then the history is empty`() = runBlocking<Unit> {
        val original = "{ broken \u00e9 SENTINEL\r\n".toByteArray(Charsets.UTF_8)
        Files.createDirectories(file.parent)
        Files.write(file, original)
        val r = repo()

        assertEquals("migration-history.json.unreadable-20261109-143005", r.asideFileName())
        val result = r.moveAside()

        assertEquals(HistoryMoveResult.Moved("migration-history.json.unreadable-20261109-143005"), result)
        assertEquals(setOf("migration-history.json.unreadable-20261109-143005"), names(), "the original name is free, the old file is kept")
        assertTrue(original.contentEquals(Files.readAllBytes(file.resolveSibling("migration-history.json.unreadable-20261109-143005"))))
        assertEquals(HistoryResult.Loaded(emptyMap()), repo().load("uat-eu"))
        r.recordApplied("uat-eu", "0001", "x")
        assertEquals(mapOf("0001" to "x"), repo().getAppliedMigrations("uat-eu"))
    }

    @Test
    fun `moveAside never overwrites an earlier file of the same second and numbers the new one`() = runBlocking {
        val first = "first broken".toByteArray()
        val second = "second broken".toByteArray()
        val third = "third broken".toByteArray()
        val base = "migration-history.json.unreadable-20261109-143005"

        put(String(first)); assertEquals(HistoryMoveResult.Moved(base), repo().moveAside())
        put(String(second)); assertEquals("$base-2", repo().asideFileName())
        assertEquals(HistoryMoveResult.Moved("$base-2"), repo().moveAside())
        put(String(third)); assertEquals(HistoryMoveResult.Moved("$base-3"), repo().moveAside())

        assertTrue(first.contentEquals(Files.readAllBytes(file.resolveSibling(base))))
        assertTrue(second.contentEquals(Files.readAllBytes(file.resolveSibling("$base-2"))))
        assertTrue(third.contentEquals(Files.readAllBytes(file.resolveSibling("$base-3"))))
        assertEquals(setOf(base, "$base-2", "$base-3"), names(), "three kept files, nothing deleted")
    }

    @Test
    fun `a failing move keeps the file where it was and says so`() = runBlocking {
        put("{ broken")
        val before = bytes()

        val result = repo(crash).moveAside()

        assertIs<HistoryMoveResult.Failed>(result)
        assertTrue(before.contentEquals(bytes()))
        assertEquals(setOf("migration-history.json"), names())
    }

    @Test
    fun `moveAside with no file moves nothing and fails without creating files`() = runBlocking {
        Files.createDirectories(file.parent)
        val result = runCatching { repo().moveAside() }

        assertTrue(result.getOrNull() is HistoryMoveResult.Failed || result.isFailure)
        assertTrue(names().isEmpty())
    }
}
