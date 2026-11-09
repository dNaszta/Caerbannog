package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.FolderListing
import dev.caerbannog.core.migrations.HistoryMoveResult
import dev.caerbannog.core.migrations.HistoryResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFileRepository
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.migrations.ProfileHistoryRenamer
import java.nio.file.Path
import java.time.ZoneId

// M5-08b STUB FILE (test-first compile stubs, written by the test developer). The senior developer implements these classes in their own
// files and DELETES this file; M508bStubGuardTest (data-rabbit) stays red until then. Nothing here is behaviour.

/** The one place a file is moved into place; a test injects a failing one to simulate a crash between the temp file and the move. */
fun interface FileMover {
    /** Moves [source] over [target] atomically (falling back only where the file system cannot). Throws on failure. */
    fun move(source: Path, target: Path)
}

object NioFileMover : FileMover {
    override fun move(source: Path, target: Path): Unit = throw NotImplementedError("M5-08b STUB")
}

/** `<version>.json` files holding exactly the raw text, in [folder] (the user's own folder: never created, never otherwise written). */
class FolderMigrationFileRepository(
    private val folder: Path,
    private val mover: FileMover = NioFileMover,
) : MigrationFileRepository {
    override suspend fun listFiles(): List<MigrationFile> = emptyList()
    override suspend fun load(): FolderListing = FolderListing.Listed(emptyList())
    override suspend fun importFile(rawJson: String): MigrationValidationResult = MigrationValidationResult.Invalid("M5-08b STUB")
    override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile =
        throw NotImplementedError("M5-08b STUB")
    override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult =
        MigrationValidationResult.Invalid("M5-08b STUB")
    override suspend fun nextVersion(): String = "0000"
}

/** `migration-history.json` ({"schema":1,"profiles":{"<profile>":{"<version>":"<checksum>"}}}), written atomically, never while unreadable. */
class JsonFileMigrationHistoryRepository(
    private val file: Path,
    private val now: NowProvider,
    private val mover: FileMover = NioFileMover,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : MigrationHistoryRepository, ProfileHistoryRenamer {
    override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = emptyMap()
    override suspend fun recordApplied(profileName: String, version: String, checksum: String) = Unit
    override suspend fun load(profileName: String): HistoryResult = HistoryResult.Loaded(emptyMap())
    override fun asideFileName(): String = throw NotImplementedError("M5-08b STUB")
    override suspend fun moveAside(): HistoryMoveResult = HistoryMoveResult.Failed("M5-08b STUB")
    override fun rename(oldName: String, newName: String) = Unit
}
