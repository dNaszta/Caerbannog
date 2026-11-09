package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.MAX_DEFINITIONS_FILE_BYTES
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** M8-01: loads a definitions file as text, size checked before reading, read only. */
class JvmDefinitionsFileLoaderTest {
    @TempDir
    lateinit var dir: Path

    private fun load(path: String, loader: JvmDefinitionsFileLoader = JvmDefinitionsFileLoader()): FileLoadResult = runBlocking { loader.load(path) }

    private fun file(name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }

    @Test
    fun `the limit defaults to 64 MiB`() = assertEquals(64L * 1024 * 1024, MAX_DEFINITIONS_FILE_BYTES)

    @Test
    fun `a normal file gives its exact text`() {
        val text = """{"queues":[{"name":"café 日本","vhost":"/"}]}""" + "\r\n"
        val path = file("d.json", text.toByteArray(Charsets.UTF_8))
        assertEquals(FileLoadResult.Loaded(text), load(path.toString()))
    }

    @Test
    fun `an empty file gives empty text`() {
        assertEquals(FileLoadResult.Loaded(""), load(file("empty.json", ByteArray(0)).toString()))
    }

    @Test
    fun `a leading byte order mark is stripped`() {
        val path = file("bom.json", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray())
        assertEquals(FileLoadResult.Loaded("{}"), load(path.toString()))
    }

    @Test
    fun `a missing file is unreadable`() {
        val result = load(dir.resolve("nope.json").toString())
        assertTrue(result is FileLoadResult.Unreadable, "got $result")
    }

    @Test
    fun `a directory is unreadable`() {
        val result = load(dir.toString())
        assertTrue(result is FileLoadResult.Unreadable, "got $result")
    }

    @Test
    fun `an invalid path is unreadable and does not throw`() {
        assertTrue(load("a\u0000b") is FileLoadResult.Unreadable)
        assertTrue(load("") is FileLoadResult.Unreadable)
    }

    @Test
    fun `a file without read permission is unreadable`() {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix") && System.getProperty("user.name") != "root")
        val path = file("locked.json", "{}".toByteArray())
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"))
        try {
            assertTrue(load(path.toString()) is FileLoadResult.Unreadable)
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a file at the limit is loaded and one byte over is too large`() {
        val loader = JvmDefinitionsFileLoader(maxBytes = 10)
        assertEquals(FileLoadResult.Loaded("0123456789"), load(file("at.json", "0123456789".toByteArray()).toString(), loader))
        assertEquals(FileLoadResult.TooLarge(bytes = 11, limit = 10), load(file("over.json", "0123456789X".toByteArray()).toString(), loader))
    }

    @Test
    fun `size is checked before reading, so an oversized file with invalid UTF-8 is still too large`() {
        // If the loader read the file first, it would answer Unreadable (malformed UTF-8) instead of TooLarge.
        val path = file("big-bad.json", ByteArray(50) { 0xFF.toByte() })
        assertEquals(FileLoadResult.TooLarge(bytes = 50, limit = 10), load(path.toString(), JvmDefinitionsFileLoader(maxBytes = 10)))
    }

    @Test
    fun `a sparse file one byte over the default limit is too large without being read`() {
        val path = dir.resolve("sparse.json")
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(MAX_DEFINITIONS_FILE_BYTES + 1) }
        assertEquals(FileLoadResult.TooLarge(MAX_DEFINITIONS_FILE_BYTES + 1, MAX_DEFINITIONS_FILE_BYTES), load(path.toString()))
    }

    @Test
    fun `invalid UTF-8 is unreadable and the reason holds no file content`() {
        val secret = "SENTINEL-FILE-CONTENT"
        val path = file("bad.json", secret.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28))
        val result = load(path.toString())
        assertTrue(result is FileLoadResult.Unreadable, "got $result")
        val reason = (result as FileLoadResult.Unreadable).reason
        assertTrue(reason.isNotBlank())
        assertFalse(reason.contains(secret), reason)
        assertFalse(reason.contains("SENTINEL"), reason)
    }

    @Test
    fun `loading never changes the file`() {
        val bytes = "{}".toByteArray()
        val path = file("ro.json", bytes)
        val before = Files.getLastModifiedTime(path)
        load(path.toString())
        assertEquals(before, Files.getLastModifiedTime(path))
        assertTrue(bytes.contentEquals(Files.readAllBytes(path)))
        assertEquals(listOf("ro.json"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
