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
    // The defaults are the old in-memory behaviour (no folder, no history problem).
    val folder: MigrationFolderState = MigrationFolderState.Ready(null),
    val invalidFiles: List<InvalidMigrationFile> = emptyList(),
    val historyProblem: HistoryUnreadable? = null,
    val isImportEnabled: Boolean = true,
    val isExportEnabled: Boolean = true,
    /** The question waiting for Confirm or Cancel, or null. */
    val confirmation: String? = null,
    /** The action being sent while an apply runs, else null. */
    val applyProgress: ApplyProgress? = null,
    /** How the last apply ended when it did not succeed; cleared by the next apply and by `dismissOutcome`. */
    val applyOutcome: ApplyOutcome? = null,
    /** Why this connection cannot apply migrations (for example a 3.x broker), or null. */
    val unavailableReason: String? = null,
) {
    /** True when a folder is chosen and exists, so "Show folder" has something to open; decided by the typed problem, never by text. */
    val canShowFolder: Boolean
        get() = when (folder) {
            is MigrationFolderState.ChooseFolder -> false
            is MigrationFolderState.FolderUnavailable -> folder.problem != FolderProblem.MISSING
            is MigrationFolderState.FolderReadOnly, is MigrationFolderState.Ready -> true
        }

    // What the screen may enable, decided here from the structured state so the composables only read it (a flag set by the view model is not enough:
    // the choose-folder, unavailable and history states must lock Apply whatever the flags say).
    private val canChangeFiles: Boolean get() = isImportEnabled && !isApplying
    val canImport: Boolean get() = canChangeFiles
    val canExport: Boolean get() = canChangeFiles
    val canEditFile: Boolean get() = canChangeFiles
    private val mayApply: Boolean
        get() = !isApplying && unavailableReason == null && historyProblem == null &&
            (folder is MigrationFolderState.Ready || folder is MigrationFolderState.FolderReadOnly)
    val canApplyPending: Boolean get() = isApplyPendingEnabled && mayApply
    val canApplySingle: Boolean get() = isApplySingleEnabled && mayApply

    val selectedEntry: MigrationEntry?
        get() = entries.firstOrNull { it.file.version == selectedVersion }
}

