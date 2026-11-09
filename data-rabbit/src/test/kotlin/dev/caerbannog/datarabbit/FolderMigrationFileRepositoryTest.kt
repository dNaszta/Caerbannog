package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.FolderListing
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.InvalidMigrationFile
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationWriteException
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08b: the real folder-backed repository on a JUnit temp folder. The user's folder is never created, a file is only written for an
 * import, an edit or an export, and a file that is not valid is never changed. Folders that cannot be made unreadable or read-only on this
 * machine (running as root, a file system without permissions) skip those tests; the skip is a recorded gap, see the task Status.
 */
class FolderMigrationFileRepositoryTest {

    @TempDir
    lateinit var root: File

    private val folder: Path get() = root.toPath().resolve("work/migrations")
    private val toRestore = mutableListOf<File>()

    @AfterEach
    fun restorePermissions() {
        toRestore.forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
    }

    private fun made(): Path = Files.createDirectories(folder)
    private fun repo(mover: FileMover = NioFileMover) = FolderMigrationFileRepository(folder, mover)

    private fun raw(version: String = "0001", extra: String = "") =
        """{ "version": "$version", "description": "d", "up": [ { "action": "declareQueue", "name": "q$version" } ]$extra }"""

    /** Every file and folder below [start] with its bytes: what "nothing was written" compares. */
    private fun tree(start: Path = root.toPath()): Map<String, String> =
        Files.walk(start).use { s ->
            s.toList().associate { p ->
                start.relativize(p).toString() to if (Files.isRegularFile(p)) Files.readAllBytes(p).joinToString(",") else "<dir>"
            }
        }

    private fun fileText(name: String) = String(Files.readAllBytes(folder.resolve(name)), Charsets.UTF_8)

    private fun write(name: String, text: String) = Files.write(folder.resolve(name), text.toByteArray(Charsets.UTF_8))

    private fun listed(): FolderListing.Listed = assertIs(runBlocking { repo().load() })

    // ---- raw text, restart, checksum ----

