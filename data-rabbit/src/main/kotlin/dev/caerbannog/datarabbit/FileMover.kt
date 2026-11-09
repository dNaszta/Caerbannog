package dev.caerbannog.datarabbit

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The one place a file is moved into place; a test injects a failing one to simulate a crash between the temp file and the move. */
fun interface FileMover {
    /** Moves [source] over [target] atomically (falling back only where the file system cannot). Throws an `IOException` on failure. */
    fun move(source: Path, target: Path)
}

/** [FileMover] on `java.nio.file`: an atomic move, and a plain replacing move only where the file system reports it cannot be atomic. */
object NioFileMover : FileMover {
    override fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
