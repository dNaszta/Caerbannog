package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationFolderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * M5-08b test double of the folder dialog: answers from a script (a null answer is "the user cancelled"), and records every [startIn] it
 * was given, so a test can show the chooser only ever receives a folder path. With an empty script it cancels.
 */
class FakeMigrationFolderChooser(vararg answers: String?) : MigrationFolderChooser {
    private val script = ArrayDeque(answers.toList())

    /** The `startIn` of every call, in order. */
    val startedIn = mutableListOf<String?>()

    fun answerNext(path: String?) {
        script.addLast(path)
    }

    override suspend fun choose(startIn: String?): String? {
        startedIn += startIn
        return script.removeFirstOrNull()
    }
}

/** M5-08b in-memory [MigrationFolderStore]; a fresh one is "no folder chosen on this machine". */
class InMemoryMigrationFolderStore(initial: String? = null) : MigrationFolderStore {
    private val signal = MutableSharedFlow<String?>(extraBufferCapacity = 8)

    override var folder: String? = initial
        set(value) {
            field = value
            signal.tryEmit(value)
        }

    override val changes: Flow<String?> get() = signal
}