    @Test
    fun `an import writes exactly the raw text to version dot json, byte for byte`() = runBlocking {
        made()
        val text = "{\r\n\t\"version\": \"0001\",  \"description\": \"Café – größer\",\r\n  \"up\": [ { \"action\": \"declareQueue\", \"name\": \"q\" } ]\r\n}   "

        val result = repo().importFile(text)

        assertIs<MigrationValidationResult.Valid>(result)
        assertTrue(text.toByteArray(Charsets.UTF_8).contentEquals(Files.readAllBytes(folder.resolve("0001.json"))), "the file must hold the imported text untouched, not a normalised JSON")
        assertEquals(listOf("0001.json"), Files.list(folder).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `the listed raw text and the checksum are those of the file and stay the same after a restart`() = runBlocking {
        made()
        val text = raw("0001", extra = ",\n \"x-note\": \"ä\"")
        repo().importFile(text)

        val first = repo().listFiles().single()
        val afterRestart = FolderMigrationFileRepository(folder).listFiles().single()

        assertEquals(text, first.rawJson)
        assertEquals(MigrationParser.computeChecksum(text), first.checksum)
        assertEquals(first.checksum, afterRestart.checksum)
        assertEquals(first.rawJson, afterRestart.rawJson)
    }

    @Test
    fun `listing never rewrites a file`() = runBlocking {
        made()
        write("0001.json", raw("0001").replace(" ", "   "))
        val before = tree()

        repo().listFiles()
        repo().load()

        assertEquals(before, tree())
    }

    @Test
    fun `files are listed in version order`() = runBlocking {
        made()
        write("0010.json", raw("0010")); write("0002.json", raw("0002")); write("0001.json", raw("0001"))

        assertEquals(listOf("0001", "0002", "0010"), repo().listFiles().map { it.version })
    }

    @Test
    fun `a numeric version of an older file is stored under its whole number`() = runBlocking {
        made()
        val text = """{ "version": 3, "description": "old", "up": [ { "action": "declareQueue", "name": "q" } ] }"""

        assertIs<MigrationValidationResult.Valid>(repo().importFile(text))

        assertEquals(text, fileText("3.json"))
    }

    // ---- duplicates, update, export ----

    @Test
    fun `a version that already exists is refused and the old file is untouched`() = runBlocking {
        made()
        repo().importFile(raw("0001"))
        val before = tree()

        val result = repo().importFile(raw("0001", extra = ",\"description2\":\"other content\""))

        assertIs<MigrationValidationResult.Invalid>(result)
        assertEquals(before, tree())
    }

    @Test
    fun `an invalid import writes nothing`() = runBlocking<Unit> {
        made()
        val before = tree()

        assertIs<MigrationValidationResult.Invalid>(repo().importFile("not json {"))
        assertIs<MigrationValidationResult.Invalid>(repo().importFile("""{ "description": "no version", "up": [] }"""))

        assertEquals(before, tree())
    }

    @Test
    fun `updateFile replaces the stored text exactly, and an invalid edit keeps the old file`() = runBlocking<Unit> {
        made()
        repo().importFile(raw("0001"))
        val edited = raw("0001", extra = ",\n  \"note\": \"edited\"")

        assertIs<MigrationValidationResult.Valid>(repo().updateFile("0001", edited))
        assertEquals(edited, fileText("0001.json"))

        val before = tree()
        assertIs<MigrationValidationResult.Invalid>(repo().updateFile("0001", "{ broken"))
        assertEquals(before, tree(), "a refused edit must leave the file as it was")
    }

    @Test
    fun `export writes the exported text as the new version file`() = runBlocking {
        made()
        val actions = listOf<MigrationAction>(MigrationAction.DeclareQueue(name = "orders", type = "quorum"))

        val exported = repo().exportTopology("0001", "Exported broker topology", actions)

        assertEquals(MigrationParser.exportToJson("0001", "Exported broker topology", actions), fileText("0001.json"))
        assertEquals(exported.rawJson, fileText("0001.json"))
        assertEquals(listOf("0001"), repo().listFiles().map { it.version })
    }

    // ---- nextVersion ----

    @Test
    fun `nextVersion reads the folder`() = runBlocking {
        made()
        assertEquals("0001", repo().nextVersion())
        write("0001.json", raw("0001")); write("0002.json", raw("0002")); write("0010.json", raw("0010")); write("notes-v1.json", raw("notes-v1"))

        assertEquals("0011", repo().nextVersion())
    }

    @Test
    fun `nextVersion skips a name taken by an invalid file so an export never overwrites it`() = runBlocking<Unit> {
        made()
        write("0001.json", raw("0001")); write("0007.json", "{ not json")

        assertEquals("0008", repo().nextVersion())
    }

    // ---- invalid files ----

    @Test
    fun `an invalid file is listed as invalid with a reason, is not a migration and stays unchanged`() = runBlocking<Unit> {
        made()
        write("0001.json", raw("0001"))
        write("0007.json", "{ not json SENTINEL")
        write("0008.json", """{ "description": "no version", "up": [] }""")
        val before = tree()

        val l = listed()

        assertEquals(listOf("0001"), l.files.map { it.version })
        assertEquals(setOf("0007.json", "0008.json"), l.invalid.map { it.fileName }.toSet())
        l.invalid.forEach {
            assertTrue(it.reason.isNotBlank())
            assertFalse(it.reason.contains("SENTINEL"), "a reason must not carry file content: ${it.reason}")
        }
        assertEquals(before, tree())
    }

    @Test
    fun `a file nested ten thousand levels deep is invalid with the nesting reason and does not crash`() = runBlocking {
        made()
        write("0009.json", DeepJson.arrays())
        write("0010.json", """{ "version": "0010", "up": [ { "action": "declareQueue", "name": ${DeepJson.arrays()} } ] }""")

        val l = listed()

        assertTrue(l.files.isEmpty())
        assertEquals(
            listOf(InvalidMigrationFile("0009.json", "the file is nested too deep"), InvalidMigrationFile("0010.json", "the file is nested too deep")),
            l.invalid.sortedBy { it.fileName },
        )
    }

    @Test
    fun `files that are not json files, sub folders and a leftover temp file are ignored`() = runBlocking {
        made()
        write("0001.json", raw("0001"))
        write("README.txt", "hello"); write("0002.json.tmp", "half a fi"); write("settings.tmp", "x")
        Files.createDirectories(folder.resolve("0003.json")) // a folder with a json name
        Files.createDirectories(folder.resolve("archive"))
        write("archive/0004.json", raw("0004"))

        val l = listed()

        assertEquals(listOf("0001"), l.files.map { it.version })
        assertTrue(l.invalid.isEmpty(), "only *.json files can be invalid migrations: ${l.invalid}")
    }

    // ---- path escape ----

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"../0001\"", "\"a/b\"", "\"a\\\\b\"", "\"0001.json\"", "NaN", "1e3", "\"..\"", "\".\"", "\"a\\u0000b\"", "\"C:\\\\x\"",
            "\"/etc/x\"", "\"..\\\\..\\\\x\"", "\"\"", "\" \"",
        ],
    )
    fun `a version that could leave the folder is refused and nothing is written anywhere`(versionJson: String) = runBlocking {
        made()
        val before = tree()
        val text = """{ "version": $versionJson, "description": "d", "up": [ { "action": "declareQueue", "name": "q" } ] }"""

        val result = repo().importFile(text)

        assertIs<MigrationValidationResult.Invalid>(result, "version $versionJson must be refused")
        assertEquals(before, tree(), "nothing may be written for version $versionJson")
    }

