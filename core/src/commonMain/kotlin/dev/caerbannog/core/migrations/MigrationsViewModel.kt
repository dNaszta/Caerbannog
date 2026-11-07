package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
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
                runner.dryRun(it, hasConfigurePermission, controlLevel)
            }

            val hasPending = entries.any { it.status is MigrationStatus.Pending }
            val canApplyPending = hasPending && hasConfigurePermission && controlLevel != TopologyControlLevel.READ_ONLY
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

    fun selectVersion(version: String) {
        scope.launch {
            val entry = _state.value.entries.firstOrNull { it.file.version == version }
            val file = entry?.file
            val dryRun = file?.let { runner.dryRun(it, hasConfigurePermission, controlLevel) }
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
        val result = runner.applyPending(profileName, files, hasConfigurePermission, controlLevel)
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
        return runner.applyPending(profileName, listOf(entry.file), hasConfigurePermission, controlLevel)
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

    suspend fun exportCurrentTopology(): MigrationFile {
        val nextVersion = fileRepository.nextVersion()
        val actions = mutableListOf<MigrationAction>()

        if (topologyCatalog != null) {
            val queuesResult = topologyCatalog.queues(vhost, 1)
            val queues = (queuesResult as? dev.caerbannog.core.streams.CatalogResult.Success)?.value?.rows ?: emptyList()
            for (q in queues) {
                if (q.type == "stream") {
                    actions += MigrationAction.DeclareStream(name = q.name, vhost = vhost)
                } else {
                    actions += MigrationAction.DeclareQueue(name = q.name, type = q.type, vhost = vhost)
                }
            }

            val exchangesResult = topologyCatalog.exchanges(vhost)
            val exchanges = (exchangesResult as? dev.caerbannog.core.streams.CatalogResult.Success)?.value ?: emptyList()
            for (ex in exchanges) {
                if (ex.name.isNotEmpty() && !ex.name.startsWith("amq.")) {
                    actions += MigrationAction.DeclareExchange(name = ex.name, type = ex.type, vhost = vhost)
                }
            }

            val bindingsResult = topologyCatalog.bindings(vhost)
            val bindings = (bindingsResult as? dev.caerbannog.core.streams.CatalogResult.Success)?.value ?: emptyList()
            for (b in bindings) {
                if (b.source.isNotEmpty() && b.destination.isNotEmpty()) {
                    actions += MigrationAction.DeclareBinding(
                        exchange = b.source,
                        queue = b.destination,
                        routingKey = b.routingKey,
                        vhost = vhost,
                    )
                }
            }
        }

        val exported = fileRepository.exportTopology(
            version = nextVersion,
            description = "Exported broker topology",
            actions = actions,
        )
        loadMigrations()
        selectVersion(exported.version)
        return exported
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
