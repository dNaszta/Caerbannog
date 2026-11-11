package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.coroutines.CancellationException

/**
 * Result of executing a single migration action on the broker.
 */
sealed interface ActionResult {
    data object Success : ActionResult
    /** [kind] is decided by the executor from facts (never from [reason]); it only changes how the failure is worded. */
    data class Failed(val reason: String, val kind: FailureKind = FailureKind.Other) : ActionResult
}

/** Why an action failed, decided by the executor from facts, never from text. */
enum class FailureKind { Other, Conflict }

/** One step of an apply: the action about to be sent (1-based [index] of [total] actions of migration [version]). */
data class ApplyStep(val version: String, val index: Int, val total: Int)

/**
 * Capability to execute individual topology actions on a broker.
 */
interface TopologyMigrationExecutor {
    suspend fun execute(action: MigrationAction): ActionResult
}

/**
 * Per-machine persistence of applied migration versions and checksums per profile.
 */
interface MigrationHistoryRepository {
    suspend fun getAppliedMigrations(profileName: String): Map<String, String>
    suspend fun recordApplied(profileName: String, version: String, checksum: String)

    /** Typed answer: [HistoryResult.Unreadable] when the history file exists but cannot be read; the default never fails. */
    suspend fun load(profileName: String): HistoryResult = HistoryResult.Loaded(getAppliedMigrations(profileName))

    /** The name [moveAside] would give the unreadable file now (with its collision suffix). */
    fun asideFileName(): String = throw UnsupportedOperationException("this history has no file to move aside")

    /** Renames the unreadable history file to [asideFileName], never deleting or overwriting. */
    suspend fun moveAside(): HistoryMoveResult = HistoryMoveResult.Failed("this history has no file to move aside")
}

/**
 * In-memory history repository for tests and default state.
 */
class InMemoryMigrationHistoryRepository : MigrationHistoryRepository {
    private val history = mutableMapOf<String, MutableMap<String, String>>()

    override suspend fun getAppliedMigrations(profileName: String): Map<String, String> {
        return history[profileName]?.toMap() ?: emptyMap()
    }

    override suspend fun recordApplied(profileName: String, version: String, checksum: String) {
        history.getOrPut(profileName) { mutableMapOf() }[version] = checksum
    }

    fun clear() {
        history.clear()
    }
}

/**
 * Evaluates dry-run allowances and executes topology migrations.
 */
