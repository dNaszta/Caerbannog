package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.FileLoadResult

/** M8-01 test double for [DefinitionsFileLoader]: a path maps to a canned result; an unknown path is `Unreadable` like a missing file. */
class FakeDefinitionsFileLoader(private val files: Map<String, FileLoadResult> = emptyMap()) : DefinitionsFileLoader {
    val requestedPaths: MutableList<String> = mutableListOf()

    override suspend fun load(path: String): FileLoadResult {
        requestedPaths += path
        return files[path] ?: FileLoadResult.Unreadable("The file cannot be read")
    }
}
