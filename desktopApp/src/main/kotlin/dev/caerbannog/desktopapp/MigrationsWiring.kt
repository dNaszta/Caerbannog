package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.CoroutineScope

/**
 * What one Migrations screen is built from. The window rebuilds the view model when this value changes, so it is a value: equal inputs are
 * equal. The executor and the catalog are compared by identity (a new connection brings new ones).
 */
data class MigrationsInputs(
    val connectionName: String,
    val vhost: String,
    val hasConfigurePermission: Boolean,
    val controlLevel: TopologyControlLevel,
    val executor: TopologyMigrationExecutor?,
    /** Why there is no [executor]: never blank when there is none; null when there is one. */
    val unavailableReason: String?,
    val topology: TopologyCatalog?,
)

private const val NOT_CONNECTED = "Connect to a broker first"
private const val NO_EXECUTOR = "This connection cannot run migrations"

/**
 * The inputs from the connection state: the profile's own level (read-only until proven otherwise), the connection's virtual host, the
 * permission check's answer, the connection's executor and the reason when it has none.
 */
fun AppComposition.migrationsInputs(connections: ConnectionsState): MigrationsInputs {
    val current = connections.currentConnection
    val source = active.source
    val profile = connections.profiles.firstOrNull { it.name == current?.name }
    val permission = (current?.status as? PermissionCheckStatus.Done)?.result
    val executor = source?.migrationExecutor
    return MigrationsInputs(
        connectionName = current?.name.orEmpty(),
        vhost = source?.vhost ?: "/",
        hasConfigurePermission = permission?.topologyUnlocked ?: false,
        controlLevel = profile?.topologyControlLevel ?: TopologyControlLevel.READ_ONLY,
        executor = executor,
        unavailableReason = if (executor != null) null else when {
            source == null -> NOT_CONNECTED
            else -> source.migrationUnavailableReason?.takeIf { it.isNotBlank() } ?: NO_EXECUTOR
        },
        topology = source?.topology,
    )
}

/** The Migrations view model for [inputs]: the real runner over [migrationHistory] and the connection's executor (a refusing one when there is none). */
fun AppComposition.createMigrationsViewModel(inputs: MigrationsInputs, scope: CoroutineScope): MigrationsViewModel =
    MigrationsViewModel(
        fileRepository = null,
        runner = MigrationRunner(migrationHistory, inputs.executor ?: RefusingExecutor),
        topologyCatalog = inputs.topology,
        profileName = inputs.connectionName,
        vhost = inputs.vhost,
        hasConfigurePermission = inputs.hasConfigurePermission,
        controlLevel = inputs.controlLevel,
        folderPorts = migrationFolderPorts,
        migrationUnavailableReason = inputs.unavailableReason,
        scope = scope,
    )

/** Stands in when there is no executor; the view model refuses to apply first, so this is the second lock, never the only one. */
private object RefusingExecutor : TopologyMigrationExecutor {
    override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Failed(NO_EXECUTOR)
}

/** Saves an edited migration into the folder in use; nothing happens while no folder is chosen. */
suspend fun MigrationFolderPorts.updateFile(version: String, rawJson: String) {
    store.folder?.let(repositoryFor)?.updateFile(version, rawJson)
}
