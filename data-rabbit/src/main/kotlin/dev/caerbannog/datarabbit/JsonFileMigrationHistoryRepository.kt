package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.HistoryMoveResult
import dev.caerbannog.core.migrations.HistoryResult
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.migrations.ProfileHistoryKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The history is `{"schema":1,"profiles":{"<profile>":{"<version>":"<checksum>"}}}`, three levels; far below the reader's failure point. */
private const val MAX_FILE_DEPTH = 32
private const val SCHEMA = 1
private const val ASIDE_INFIX = ".unreadable-"
private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private const val NOT_JSON = "The file is not valid JSON"
private const val TOO_DEEP = "The file is nested too deep"
private const val WRONG_SHAPE = "The file does not have the expected shape"
private const val NOT_READ = "The file cannot be read"

private typealias Profiles = Map<String, Map<String, String>>

/**
 * The applied history of every profile on this machine, in [file] (`~/.caerbannog/migration-history.json`, the app's own data and never
 * the user's migrations folder). A missing file is an empty history. A file that exists and cannot be read is [HistoryResult.Unreadable]:
 * nothing is ever written over it; the only way out is [moveAside], which renames it (timestamp name, never deleting, never overwriting).
 * Writes go through a temp file and [mover]. The reasons are fixed texts, never file content. Calls are serialised by one lock.
 */
class JsonFileMigrationHistoryRepository(
    private val file: Path,
    private val now: NowProvider,
    private val mover: FileMover = NioFileMover,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : MigrationHistoryRepository, ProfileHistoryKeys {

    private sealed interface Snapshot {
        data class Loaded(val profiles: Profiles) : Snapshot
        data class Unreadable(val reason: String) : Snapshot
    }

    private val json = Json { prettyPrint = true }
    private val lock = Any()

    override suspend fun load(profileName: String): HistoryResult = synchronized(lock) {
        when (val snapshot = read()) {
            is Snapshot.Loaded -> HistoryResult.Loaded(snapshot.profiles[profileName].orEmpty())
            is Snapshot.Unreadable -> HistoryResult.Unreadable(file.toString(), snapshot.reason)
        }
    }

    /** Never throws: an unreadable history reads as empty here; the typed [load] is the call that tells the difference. */
    override suspend fun getAppliedMigrations(profileName: String): Map<String, String> =
        (load(profileName) as? HistoryResult.Loaded)?.applied.orEmpty()

    /** @throws IllegalStateException while the file is unreadable (nothing is written); `IOException` when the write fails. */
    override suspend fun recordApplied(profileName: String, version: String, checksum: String) {
        synchronized(lock) {
            val profiles = (read() as? Snapshot.Loaded)?.profiles ?: throw IllegalStateException("the history file is unreadable")
            write(profiles + (profileName to (profiles[profileName].orEmpty() + (version to checksum))))
        }
    }

    /**
     * Moves the entries of [oldName] to [newName]; whatever was under [newName] is REPLACED (a leftover of a deleted profile must not be
     * merged). Does nothing while the file is unreadable. A failed write leaves the entries where they were: the migrations then show as
     * pending under the new name, never as applied by mistake.
     */
    override fun rename(oldName: String, newName: String) {
        if (oldName == newName) return
        change { profiles ->
            if (oldName !in profiles && newName !in profiles) return@change null
            val moved = profiles[oldName]
            (profiles - oldName - newName) + listOfNotNull(moved?.let { newName to it })
        }
    }

    /** Removes the entries of [profileName]. Does nothing while the file is unreadable or when there is nothing to remove. */
    override fun delete(profileName: String) {
        change { profiles -> if (profileName in profiles) profiles - profileName else null }
    }

    override fun asideFileName(): String = synchronized(lock) { freeAsideName() }

    override suspend fun moveAside(): HistoryMoveResult = synchronized(lock) {
        if (read() !is Snapshot.Unreadable) return@synchronized HistoryMoveResult.Failed("There is no unreadable history file to move aside")
        val name = freeAsideName()
        try {
            mover.move(file, file.resolveSibling(name))
            HistoryMoveResult.Moved(name)
        } catch (_: IOException) {
            HistoryMoveResult.Failed("The history file could not be moved aside")
        }
    }

    // Best effort for the key changes: they cannot fail a profile edit that already happened.
    private fun change(transform: (Profiles) -> Profiles?) {
        synchronized(lock) {
            val profiles = (read() as? Snapshot.Loaded)?.profiles ?: return
            val changed = transform(profiles) ?: return
            try {
                write(changed)
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun freeAsideName(): String {
        val base = file.fileName.toString() + ASIDE_INFIX + TIMESTAMP.withZone(zone).format(Instant.ofEpochMilli(now.nowMillis()))
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base-$it" }
            .first { !Files.exists(file.resolveSibling(it)) }
    }

    private fun write(profiles: Profiles) {
        val tree = JsonObject(
            mapOf(
                "schema" to JsonPrimitive(SCHEMA),
                "profiles" to JsonObject(profiles.mapValues { (_, applied) -> JsonObject(applied.mapValues { JsonPrimitive(it.value) }) }),
            ),
        )
        Files.createDirectories(file.toAbsolutePath().parent)
        AtomicFileWriter.write(file, json.encodeToString(JsonObject.serializer(), tree).toByteArray(Charsets.UTF_8), mover)
    }

    private fun read(): Snapshot {
        if (!Files.exists(file)) return Snapshot.Loaded(emptyMap())
        val text = try {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (_: IOException) {
            return Snapshot.Unreadable(NOT_READ)
        }
        return when (val parsed = parseUntrustedJson(text, MAX_FILE_DEPTH, json)) {
            UntrustedJson.TooDeep -> Snapshot.Unreadable(TOO_DEEP)
            UntrustedJson.NotJson -> Snapshot.Unreadable(NOT_JSON)
            is UntrustedJson.Parsed -> profilesOf(parsed.element)?.let { Snapshot.Loaded(it) } ?: Snapshot.Unreadable(WRONG_SHAPE)
        }
    }

    private fun profilesOf(root: JsonElement): Profiles? {
        val schema = ((root as? JsonObject)?.get("schema") as? JsonPrimitive)?.content
        val profiles = (root as? JsonObject)?.get("profiles") as? JsonObject
        if (schema != SCHEMA.toString() || profiles == null) return null
        return profiles.mapValues { (_, entry) ->
            (entry as? JsonObject)?.mapValues { (_, checksum) -> checksum.asText() ?: return null } ?: return null
        }
    }

    private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
