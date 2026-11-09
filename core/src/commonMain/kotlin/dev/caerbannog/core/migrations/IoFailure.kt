package dev.caerbannog.core.migrations

/** True for a failure of the disk or the file system (`java.io.IOException` on the JVM); common code has no such type of its own. */
internal expect fun Throwable.isIoFailure(): Boolean