    @Test
    fun `updateFile never takes a path from its version argument`() = runBlocking {
        made()
        repo().importFile(raw("0001"))
        val before = tree()

        for (bad in listOf("../0001", "a/b", "..", "0001.json", "")) {
            val r = runCatching { repo().updateFile(bad, raw("0002")) }
            assertTrue(r.isFailure || r.getOrNull() is MigrationValidationResult.Invalid, "version argument '$bad' must not be accepted")
        }
        assertEquals(before, tree())
    }

    // ---- the folder itself ----

    @Test
    fun `a missing folder is reported with its path, nothing is created and an import is refused`() = runBlocking {
        val before = tree()

        val l = repo().load()

        assertEquals(FolderListing.Unavailable(folder.toString(), FolderProblem.MISSING), l)
        assertTrue(repo().listFiles().isEmpty())
        assertIs<MigrationValidationResult.Invalid>(repo().importFile(raw("0001")))
        repo().nextVersion()
        assertEquals(before, tree())
        assertFalse(Files.exists(folder), "the user's folder must never be created by the app")
        assertFalse(Files.exists(folder.parent), "no parent of the user's folder may be created either")
    }

    @Test
    fun `a path that is a file is not a folder and is left alone`() = runBlocking {
        Files.createDirectories(folder.parent)
        Files.write(folder, "I am a file".toByteArray())
        val before = tree()

        assertEquals(FolderListing.Unavailable(folder.toString(), FolderProblem.NOT_A_FOLDER), repo().load())
        assertIs<MigrationValidationResult.Invalid>(repo().importFile(raw("0001")))
        assertEquals(before, tree())
    }

