package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.MAX_DEFINITIONS_FILE_BYTES

// M8-01 STEP 2 COMPILE STUB (test developer). NOT PRODUCTION CODE. The senior developer replaces this file with
// JvmDefinitionsFileLoader.kt (brief: UTF-8, BOM stripped, size check before reading, Dispatchers.IO, never writes).
class JvmDefinitionsFileLoader(val maxBytes: Long = MAX_DEFINITIONS_FILE_BYTES) : DefinitionsFileLoader {
    override suspend fun load(path: String): FileLoadResult = TODO("M8-01: the loader")
}