class MigrationRunner(
    private val historyRepository: MigrationHistoryRepository,
    private val executor: TopologyMigrationExecutor,
) {
    /**
     * Dry run check: evaluates whether actions are allowed without modifying the broker. [vhost] is the connection's virtual host:
     * an action for any other one is blocked (spec V1), whatever the control level.
     */
    fun dryRun(
        migration: MigrationFile,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        vhost: String,
        /** M8-12a: the tags of the connection's user; a broker-level action needs "administrator" (and Full control). */
        userTags: Set<String> = emptySet(),
    ): MigrationDryRun {
        // A file with an access action is checked action by action; a topology-only file keeps the rules below untouched.
        if (migration.up.any { it.isBrokerLevel }) return dryRunPerAction(migration, hasConfigurePermission, controlLevel, vhost, userTags)
        if (!hasConfigurePermission) {
            val actions = migration.up.map {
                ActionDryRun(it, allowed = false, reason = "User has no configure permission on this virtual host")
            }
            return MigrationDryRun(
                version = migration.version,
                actions = actions,
                canApply = false,
                blockedReason = "The user has no configure permission on this connection",
            )
        }

        if (controlLevel == TopologyControlLevel.READ_ONLY) {
            val actions = migration.up.map {
                ActionDryRun(it, allowed = false, reason = "Topology control level is Read-only")
            }
            return MigrationDryRun(
                version = migration.version,
                actions = actions,
                canApply = false,
                blockedReason = "Topology control level is Read-only",
            )
        }

        val actions = migration.up.map { action ->
            if (action.vhost != vhost) {
                ActionDryRun(action = action, allowed = false, reason = otherVhostReason(action.vhost, vhost))
            } else if (action.isDestructive && controlLevel != TopologyControlLevel.FULL_CONTROL) {
                ActionDryRun(
                    action = action,
                    allowed = false,
                    reason = "Destructive actions require Full control level",
                )
            } else {
                ActionDryRun(action = action, allowed = true)
            }
        }

        val allAllowed = actions.all { it.allowed }
        return MigrationDryRun(
            version = migration.version,
            actions = actions,
            canApply = allAllowed,
            blockedReason = when {
                allAllowed -> null
                migration.up.any { it.vhost != vhost } -> "Some actions belong to another virtual host than this connection's"
                else -> "Some actions are blocked by control level"
            },
        )
    }

    /**
     * A broker-level action needs the administrator tag and Full control; the configure permission and the V1 vhost rule do not apply to it.
     * A topology action keeps its rules. Read-only blocks everything.
     */
    private fun dryRunPerAction(
        migration: MigrationFile,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        vhost: String,
        userTags: Set<String>,
    ): MigrationDryRun {
        val readOnly = controlLevel == TopologyControlLevel.READ_ONLY
        val actions = migration.up.map { action ->
            val reason = when {
                readOnly -> READ_ONLY_REASON
                action.isBrokerLevel -> when {
                    ADMINISTRATOR !in userTags -> NEEDS_ADMINISTRATOR
                    controlLevel != TopologyControlLevel.FULL_CONTROL -> NEEDS_FULL_CONTROL
                    else -> null
                }
                !hasConfigurePermission -> "User has no configure permission on this virtual host"
                action.vhost != vhost -> otherVhostReason(action.vhost, vhost)
                action.isDestructive && controlLevel != TopologyControlLevel.FULL_CONTROL -> "Destructive actions require Full control level"
                else -> null
            }
            ActionDryRun(action, allowed = reason == null, reason = reason)
        }
        val first = actions.firstOrNull { !it.allowed }
        val blockedReason = when {
            first == null -> null
            readOnly || first.action.isBrokerLevel -> first.reason
            !hasConfigurePermission -> "The user has no configure permission on this connection"
            first.action.vhost != vhost -> "Some actions belong to another virtual host than this connection's"
            else -> "Some actions are blocked by control level"
        }
        return MigrationDryRun(migration.version, actions, canApply = first == null, blockedReason = blockedReason)
    }

    /** Names a virtual host only through the echo rule; the wording keeps saying "virtual host" either way. */
    private fun otherVhostReason(actionVhost: String, connectionVhost: String): String =
        if (echoableName(actionVhost) && echoableName(connectionVhost)) {
            "The action belongs to virtual host $actionVhost; this connection is for $connectionVhost"
        } else {
            "The action belongs to another virtual host than this connection's"
        }

    /**
     * Resolves the current status of each migration file for the specified profile.
     */
    suspend fun resolveStatuses(
        profileName: String,
        files: List<MigrationFile>,
    ): List<MigrationEntry> {
        val applied = historyRepository.getAppliedMigrations(profileName)
        return files.map { file ->
            val recordedChecksum = applied[file.version]
            val status = when {
                recordedChecksum == null -> MigrationStatus.Pending
                recordedChecksum != file.checksum -> MigrationStatus.Modified(
                    recordedChecksum = recordedChecksum,
                    currentChecksum = file.checksum,
                )
                else -> MigrationStatus.Applied(recordedChecksum)
            }
            MigrationEntry(file = file, status = status)
        }
    }

    /**
     * Applies pending migrations in ascending version order.
     */
    suspend fun applyPending(
        profileName: String,
        files: List<MigrationFile>,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        vhost: String,
        /** M8-12a: see [dryRun]. */
        userTags: Set<String> = emptySet(),
        /** Called once per action, in order, just before it is sent (never after a failure). */
        onAction: (ApplyStep) -> Unit = {},
    ): MigrationApplyResult {
        // An access file does not need the configure permission: it is checked action by action in the dry run below.
        if (!hasConfigurePermission && files.none { file -> file.up.any { it.isBrokerLevel } }) {
            return MigrationApplyResult.Blocked("The user has no configure permission on this connection")
        }
        if (controlLevel == TopologyControlLevel.READ_ONLY) {
            return MigrationApplyResult.Blocked(READ_ONLY_REASON)
        }

        val statuses = resolveStatuses(profileName, files)
        // Only pending migrations are applied. Modified or already applied migrations are skipped.
        val pending = statuses
            .filter { it.status is MigrationStatus.Pending }
            .map { it.file }
            .sortedBy { it.version }

        if (pending.isEmpty()) {
            return MigrationApplyResult.Success(emptyList())
        }

        val appliedVersions = mutableListOf<String>()

        for (migration in pending) {
            val dryRunResult = dryRun(migration, hasConfigurePermission, controlLevel, vhost, userTags)
            if (!dryRunResult.canApply) {
                return MigrationApplyResult.Blocked(dryRunResult.blockedReason ?: "Migration is blocked")
            }

            for ((position, action) in migration.up.withIndex()) {
                onAction(ApplyStep(migration.version, position + 1, migration.up.size))
                when (val outcome = executor.execute(action)) {
                    is ActionResult.Success -> {
                        // Action completed or was idempotent no-op
                    }
                    is ActionResult.Failed -> {
                        return MigrationApplyResult.ActionFailed(
                            version = migration.version,
                            action = action,
                            index = position + 1,
                            total = migration.up.size,
                            reason = outcome.reason,
                            kind = outcome.kind,
                        )
                    }
                }
            }

            // Record as applied upon completing all actions in the migration; if that fails, nothing later may run.
            if (!recorded(profileName, migration)) {
                return MigrationApplyResult.NotRecorded(migration.version, NOT_RECORDED)
            }
            appliedVersions += migration.version
        }

        return MigrationApplyResult.Success(appliedVersions)
    }

    /** False when the history refused or could not write the record; the broker change was made and every action is safe to run again. */
    private suspend fun recorded(profileName: String, migration: MigrationFile): Boolean =
        try {
            historyRepository.recordApplied(profileName, migration.version, migration.checksum)
            true
        } catch (failure: CancellationException) {
            // A cancellation is an IllegalStateException on the JVM; it must keep cancelling.
            throw failure
        } catch (failure: Exception) {
            if (failure is IllegalStateException || failure.isIoFailure()) false else throw failure
        }

    private companion object {
        const val ADMINISTRATOR = "administrator"
        const val READ_ONLY_REASON = "Topology control level is Read-only"
        const val NEEDS_ADMINISTRATOR = "Creating virtual hosts, users and permissions needs the administrator tag"
        const val NEEDS_FULL_CONTROL = "Creating virtual hosts, users and permissions needs the level Full control"
        const val NOT_RECORDED = "The migration was applied but could not be recorded; running it again is safe"
    }
}
