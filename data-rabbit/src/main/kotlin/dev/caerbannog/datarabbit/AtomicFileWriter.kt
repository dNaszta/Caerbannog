package dev.caerbannog.datarabbit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a file so a reader never sees half of it: the bytes go to a temp file beside [write]'s target, then [FileMover] moves it into
 * place. The temp name ends in `.tmp` (never `.json`, so a leftover is not mistaken for a migration) and the temp file is removed when the
 * write or the move fails. The parent folder must exist: this never creates folders.
 */
internal object AtomicFileWriter {
    /** @throws IOException when the temp file cannot be written or moved; [target] is then exactly as it was. */
    fun write(target: Path, bytes: ByteArray, mover: FileMover) {
        val parent = target.toAbsolutePath().parent ?: throw IOException("no parent folder")
        val temp = Files.createTempFile(parent, ".caerbannog-", ".tmp")
        try {
            Files.write(temp, bytes)
            mover.move(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
