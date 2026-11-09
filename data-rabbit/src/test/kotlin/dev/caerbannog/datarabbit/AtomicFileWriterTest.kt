package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5-08b round 1 (permissions): `ownerOnly = true` (the app's own history and settings) gives mode 600; `ownerOnly = false` (migration files in
 * the user's folder) gives the process default, the same as a file created by `Files.createFile` beside it. Skipped where the file system has no POSIX permissions.
 */
class AtomicFileWriterTest {

    @TempDir
    lateinit var dir: Path

    private fun posix() = assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here")
    private fun mode(p: Path): Set<PosixFilePermission> = Files.getPosixFilePermissions(p)
    private val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    @Test
    fun `ownerOnly true writes mode 600`() {
        posix()
        val target = dir.resolve("a.json")

        AtomicFileWriter.write(target, "x".toByteArray(), NioFileMover, ownerOnly = true)

        assertEquals(ownerOnly, mode(target))
    }

    @Test
    fun `ownerOnly false writes the default mode of the process, like a control file created beside it`() {
        posix()
        val control = Files.createFile(dir.resolve("control"))
        assumeTrue(mode(control) != ownerOnly, "the process umask already makes new files 600; the check cannot tell the modes apart")
        val target = dir.resolve("a.json")

        AtomicFileWriter.write(target, "x".toByteArray(), NioFileMover, ownerOnly = false)

        assertEquals(mode(control), mode(target), "a file in the user's folder must get the default permissions, not owner-only")
    }

    @Test
    fun `replacing an existing file also gives the requested mode and keeps content exact`() {
        posix()
        val control = Files.createFile(dir.resolve("control"))
        assumeTrue(mode(control) != ownerOnly, "umask makes files 600")
        val target = dir.resolve("a.json")
        AtomicFileWriter.write(target, "old".toByteArray(), NioFileMover, ownerOnly = true)

        AtomicFileWriter.write(target, "new é\r\n".toByteArray(Charsets.UTF_8), NioFileMover, ownerOnly = false)

        assertEquals("new é\r\n", String(Files.readAllBytes(target), Charsets.UTF_8))
        assertEquals(mode(control), mode(target))
    }

    @Test
    fun `either mode leaves no temp file after a failed move`() {
        for (flag in listOf(true, false)) {
            val result = runCatching { AtomicFileWriter.write(dir.resolve("b.json"), "x".toByteArray(), { _, _ -> throw IOException("no") }, ownerOnly = flag) }
            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
        }
    }
}
