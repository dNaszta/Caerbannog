package dev.caerbannog.core.migrate3x

/** The largest definitions file the app reads. */
const val MAX_DEFINITIONS_FILE_BYTES: Long = 64L * 1024 * 1024

sealed interface FileLoadResult {
    data class Loaded(val text: String) : FileLoadResult

    /** [reason] never includes file content. */
    data class Unreadable(val reason: String) : FileLoadResult
    data class TooLarge(val bytes: Long, val limit: Long) : FileLoadResult
}

/** Loads a definitions file as text. Read only. */
fun interface DefinitionsFileLoader {
    suspend fun load(path: String): FileLoadResult
}
