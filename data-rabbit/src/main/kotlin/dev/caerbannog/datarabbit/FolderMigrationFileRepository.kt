package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.FOLDER_READ_ONLY_MESSAGE
import dev.caerbannog.core.migrations.FolderListing
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.InvalidMigrationFile
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFileRepository
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationWriteException
import dev.caerbannog.core.migrations.echoableName
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val JSON_SUFFIX = ".json"
private val SAFE_VERSION = Regex("[A-Za-z0-9_-]+")

private const val NOT_WRITTEN = "the file could not be written"
private const val VERSION_EXISTS = "that version already exists"
private const val VERSION_CHANGED = "the version in the text must stay the same"
private const val NAME_MISMATCH = "the file name does not match its version"
private const val NOT_READ = "the file could not be read"
private const val UNUSUAL_NAME = "a file with an unusual name"

/**
 * `<version>.json` files in [folder], each holding exactly the raw text of its migration (bytes as imported, UTF-8), so its checksum is the
 * same after a restart. [folder] is the user's own: it is never created (nor its parents), a file is written only for an import, an edit or
 * an export, always through a temp file and [mover], and a file that is not a valid migration is listed and never changed or deleted.
 * The version is the file name, so it must match `[A-Za-z0-9_-]+`: no input can name a path outside the folder.
 * Read-only and unreadable detection use the permission bits and are best effort (UNVERIFIED on Windows and exotic file systems).
 */
class FolderMigrationFileRepository(
    private val folder: Path,
    private val mover: FileMover = NioFileMover,
) : MigrationFileRepository {

    private sealed interface Access {
        data class Unusable(val problem: FolderProblem) : Access
        data class Usable(val readOnly: Boolean) : Access
    }

    private fun access(): Access = when {
        !Files.exists(folder) -> Access.Unusable(FolderProblem.MISSING)
        !Files.isDirectory(folder) -> Access.Unusable(FolderProblem.NOT_A_FOLDER)
        !Files.isReadable(folder) || !Files.isExecutable(folder) -> Access.Unusable(FolderProblem.UNREADABLE)
        else -> Access.Usable(readOnly = !Files.isWritable(folder))
    }

    override suspend fun load(): FolderListing = when (val access = access()) {
        is Access.Unusable -> FolderListing.Unavailable(folder.toString(), access.problem)
        is Access.Usable -> listOrUnavailable(access.readOnly)
    }

    override suspend fun listFiles(): List<MigrationFile> = (load() as? FolderListing.Listed)?.files.orEmpty()

    private fun listOrUnavailable(readOnly: Boolean): FolderListing {
        val names = try {
            Files.list(folder).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(JSON_SUFFIX) }.toList()
            }
        } catch (_: IOException) {
            return FolderListing.Unavailable(folder.toString(), FolderProblem.UNREADABLE)
        }
        val files = mutableListOf<MigrationFile>()
        val invalid = mutableListOf<InvalidMigrationFile>()
        for (path in names.sortedBy { it.fileName.toString() }) {
            when (val read = readFile(path)) {
                is MigrationValidationResult.Valid -> files += read.migration
                is MigrationValidationResult.Invalid -> invalid += InvalidMigrationFile(shownName(path), read.reason)
            }
        }
        return FolderListing.Listed(files.sortedBy { it.version }, invalid, readOnly)
    }

    private fun readFile(path: Path): MigrationValidationResult {
        val text = try {
            String(Files.readAllBytes(path), Charsets.UTF_8)
        } catch (_: IOException) {
            return MigrationValidationResult.Invalid(NOT_READ)
        }
        val result = MigrationParser.parse(text)
        val stem = path.fileName.toString().removeSuffix(JSON_SUFFIX)
        return if (result is MigrationValidationResult.Valid && result.migration.version != stem) {
            MigrationValidationResult.Invalid(NAME_MISMATCH)
        } else {
            result
        }
    }

    /** A file name from the folder is shown only when it passes the echo rule. */
    private fun shownName(path: Path): String = path.fileName.toString().takeIf(::echoableName) ?: UNUSUAL_NAME

    override suspend fun importFile(rawJson: String): MigrationValidationResult {
        refusalForWrite()?.let { return MigrationValidationResult.Invalid(it) }
        val result = MigrationParser.parse(rawJson)
        if (result !is MigrationValidationResult.Valid) return result
        val target = fileOf(result.migration.version) ?: return MigrationValidationResult.Invalid(NOT_WRITTEN)
        if (Files.exists(target)) return MigrationValidationResult.Invalid(VERSION_EXISTS)
        return writeOrInvalid(target, result)
    }

    override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult {
        refusalForWrite()?.let { return MigrationValidationResult.Invalid(it) }
        val target = fileOf(version) ?: return MigrationValidationResult.Invalid(NOT_WRITTEN)
        val result = MigrationParser.parse(newRawJson)
        if (result !is MigrationValidationResult.Valid) return result
        if (result.migration.version != version) return MigrationValidationResult.Invalid(VERSION_CHANGED)
        return writeOrInvalid(target, result)
    }

    private fun writeOrInvalid(target: Path, result: MigrationValidationResult.Valid): MigrationValidationResult =
        try {
            // A file that holds a password hash is readable by its owner only (P13); every other file keeps the process default.
            AtomicFileWriter.write(target, result.migration.rawJson.toByteArray(Charsets.UTF_8), mover, ownerOnly = result.migration.up.any { it is MigrationAction.DeclareUser })
            result
        } catch (_: IOException) {
            MigrationValidationResult.Invalid(NOT_WRITTEN)
        }

    override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile {
        refusalForWrite()?.let { throw MigrationWriteException(it) }
        val target = fileOf(version)?.takeUnless { Files.exists(it) } ?: throw MigrationWriteException(NOT_WRITTEN)
        val rawJson = MigrationParser.exportToJson(version, description, actions)
        val parsed = MigrationParser.parse(rawJson) as? MigrationValidationResult.Valid ?: throw MigrationWriteException(NOT_WRITTEN)
        try {
            AtomicFileWriter.write(target, rawJson.toByteArray(Charsets.UTF_8), mover, ownerOnly = actions.any { it is MigrationAction.DeclareUser })
        } catch (_: IOException) {
            throw MigrationWriteException(NOT_WRITTEN)
        }
        return parsed.migration
    }

    /** The next free four-digit number above every `<number>.json` name, valid or not, so an export never lands on an existing file. */
    override suspend fun nextVersion(): String {
        if (access() is Access.Unusable) return FIRST_VERSION
        val taken = try {
            Files.list(folder).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.endsWith(JSON_SUFFIX) }
                    .map { name -> name.removeSuffix(JSON_SUFFIX).takeIf { it.all { c -> c in '0'..'9' } }?.toLongOrNull() }
                    .toList().filterNotNull()
            }
        } catch (_: IOException) {
            emptyList()
        }
        return ((taken.maxOrNull() ?: 0L) + 1).toString().padStart(4, '0')
    }

    /** Why nothing may be written now (the folder state), or null. */
    private fun refusalForWrite(): String? = when (val access = access()) {
        is Access.Unusable -> access.problem.message
        is Access.Usable -> if (access.readOnly) FOLDER_READ_ONLY_MESSAGE else null
    }

    /** The path of `<version>.json`, or null when the version is not a safe file name. */
    private fun fileOf(version: String): Path? =
        if (SAFE_VERSION.matches(version)) folder.resolve(version + JSON_SUFFIX) else null

    private companion object {
        const val FIRST_VERSION = "0001"
    }
}
