package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel

/**
 * Result of executing a single migration action on the broker.
 */
sealed interface ActionResult {
    data object Success : ActionResult
    data class Failed(val reason: String) : ActionResult
}

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
     * Dry run check: evaluates whether actions are allowed without modifying the broker.
     */
    fun dryRun(
        migration: MigrationFile,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        // M5-08a STUB: accepted and ignored until the runner implements the V1 rule. The default keeps the existing callers compiling.
        vhost: String,
    ): MigrationDryRun {
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
            if (action.isDestructive && controlLevel != TopologyControlLevel.FULL_CONTROL) {
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
            blockedReason = if (!allAllowed) "Some actions are blocked by control level" else null,
        )
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
        // M5-08a STUB: accepted and ignored until the runner implements the V1 rule.
        vhost: String,
    ): MigrationApplyResult {
        if (!hasConfigurePermission) {
            return MigrationApplyResult.Blocked("The user has no configure permission on this connection")
        }
        if (controlLevel == TopologyControlLevel.READ_ONLY) {
            return MigrationApplyResult.Blocked("Topology control level is Read-only")
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
            val dryRunResult = dryRun(migration, hasConfigurePermission, controlLevel, vhost)
            if (!dryRunResult.canApply) {
                return MigrationApplyResult.Blocked(dryRunResult.blockedReason ?: "Migration is blocked")
            }

            for (action in migration.up) {
                when (val outcome = executor.execute(action)) {
                    is ActionResult.Success -> {
                        // Action completed or was idempotent no-op
                    }
                    is ActionResult.Failed -> {
                        return MigrationApplyResult.ActionFailed(
                            version = migration.version,
                            action = action,
                            index = 0, // M5-08a STUB
                            total = 0, // M5-08a STUB
                            reason = outcome.reason,
                        )
                    }
                }
            }

            // Record as applied upon completing all actions in the migration
            historyRepository.recordApplied(profileName, migration.version, migration.checksum)
            appliedVersions += migration.version
        }

        return MigrationApplyResult.Success(appliedVersions)
    }
}