class MigrationsViewModel(
    /** Used only without [folderPorts] (the in-memory mode of the tests); null reads and writes nothing. */
    private val fileRepository: MigrationFileRepository?,
    private val runner: MigrationRunner,
    private val topologyCatalog: TopologyCatalog? = null,
    var profileName: String = "uat-eu",
    var vhost: String = "/",
    var hasConfigurePermission: Boolean = true,
    var controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    /** M8-12a: the tags of the connection's user, passed to the runner. */
    var userTags: Set<String> = emptySet(),
    /** When set, files come from `repositoryFor(chosen folder)` and [fileRepository] is not used. */
    private val folderPorts: MigrationFolderPorts? = null,
    /** Non-null disables Apply pending and Apply single (import, export and the dry run keep working). */
    private val migrationUnavailableReason: String? = null,
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
        folderPorts?.let { ports ->
            scope.launch { ports.store.changes.collect { loadMigrations() } }
        }
        loadMigrations()
    }

    fun setConnectionContext(
        newProfile: String,
        newVhost: String = "/",
        newConfigurePermission: Boolean = true,
        newControlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
        newUserTags: Set<String> = emptySet(),
    ) {
        userTags = newUserTags
        profileName = newProfile
        vhost = newVhost
        hasConfigurePermission = newConfigurePermission
        controlLevel = newControlLevel
        loadMigrations()
    }

    fun loadMigrations() {
        scope.launch {
            val listing = readListing()
            val history = folderPorts?.history?.load(profileName)
            val historyProblem = (history as? HistoryResult.Unreadable)?.let { HistoryUnreadable(it.path, it.reason) }
            val files = (listing as? FolderListing.Listed)?.files.orEmpty()
            val entries = runner.resolveStatuses(profileName, files)
            val selected = _state.value.selectedVersion?.let { ver ->
                entries.firstOrNull { it.file.version == ver }
            } ?: entries.firstOrNull()

            val selectedFile = selected?.file
            val dryRun = selectedFile?.let {
                runner.dryRun(it, hasConfigurePermission, controlLevel, vhost, userTags)
            }

            val pendingFiles = entries.filter { it.status is MigrationStatus.Pending }.map { it.file }
            val canApply = mayChangeTopology(forFile = pendingFiles.isNotEmpty() && pendingFiles.all { f -> f.up.any { it.isBrokerLevel } }) && historyProblem == null
            val hasPending = pendingFiles.isNotEmpty()
            // Every pending file must be allowed by its own dry run: an access file by tag and level, a topology file by the old rules.
            val canApplyPending = hasPending && canApply && pendingFiles.all { f -> belongsToConnection(f) && (f.up.none { it.isBrokerLevel } || runner.dryRun(f, hasConfigurePermission, controlLevel, vhost, userTags).canApply) }
            val canApplySingle = selected?.status is MigrationStatus.Pending && (dryRun?.canApply == true) &&
                mayChangeTopology(forFile = selectedFile.up.any { it.isBrokerLevel } == true) && historyProblem == null

            val hasModified = entries.any { it.status is MigrationStatus.Modified }
            val warning = if (hasModified) {
                "Warning: an applied migration was modified on disk. Caerbannog will not apply the modified file again."
            } else null

            val folder = folderState(listing)
            val banner = bannerMessage()
            val canWrite = folder is MigrationFolderState.Ready

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
                    folder = folder,
                    invalidFiles = (listing as? FolderListing.Listed)?.invalid.orEmpty(),
                    historyProblem = historyProblem,
                    isImportEnabled = canWrite,
                    isExportEnabled = canWrite,
                    unavailableReason = migrationUnavailableReason,
                )
            }
        }
    }

    /** [forFile]: an access file does not need the configure permission (its dry run decides by tag and level); null asks about a topology file. */
    private fun mayChangeTopology(forFile: Boolean = false): Boolean =
        (hasConfigurePermission || forFile) && controlLevel != TopologyControlLevel.READ_ONLY && migrationUnavailableReason == null

    /** The permission and level lines only; the folder, history, outcome and unavailable states are drawn from their structured fields. */
    private fun bannerMessage(): String? = when {
        controlLevel == TopologyControlLevel.READ_ONLY -> "Apply is disabled: this connection is set to Read-only"
        !hasConfigurePermission ->
            "Apply is disabled: the permission check found that user cannot change topology on $profileName. You can still edit, validate and export this file, then apply it on a connection that allows it."
        else -> null
    }

    private fun unavailableText(reason: String) = "Migrations are not offered on this connection: $reason"

    /** The repository of the folder in use; null while no folder is chosen (nothing is read or written then). */
    private fun currentRepository(): MigrationFileRepository? =
        if (folderPorts == null) fileRepository else folderPorts.store.folder?.let(folderPorts.repositoryFor)

    private suspend fun readListing(): FolderListing? = currentRepository()?.load()

    private fun folderState(listing: FolderListing?): MigrationFolderState {
        val path = folderPorts?.store?.folder
        return when {
            listing == null -> MigrationFolderState.ChooseFolder
            listing is FolderListing.Unavailable -> MigrationFolderState.FolderUnavailable(listing.path, listing.problem)
            listing is FolderListing.Listed && listing.readOnly && path != null -> MigrationFolderState.FolderReadOnly(path)
            else -> MigrationFolderState.Ready(path)
        }
    }

    /** A migration with an action for another virtual host can never be applied on this connection (spec V1). */
    private fun belongsToConnection(file: MigrationFile): Boolean = file.up.all { it.isBrokerLevel || it.vhost == vhost }

    fun selectVersion(version: String) {
        scope.launch {
            val entry = _state.value.entries.firstOrNull { it.file.version == version }
            val file = entry?.file
            val dryRun = file?.let { runner.dryRun(it, hasConfigurePermission, controlLevel, vhost, userTags) }
            val canApplySingle = entry?.status is MigrationStatus.Pending &&
                (dryRun?.canApply == true) &&
                mayChangeTopology(forFile = file.up.any { it.isBrokerLevel } == true)

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
        if (_state.value.isApplying) return MigrationApplyResult.Blocked(APPLY_RUNNING)
        applyBlockReason()?.let { reason ->
            _state.update { it.copy(errorMessage = reason) }
            return MigrationApplyResult.Blocked(reason)
        }
        return apply(currentRepository()?.listFiles().orEmpty())
    }

    suspend fun applySingle(version: String): MigrationApplyResult {
        if (_state.value.isApplying) return MigrationApplyResult.Blocked(APPLY_RUNNING)
        val entry = _state.value.entries.firstOrNull { it.file.version == version }
            ?: return MigrationApplyResult.Blocked("Migration file not found")
        applyBlockReason()?.let { return MigrationApplyResult.Blocked(it) }
        return apply(listOf(entry.file))
    }

    /** Runs [files] through the runner, shows the action being sent, and leaves the structured outcome when it did not succeed. */
    private suspend fun apply(files: List<MigrationFile>): MigrationApplyResult {
        // Two callers can pass the cheap check above together; only one wins the flag.
        while (true) {
            val current = _state.value
            if (current.isApplying) return MigrationApplyResult.Blocked(APPLY_RUNNING)
            if (_state.compareAndSet(current, current.copy(isApplying = true, errorMessage = null, applyOutcome = null, applyProgress = null))) break
        }
        val result = runner.applyPending(profileName, files, hasConfigurePermission, controlLevel, vhost, userTags) { step ->
            _state.update { it.copy(applyProgress = ApplyProgress(step.version, step.index, step.total)) }
        }
        val outcome = result.toOutcome()
        val blockedReason = (result as? MigrationApplyResult.Blocked)?.reason
        _state.update {
            it.copy(applyOutcome = outcome, errorMessage = blockedReason, applyProgress = null)
        }
        if (result !is MigrationApplyResult.Blocked) loadMigrations()
        _state.update { it.copy(isApplying = false) }
        return result
    }

    /** The fixed reason nothing may be applied right now, or null: the history cannot be trusted, or there is no folder to read from. */
    private fun applyBlockReason(): String? {
        val current = _state.value
        return when {
            migrationUnavailableReason != null -> unavailableText(migrationUnavailableReason)
            current.historyProblem != null -> HISTORY_UNREADABLE
            current.folder is MigrationFolderState.ChooseFolder -> NO_FOLDER
            current.folder is MigrationFolderState.FolderUnavailable -> current.folder.reason
            else -> null
        }
    }

    suspend fun importFile(rawJson: String): MigrationValidationResult {
        val repository = writableRepository() ?: return refuseWrite()
        val result = repository.importFile(rawJson)
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
        val repository = writableRepository()
        if (repository == null) {
            refuseWrite()
            return null
        }
        val actions = readTopologyActions()
        if (actions == null) {
            _state.update { it.copy(errorMessage = TOPOLOGY_NOT_READ) }
            return null
        }
        val exported = try {
            repository.exportTopology(
                version = repository.nextVersion(),
                description = "Exported broker topology",
                actions = actions,
            )
        } catch (_: MigrationWriteException) {
            _state.update { it.copy(errorMessage = FILE_NOT_WRITTEN) }
            return null
        }
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

    private fun writableRepository(): MigrationFileRepository? = if (_state.value.isImportEnabled) currentRepository() else null

    private fun refuseWrite(): MigrationValidationResult.Invalid {
        val reason = (_state.value.folder as? MigrationFolderState.FolderReadOnly)?.reason ?: FOLDER_NOT_WRITABLE
        _state.update { it.copy(errorMessage = reason) }
        return MigrationValidationResult.Invalid(reason)
    }

    /** Asks the user for a folder (the dialog starts in the current one) and, unless cancelled, remembers and lists it. */
    fun chooseFolder() {
        val ports = folderPorts ?: return
        scope.launch {
            val picked = ports.chooser.choose(ports.store.folder) ?: return@launch
            ports.store.folder = picked
            loadMigrations()
        }
    }

    fun changeFolder() = chooseFolder()

    /** Reads the folder and the history again; nothing is created or chosen. */
    fun retry() = loadMigrations()

    /** Asks before the unreadable history file is moved aside; nothing moves until [confirmEmptyHistory]. */
    fun startWithEmptyHistory() {
        val ports = folderPorts ?: return
        if (_state.value.historyProblem == null) return
        val question = "Move migration-history.json to ${ports.history.asideFileName()} and start with an empty history? The old file is kept."
        _state.update { it.copy(confirmation = question) }
    }

    fun confirmEmptyHistory() {
        val ports = folderPorts ?: return
        if (_state.value.confirmation == null) return
        _state.update { it.copy(confirmation = null) }
        scope.launch {
            when (ports.history.moveAside()) {
                is HistoryMoveResult.Moved -> loadMigrations()
                is HistoryMoveResult.Failed -> _state.update { it.copy(errorMessage = HISTORY_NOT_MOVED) }
            }
        }
    }

    fun cancelEmptyHistory() {
        _state.update { it.copy(confirmation = null) }
    }

    /** Saves an edited file through the folder in use and answers the parser's verdict; an `Invalid` reason is shown in `errorMessage`, never ignored. */
    suspend fun updateFile(version: String, rawJson: String): MigrationValidationResult {
        if (currentRepository() == null) {
            _state.update { it.copy(errorMessage = NO_FOLDER) }
            return MigrationValidationResult.Invalid(NO_FOLDER)
        }
        val repository = writableRepository() ?: return refuseWrite()
        val result = repository.updateFile(version, rawJson)
        when (result) {
            is MigrationValidationResult.Valid -> loadMigrations()
            is MigrationValidationResult.Invalid -> _state.update { it.copy(errorMessage = result.reason) }
        }
        return result
    }

    /** Clears the last apply's outcome, any progress and the error text. */
    fun dismissOutcome() {
        _state.update { it.copy(applyOutcome = null, applyProgress = null, errorMessage = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null, applyOutcome = null) }
    }

    private companion object {
        const val TOPOLOGY_NOT_READ = "The topology could not be read"
        const val FILE_NOT_WRITTEN = "The file could not be written"
        const val HISTORY_UNREADABLE = "The applied history cannot be read, so nothing can be applied"
        const val HISTORY_NOT_MOVED = "The history file could not be moved aside; it was not changed"
        const val APPLY_RUNNING = "An apply is already running"
        const val NO_FOLDER = "Choose a migrations folder first"
        const val FOLDER_NOT_WRITABLE = "Choose a usable migrations folder first"
    }
}
