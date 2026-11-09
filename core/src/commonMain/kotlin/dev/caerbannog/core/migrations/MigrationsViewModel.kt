package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MigrationsScreenState(
    val entries: List<MigrationEntry> = emptyList(),
    val selectedVersion: String? = null,
    val selectedFile: MigrationFile? = null,
    val dryRun: MigrationDryRun? = null,
    val isApplyPendingEnabled: Boolean = false,
    val isApplySingleEnabled: Boolean = false,
    val hasConfigurePermission: Boolean = true,
    val controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    val profileName: String = "",
    val vhost: String = "/",
    val bannerMessage: String? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
    val isApplying: Boolean = false,
) {
    val selectedEntry: MigrationEntry?
        get() = entries.firstOrNull { it.file.version == selectedVersion }
}

class MigrationsViewModel(
    private val fileRepository: MigrationFileRepository,
    private val runner: MigrationRunner,
    private val topologyCatalog: TopologyCatalog? = null,
    var profileName: String = "uat-eu",
    var vhost: String = "/",
    var hasConfigurePermission: Boolean = true,
    var controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        MigrationsScreenState(
            profileName = profileName,
            vhost = vhost,
            hasConfigurePermission = hasConfigurePermission,
            controlLevel = controlLevel,
        )
    )
    val state: StateFlow<MigrationsScreenState> = _state.asStateFlow()

    init {
        loadMigrations()
    }

    fun setConnectionContext(
        newProfile: String,
        newVhost: String = "/",
        newConfigurePermission: Boolean = true,
        newControlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    ) {
        profileName = newProfile
        vhost = newVhost
        hasConfigurePermission = newConfigurePermission
        controlLevel = newControlLevel
        loadMigrations()
    }

    fun loadMigrations() {
        scope.launch {
            val files = fileRepository.listFiles()
            val entries = runner.resolveStatuses(profileName, files)
            val selected = _state.value.selectedVersion?.let { ver ->
                entries.firstOrNull { it.file.version == ver }
            } ?: entries.firstOrNull()

            val selectedFile = selected?.file
            val dryRun = selectedFile?.let {
                runner.dryRun(it, hasConfigurePermission, controlLevel, vhost)
            }

            val hasPending = entries.any { it.status is MigrationStatus.Pending }
            val canApplyPending = hasPending && hasConfigurePermission && controlLevel != TopologyControlLevel.READ_ONLY &&
                entries.filter { it.status is MigrationStatus.Pending }.all { belongsToConnection(it.file) }
            val canApplySingle = selected?.status is MigrationStatus.Pending &&
                (dryRun?.canApply == true) &&
                hasConfigurePermission &&
                controlLevel != TopologyControlLevel.READ_ONLY

            val hasModified = entries.any { it.status is MigrationStatus.Modified }
            val warning = if (hasModified) {
                "Warning: an applied migration was modified on disk. Caerbannog will not apply the modified file again."
            } else null

            val banner = if (!hasConfigurePermission) {
                "Apply is disabled: the permission check found that user cannot change topology on $profileName. You can still edit, validate and export this file, then apply it on a connection that allows it."
            } else null

            _state.update { current ->
                current.copy(
                    entries = entries,
                    selectedVersion = selected?.file?.version,
                    selectedFile = selectedFile,
                    dryRun = dryRun,
                    isApplyPendingEnabled = canApplyPending,
                    isApplySingleEnabled = canApplySingle,
                    hasConfigurePermission = hasConfigurePermission,
                    controlLevel = controlLevel,
                    profileName = profileName,
                    vhost = vhost,
                    warningMessage = warning,
                    bannerMessage = banner,
                )
            }
        }
    }

    /** A migration with an action for another virtual host can never be applied on this connection (spec V1). */
    private fun belongsToConnection(file: MigrationFile): Boolean = file.up.all { it.vhost == vhost }

    fun selectVersion(version: String) {
        scope.launch {
            val entry = _state.value.entries.firstOrNull { it.file.version == version }
            val file = entry?.file
            val dryRun = file?.let { runner.dryRun(it, hasConfigurePermission, controlLevel, vhost) }
            val canApplySingle = entry?.status is MigrationStatus.Pending &&
                (dryRun?.canApply == true) &&
                hasConfigurePermission &&
                controlLevel != TopologyControlLevel.READ_ONLY

            _state.update { current ->
                current.copy(
                    selectedVersion = version,
                    selectedFile = file,
                    dryRun = dryRun,
                    isApplySingleEnabled = canApplySingle,
                    errorMessage = null,
                )
            }
        }
    }

    suspend fun applyPending(): MigrationApplyResult {
        _state.update { it.copy(isApplying = true, errorMessage = null) }
        val files = fileRepository.listFiles()
        val result = runner.applyPending(profileName, files, hasConfigurePermission, controlLevel, vhost)
        when (result) {
            is MigrationApplyResult.Success -> {
                loadMigrations()
            }
            is MigrationApplyResult.ActionFailed -> {
                val error = "Action '${result.action.actionName}' failed on version '${result.version}': ${result.reason}"
                _state.update { it.copy(errorMessage = error) }
                loadMigrations()
            }
            is MigrationApplyResult.Blocked -> {
                _state.update { it.copy(errorMessage = result.reason) }
            }
        }
        _state.update { it.copy(isApplying = false) }
        return result
    }

    suspend fun applySingle(version: String): MigrationApplyResult {
        val entry = _state.value.entries.firstOrNull { it.file.version == version }
            ?: return MigrationApplyResult.Blocked("Migration file not found")
        return runner.applyPending(profileName, listOf(entry.file), hasConfigurePermission, controlLevel, vhost)
            .also {
                loadMigrations()
            }
    }

    suspend fun importFile(rawJson: String): MigrationValidationResult {
        val result = fileRepository.importFile(rawJson)
        if (result is MigrationValidationResult.Valid) {
            loadMigrations()
            selectVersion(result.migration.version)
        } else if (result is MigrationValidationResult.Invalid) {
            _state.update { it.copy(errorMessage = result.reason) }
        }
        return result
    }

    /**
     * Writes the current topology of the connection's virtual host as a new migration and returns it. Returns null, with
     * [MigrationsScreenState.errorMessage] set and NO file written, when any catalog call fails (never an empty or partial file). Never throws.
     */
    suspend fun exportCurrentTopology(): MigrationFile? {
        val actions = readTopologyActions()
        if (actions == null) {
            _state.update { it.copy(errorMessage = TOPOLOGY_NOT_READ) }
            return null
        }
        val exported = fileRepository.exportTopology(
            version = fileRepository.nextVersion(),
            description = "Exported broker topology",
            actions = actions,
        )
        loadMigrations()
        selectVersion(exported.version)
        return exported
    }

    /** Every queue page, then the exchanges and the bindings; null as soon as one call fails. */
    private suspend fun readTopologyActions(): List<MigrationAction>? {
        val catalog = topologyCatalog ?: return emptyList()
        val queues = allQueues(catalog) ?: return null
        val exchanges = (catalog.exchanges(vhost) as? CatalogResult.Success)?.value ?: return null
        val bindings = (catalog.bindings(vhost) as? CatalogResult.Success)?.value ?: return null
        return queues.map(::queueAction) + exchanges.mapNotNull(::exchangeAction) + bindings.mapNotNull(::bindingAction)
    }

    private suspend fun allQueues(catalog: TopologyCatalog): List<QueueRow>? {
        val rows = mutableListOf<QueueRow>()
        var page = 1
        do {
            val result = (catalog.queues(vhost, page) as? CatalogResult.Success)?.value ?: return null
            rows += result.rows
            page++
        } while (result.page < result.pageCount)
        return rows
    }

    private fun queueAction(row: QueueRow): MigrationAction =
        if (row.type == "stream") {
            MigrationAction.DeclareStream(name = row.name, vhost = vhost)
        } else {
            MigrationAction.DeclareQueue(name = row.name, type = row.type, vhost = vhost)
        }

    private fun exchangeAction(row: ExchangeRow): MigrationAction? =
        if (row.name.isEmpty() || row.name.startsWith("amq.")) {
            null
        } else {
            MigrationAction.DeclareExchange(
                name = row.name,
                type = row.type,
                vhost = vhost,
                durable = row.durable,
                autoDelete = row.autoDelete,
                internal = row.internal,
            )
        }

    private fun bindingAction(row: BindingRow): MigrationAction? =
        if (row.source.isEmpty() || row.destination.isEmpty()) {
            null
        } else {
            MigrationAction.DeclareBinding(
                exchange = row.source,
                queue = row.destination,
                routingKey = row.routingKey,
                vhost = vhost,
                destinationType = row.destinationType,
            )
        }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private companion object {
        const val TOPOLOGY_NOT_READ = "The topology could not be read"
    }
}
