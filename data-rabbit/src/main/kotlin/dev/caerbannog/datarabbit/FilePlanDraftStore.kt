package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.planner.DraftLoad
import dev.caerbannog.core.planner.DraftMoveResult
import dev.caerbannog.core.planner.PlanDraftStore
import dev.caerbannog.core.planner.TopologyPlan
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DRAFT_VERSION = "draft"
private const val ASIDE_INFIX = ".unreadable-"
private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private const val NOT_TEXT = "The file is not valid text"
private const val NOT_A_PLAN = "The file is not a valid plan"
private const val HOLDS_HASHES = "This file holds password hashes. Edit it outside the app."
private const val NOT_READ = "The file cannot be read"
private const val NOTHING_TO_MOVE = "There is no unreadable draft file to move aside"
private const val NOT_MOVED = "The draft file could not be moved aside"

/**
 * The one draft of this machine, in [file] (`~/.caerbannog/planner-draft.json`, the app's own data and never the migrations folder). Its text is
 * `MigrationParser.exportToJson("draft", description, actions)` and it is read with `MigrationParser.parse`: no new format. A missing file is
 * no draft. A file that exists and cannot be used (not text, not a plan, or a plan that holds an access action, whose password hash a draft must
 * never hold or re-save, spec A8) is [DraftLoad.Unreadable] with a fixed reason that never echoes content: it is never written over, cleared or
 * deleted, and the only way out is [moveAside], which renames it (timestamp name, never deleting, never overwriting). Writes go through a temp
 * file and [mover], so a crash leaves the old bytes. A draft holds no secret, so the file has the process's default permissions.
 * Calls are serialised by one lock. This class knows no broker and no migrations folder.
 */
class FilePlanDraftStore(
    private val file: Path,
    private val now: NowProvider,
    private val mover: FileMover = NioFileMover,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : PlanDraftStore {

    private sealed interface Snapshot {
        data object Missing : Snapshot
        data class Readable(val plan: TopologyPlan) : Snapshot
        data class Unusable(val reason: String) : Snapshot
    }

    private val lock = Any()

    override suspend fun load(): DraftLoad = synchronized(lock) {
        when (val snapshot = read()) {
            Snapshot.Missing -> DraftLoad.None
            is Snapshot.Readable -> DraftLoad.Loaded(snapshot.plan)
            is Snapshot.Unusable -> DraftLoad.Unreadable(file.toString(), snapshot.reason)
        }
    }

    override suspend fun save(plan: TopologyPlan): Boolean = synchronized(lock) {
        if (read() is Snapshot.Unusable) return@synchronized false
        val text = MigrationParser.exportToJson(DRAFT_VERSION, plan.description, plan.steps.map { it.action })
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            AtomicFileWriter.write(file, text.toByteArray(Charsets.UTF_8), mover, ownerOnly = false)
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * An unreadable file stays. When the delete itself fails the file stays too and comes back as the draft at the next start, which is safe
     * (the interface has no way to say "not removed", and Dismiss has already told the user the plan is gone from the screen).
     */
    override suspend fun clear() {
        synchronized(lock) {
            if (read() is Snapshot.Unusable) return
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
                // see the KDoc: the draft file is left as it was
            }
        }
    }

    override fun asideFileName(): String = synchronized(lock) { freeAsideName() }

    override suspend fun moveAside(): DraftMoveResult = synchronized(lock) {
        if (read() !is Snapshot.Unusable) return@synchronized DraftMoveResult.Failed(NOTHING_TO_MOVE)
        val name = freeAsideName()
        try {
            mover.move(file, file.resolveSibling(name))
            DraftMoveResult.Moved(name)
        } catch (_: IOException) {
            DraftMoveResult.Failed(NOT_MOVED)
        }
    }

    private fun freeAsideName(): String {
        val base = file.fileName.toString() + ASIDE_INFIX + TIMESTAMP.withZone(zone).format(Instant.ofEpochMilli(now.nowMillis()))
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base-$it" }
            .first { !Files.exists(file.resolveSibling(it)) }
    }

    private fun read(): Snapshot {
        if (!Files.exists(file)) return Snapshot.Missing
        val bytes = try {
            Files.readAllBytes(file)
        } catch (_: IOException) {
            return Snapshot.Unusable(NOT_READ)
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return Snapshot.Unusable(NOT_TEXT)
        }
        val migration = (MigrationParser.parse(text) as? MigrationValidationResult.Valid)?.migration
            ?: return Snapshot.Unusable(NOT_A_PLAN)
        if (migration.up.any { it.isBrokerLevel }) return Snapshot.Unusable(HOLDS_HASHES)
        return Snapshot.Readable(TopologyPlan.of(migration.description, migration.up, defaultVhost = "/"))
    }

    companion object {
        /** `~/.caerbannog/planner-draft.json`, next to `settings.json`. */
        fun defaultFile(): Path = File(System.getProperty("user.home"), ".caerbannog/planner-draft.json").toPath()
    }
}
