package dev.caerbannog.core.migrations

internal actual fun Throwable.isIoFailure(): Boolean = this is java.io.IOException