    @Test
    fun `a folder that cannot be read is reported and nothing is written`() = runBlocking {
        val dir = made().toFile()
        write("0001.json", raw("0001"))
        dir.setReadable(false); dir.setExecutable(false); toRestore += dir
        assumeTrue(!Files.isReadable(dir.toPath()), "cannot make a folder unreadable here (running as root or no permission bits)")

        assertEquals(FolderListing.Unavailable(folder.toString(), FolderProblem.UNREADABLE), repo().load())
        assertIs<MigrationValidationResult.Invalid>(repo().importFile(raw("0002")))

        dir.setReadable(true); dir.setExecutable(true)
        assertEquals(listOf("0001.json"), Files.list(folder).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a read-only folder lists its files but refuses import and edit and keeps every file`() = runBlocking {
        val dir = made().toFile()
        write("0001.json", raw("0001"))
        dir.setWritable(false); toRestore += dir
        assumeTrue(!Files.isWritable(dir.toPath()), "cannot make a folder read-only here (running as root or no permission bits)")

        val l = repo().load()
        assertIs<FolderListing.Listed>(l)
        assertTrue(l.readOnly)
        assertEquals(listOf("0001"), l.files.map { it.version })
        assertIs<MigrationValidationResult.Invalid>(repo().importFile(raw("0002")))
        assertIs<MigrationValidationResult.Invalid>(repo().updateFile("0001", raw("0001", extra = ",\"n\":1")))

        dir.setWritable(true)
        assertEquals(raw("0001"), fileText("0001.json"))
        assertEquals(listOf("0001.json"), Files.list(folder).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a writable folder is not reported read-only`() {
        made()
        assertFalse(listed().readOnly)
    }

    // ---- atomic write ----

    private class RecordingMover : FileMover {
        val calls = mutableListOf<Pair<Path, Path>>()
        override fun move(source: Path, target: Path) {
            calls += source to target
            NioFileMover.move(source, target)
        }
    }

    private val crash = FileMover { _, _ -> throw IOException("simulated crash between the temp file and the move") }

    @Test
    fun `a new file is written to a temp file in the same folder and moved into place`() = runBlocking {
        made()
        val mover = RecordingMover()

        repo(mover).importFile(raw("0001"))

        val (source, target) = mover.calls.single()
        assertEquals(folder.resolve("0001.json"), target)
        assertEquals(folder, source.parent, "the temp file must be beside the target so the move is atomic")
        assertFalse(source.fileName.toString().endsWith(".json"), "a leftover temp file must not look like a migration: $source")
    }

    @Test
    fun `a crash before the move leaves no new file and no temp file behind`() = runBlocking {
        made()
        write("0001.json", raw("0001"))
        val before = tree()

        val result = repo(crash).importFile(raw("0002"))

        assertEquals(MigrationValidationResult.Invalid("the file could not be written"), result, "a failed write is a refusal, not a crash of the screen")
        assertEquals(before, tree(), "after a failed write the folder must be exactly as before")
    }

    @Test
    fun `a crash during an edit leaves the old file intact`() = runBlocking {
        made()
        write("0001.json", raw("0001"))
        val before = tree()

        val result = repo(crash).updateFile("0001", raw("0001", extra = ",\"n\":1"))

        assertEquals(MigrationValidationResult.Invalid("the file could not be written"), result)
        assertEquals(before, tree())
        assertEquals(raw("0001"), fileText("0001.json"))
    }

    @Test
    fun `a crash during an export writes no partial file and says so`() = runBlocking {
        made()
        val before = tree()

        val result = runCatching { repo(crash).exportTopology("0001", "d", listOf(MigrationAction.DeclareQueue(name = "q"))) }

        val failure = result.exceptionOrNull()
        assertIs<MigrationWriteException>(failure, "an export that could not be written must throw MigrationWriteException, got $result")
        assertFalse(failure.reason.contains("simulated") || failure.reason.contains(root.toString()), "the reason is fixed text: ${failure.reason}")
        assertEquals(before, tree())
    }

    @Test
    fun `a leftover temp file from an earlier crash does not block or change anything`() = runBlocking<Unit> {
        made()
        write("0001.json", raw("0001"))
        write("0001.json.tmp", "{ half")
        write("0002.json.tmp", "{ half")

        assertIs<MigrationValidationResult.Valid>(repo().importFile(raw("0002")))

        assertEquals(listOf("0001", "0002"), repo().listFiles().map { it.version })
        assertEquals("{ half", fileText("0001.json.tmp"))
    }
}
