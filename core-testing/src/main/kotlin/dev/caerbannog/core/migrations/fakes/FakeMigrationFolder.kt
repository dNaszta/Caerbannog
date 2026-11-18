package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.migrations.FOLDER_READ_ONLY_MESSAGE
import dev.caerbannog.core.migrations.FolderListing
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.InvalidMigrationFile
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFileRepository
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationWriteException

/**
 * M8-06b: a migrations folder in memory that behaves like `FolderMigrationFileRepository`: a version that exists is refused
 * ("that version already exists") and never overwritten, a read-only folder refuses every write, an unavailable folder cannot be listed,
 * an invalid file (for example `0007.json` that is not a migration) is listed apart. Failure injection for the second file of a save:
 * [failWrite] (by 1-based number of the write attempt, or by version) answers a fixed reason and writes nothing.
 * Every write attempt is recorded in [attempts], so a test shows what the folder was asked to do.
 */
class FakeMigrationFolder(
    val path: String = "/migrations",
    initialVersions: List<String> = emptyList(),
) : MigrationFileRepository {
    val files: MutableMap<String, MigrationFile> = linkedMapOf()
    val invalid: MutableList<InvalidMigrationFile> = mutableListOf()
    var readOnly: Boolean = false
    var unavailable: FolderProblem? = null

    /** The raw JSON of every write attempt, in order, whatever its result. */
    val attempts: MutableList<String> = mutableListOf()

    /** Versions written, in order. */
    val written: MutableList<String> = mutableListOf()

    private val failing = mutableMapOf<Int, String>()
    private val failingVersions = mutableMapOf<String, String>()

    /** Called just before a write is checked, with the 1-based attempt number: a test takes a version "meanwhile" here. */
    var beforeWrite: suspend (attempt: Int) -> Unit = {}

    init {
        initialVersions.forEach { addMigration(it) }
    }

    /** A valid migration `declareQueue q-<version>` that was already in the folder. */
    fun addMigration(version: String, vhost: String = "/") {
        val raw = MigrationParser.exportToJson(version, "existing $version", listOf(MigrationAction.DeclareQueue("q-$version", "quorum", vhost)))
        files[version] = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
    }

    fun addInvalidFile(fileName: String, reason: String = "the file is not valid JSON") {
        invalid += InvalidMigrationFile(fileName, reason)
    }

    /** The [attempt]th write (1-based) fails with [reason] and writes nothing. */
    fun failWrite(attempt: Int, reason: String = "the file could not be written") {
        failing[attempt] = reason
    }

    fun failVersion(version: String, reason: String = "the file could not be written") {
        failingVersions[version] = reason
    }

    override suspend fun listFiles(): List<MigrationFile> = files.values.sortedBy { it.version }

    override suspend fun load(): FolderListing =
        unavailable?.let { FolderListing.Unavailable(path, it) }
            ?: FolderListing.Listed(listFiles(), invalid.toList(), readOnly)

    override suspend fun importFile(rawJson: String): MigrationValidationResult {
        attempts += rawJson
        beforeWrite(attempts.size)
        if (readOnly) return MigrationValidationResult.Invalid(FOLDER_READ_ONLY_MESSAGE)
        failing[attempts.size]?.let { return MigrationValidationResult.Invalid(it) }
        val result = MigrationParser.parse(rawJson)
        if (result !is MigrationValidationResult.Valid) return result
        failingVersions[result.migration.version]?.let { return MigrationValidationResult.Invalid(it) }
        if (result.migration.version in files) return MigrationValidationResult.Invalid("that version already exists")
        files[result.migration.version] = result.migration
        written += result.migration.version
        return result
    }

    /**
     * M9-02: off by default, so the generate flow of M8 still fails loudly if it uses [exportTopology]. The planner turns it on ([allowExport]):
     * it then behaves like `FolderMigrationFileRepository.exportTopology`: a read-only or unavailable folder, a failing write and a version that
     * exists all throw [MigrationWriteException] with nothing written (a file is never overwritten); a write is recorded in [attempts] and [written].
     */
    var exportAllowed: Boolean = false

    fun allowExport(): FakeMigrationFolder = apply { exportAllowed = true }

    override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile {
        if (!exportAllowed) throw MigrationWriteException("the generate flow must not use exportTopology: it must save what `generate` returned")
        val rawJson = MigrationParser.exportToJson(version, description, actions)
        attempts += rawJson
        beforeWrite(attempts.size)
        unavailable?.let { throw MigrationWriteException(it.message) }
        if (readOnly) throw MigrationWriteException(FOLDER_READ_ONLY_MESSAGE)
        failing[attempts.size]?.let { throw MigrationWriteException(it) }
        failingVersions[version]?.let { throw MigrationWriteException(it) }
        if (version in files) throw MigrationWriteException("that version already exists")
        val file = (MigrationParser.parse(rawJson) as? MigrationValidationResult.Valid)?.migration
            ?: throw MigrationWriteException("the file could not be written")
        files[version] = file
        written += version
        return file
    }

    /**
     * M9-12: every [updateFile] attempt (version to the raw JSON it was given), in order, whatever its result. "Nothing was rewritten" is `updates.isEmpty()`.
     * Honest like `FolderMigrationFileRepository.updateFile`: a read-only or unavailable folder, a file that is not there, text that does not parse and a
     * version that differs from the file's all answer `Invalid` and write nothing; the file then holds the text as given (its bytes are the text).
     */
    val updates: MutableList<Pair<String, String>> = mutableListOf()

    /** Versions rewritten by [updateFile], in order. */
    val updated: MutableList<String> = mutableListOf()

    override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult {
        updates += version to newRawJson
        unavailable?.let { return MigrationValidationResult.Invalid(it.message) }
        if (readOnly) return MigrationValidationResult.Invalid(FOLDER_READ_ONLY_MESSAGE)
        if (version !in files) return MigrationValidationResult.Invalid("the file could not be written")
        failingVersions[version]?.let { return MigrationValidationResult.Invalid(it) }
        val result = MigrationParser.parse(newRawJson)
        if (result !is MigrationValidationResult.Valid) return result
        if (result.migration.version != version) return MigrationValidationResult.Invalid("the version in the text must stay the same")
        files[version] = result.migration
        updated += version
        return result
    }

    /** A valid file exactly as [rawJson] says (hand-written: key order, extra keys, layout), as it was in the folder before the app started. */
    fun addRaw(rawJson: String): FakeMigrationFolder = apply {
        val migration = (MigrationParser.parse(rawJson) as? MigrationValidationResult.Valid)?.migration
            ?: error("the scenario's own file is not a valid migration: ${(MigrationParser.parse(rawJson) as MigrationValidationResult.Invalid).reason}")
        files[migration.version] = migration
    }

    /** Someone else rewrote the file in the folder (not the app): no attempt is recorded. */
    fun changeOnDisk(version: String, rawJson: String) {
        require(version in files) { "no file $version to change" }
        addRaw(rawJson)
    }

    /** Someone else removed the file from the folder. */
    fun deleteOnDisk(version: String) {
        files.remove(version)
    }

    override suspend fun nextVersion(): String = ((files.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString().padStart(4, '0')
}

/**
 * M8-06b: the ports the review screen and the Migrations screen share. [folderOf] answers `repositoryFor(path)` with the fake of that path
 * (made on first use, so a test can prepare it with [folder]); [repositoriesAsked] records every path the app asked a repository for.
 */
class FakeMigrationFolders(initialFolder: String? = null, vararg chooserAnswers: String?) {
    val store = InMemoryMigrationFolderStore(initialFolder)
    val chooser = FakeMigrationFolderChooser(*chooserAnswers)
    val history = InMemoryMigrationHistoryRepository()
    private val byPath = mutableMapOf<String, FakeMigrationFolder>()
    val repositoriesAsked = mutableListOf<String>()

    /** The fake folder at [path] (created empty on first use). */
    fun folder(path: String): FakeMigrationFolder = byPath.getOrPut(path) { FakeMigrationFolder(path) }

    val ports = MigrationFolderPorts(
        store = store,
        chooser = chooser,
        repositoryFor = { path -> repositoriesAsked += path; folder(path) },
        history = history,
    )
}
