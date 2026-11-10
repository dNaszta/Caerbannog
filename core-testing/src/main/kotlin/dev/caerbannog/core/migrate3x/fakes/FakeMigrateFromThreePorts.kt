package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.migrate3x.DefinitionsFileChooser
import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.ReportFileSaver
import dev.caerbannog.core.migrate3x.SaveOutcome
import kotlinx.coroutines.CompletableDeferred

/** M8-06a: the native file dialog. [next] is what the user picks (null = cancelled); [calls] counts the times it was opened. */
class FakeDefinitionsFileChooser(var next: String? = null) : DefinitionsFileChooser {
    var calls: Int = 0
        private set

    override suspend fun choose(): String? {
        calls++
        return next
    }
}

/** M8-06a: the native save dialog. Records what it was asked to write; [outcome] is what the dialog and the disk answer. */
class FakeReportFileSaver(var outcome: SaveOutcome = SaveOutcome.Saved("/tmp/saved.json")) : ReportFileSaver {
    data class Save(val suggestedName: String, val text: String)

    val saves: MutableList<Save> = mutableListOf()

    override suspend fun save(suggestedName: String, text: String): SaveOutcome {
        saves += Save(suggestedName, text)
        return outcome
    }
}

/** M8-06a: a loader that waits until the test calls [finish], so a state in between (Loading, cancel, a second file) can be observed. */
class SuspendingDefinitionsFileLoader : DefinitionsFileLoader {
    private val waiting = mutableMapOf<String, MutableList<CompletableDeferred<FileLoadResult>>>()
    val requestedPaths: MutableList<String> = mutableListOf()

    override suspend fun load(path: String): FileLoadResult {
        requestedPaths += path
        val gate = CompletableDeferred<FileLoadResult>()
        waiting.getOrPut(path) { mutableListOf() } += gate
        return gate.await()
    }

    /** Answers every load of [path] that is waiting. */
    fun finish(path: String, result: FileLoadResult) {
        waiting.remove(path).orEmpty().forEach { it.complete(result) }
    }
}
