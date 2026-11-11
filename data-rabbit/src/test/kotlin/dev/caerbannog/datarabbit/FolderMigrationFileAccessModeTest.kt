package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-12b (P13): a migration file that holds a password hash is written readable by its owner only (mode 600); every other file keeps the process default. */
class FolderMigrationFileAccessModeTest {
    @TempDir
    lateinit var root: File

    private val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private fun folder(): Path = Files.createDirectories(root.toPath().resolve("migrations"))
    private fun mode(p: Path) = Files.getPosixFilePermissions(p)
    private fun posix() = assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here")

    private fun accessFile(version: String = "0001") =
        """{ "version": "$version", "description": "access", "up": [ { "action": "declareUser", "name": "svc", "passwordHash": "SENTINEL-HASH", "hashingAlgorithm": "rabbit_password_hashing_sha256", "tags": [] } ] }"""

    private fun topologyFile(version: String = "0002") = """{ "version": "$version", "description": "t", "up": [ { "action": "declareQueue", "name": "q" } ] }"""

    @Test
    fun `an imported file with a declareUser is mode 600`() {
        posix()
        val dir = folder()
        val result = runBlocking { FolderMigrationFileRepository(dir, NioFileMover).importFile(accessFile()) }
        assertTrue(result is MigrationValidationResult.Valid, "$result")
        assertEquals(ownerOnly, mode(dir.resolve("0001.json")))
    }

    @Test
    fun `an updated file with a declareUser is mode 600`() {
        posix()
        val dir = folder()
        val repo = FolderMigrationFileRepository(dir, NioFileMover)
        runBlocking { repo.importFile(accessFile()) }
        val updated = runBlocking { repo.updateFile("0001", accessFile().replace("\"tags\": []", "\"tags\": [\"management\"]")) }
        assertTrue(updated is MigrationValidationResult.Valid, "$updated")
        assertEquals(ownerOnly, mode(dir.resolve("0001.json")))
    }

    @Test
    fun `an exported topology that holds a declareUser is mode 600`() {
        posix()
        val dir = folder()
        runBlocking {
            FolderMigrationFileRepository(dir, NioFileMover).exportTopology("0001", "access", listOf(MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH"), "rabbit_password_hashing_sha256")))
        }
        assertEquals(ownerOnly, mode(dir.resolve("0001.json")))
    }

    @Test
    fun `a topology file keeps the default mode of the process, not owner only`() {
        posix()
        val dir = folder()
        val plain = Files.createFile(root.toPath().resolve("plain.txt"))
        runBlocking { FolderMigrationFileRepository(dir, NioFileMover).importFile(topologyFile()) }
        assertEquals(mode(plain), mode(dir.resolve("0002.json")), "a file without a hash must not be restricted")
        assumeTrue(mode(plain) != ownerOnly, "the umask of this machine is already 077")
    }
}
