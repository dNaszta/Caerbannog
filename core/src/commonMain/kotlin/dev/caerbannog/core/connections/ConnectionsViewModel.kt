package dev.caerbannog.core.connections

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * State holder for the Connections screen: [state] flows in, these methods are the events that flow
 * back out (unidirectional data flow). Holds no broker/network/filesystem code itself - everything
 * broker-shaped goes through the [ConnectionProfileRepository], [SecretStore],
 * [BrokerReachabilityChecker] and [CaCertificateValidator] interfaces passed in, so this class runs
 * unchanged against fakes (the BDD layer) and real `data-rabbit` implementations.
 */
class ConnectionsViewModel(
    private val repository: ConnectionProfileRepository,
    private val secretStore: SecretStore,
    private val reachabilityChecker: BrokerReachabilityChecker,
    private val caCertificateValidator: CaCertificateValidator,
    private val session: ConnectionSession? = null,
    private val active: ActiveConnection? = null,
) {
    private val _state = MutableStateFlow(ConnectionsState(profiles = repository.list()))

    // Counts panel switches; a late CA validation result compares it to know the user moved on. Touched on the UI thread only.
    private var panelEpoch: Int = 0

    // The session tells this view model after each of its changes; the view model copies the latest session
    // state into its own single MutableStateFlow. No scope or custom StateFlow is needed, and `state.value` is
    // already current when the session call that changed it returns. Problem solved: derived state without a
    // coroutine scope that someone must close.
    private val stopObserving: () -> Unit = session?.let { live ->
        val unregister = live.observe { applySession(live) }
        applySession(live)
        unregister
    } ?: {}

    /**
     * What the screen shows: the view model's own state with the permission checks and the current connection
     * of the [ConnectionSession] laid over it (the session stays the one source of truth). Plain
     * [StateFlow]: collect it or read `value` synchronously.
     */
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    /**
     * Stops following the session. Optional: the view model and the session live for the whole window, so the
     * composition root need not call it; call it when a view model is dropped while its session lives on.
     */
    fun close() = stopObserving()

    // Reads the session inside the transform, so a retry or a late call always applies the latest state.
    private fun applySession(live: ConnectionSession) {
        _state.update {
            val seen = live.state.value
            it.copy(permissionChecks = seen.statuses, currentConnection = seen.current)
        }
    }

    /** Adds a brand-new profile; without an explicit level it starts at [TopologyControlLevel.READ_ONLY]. */
    fun addConnection(input: ConnectionProfileInput): SaveConnectionResult {
        val profile = ConnectionProfile(
            name = input.name,
            host = input.host,
            virtualHost = input.virtualHost,
            username = input.username,
            streamPort = input.streamPort,
            managementPort = input.managementPort,
            tls = input.tls ?: true,
            topologyControlLevel = input.topologyControlLevel ?: TopologyControlLevel.READ_ONLY,
            declaredPermissions = (input.declaredPermissions as? DeclaredPermissionsEdit.Set)?.permissions,
        )
        val result = repository.add(profile)
        if (result is SaveConnectionResult.Saved) {
            storePasswordIfPresent(input.name, input.password)
        }
        refreshAfter(result)
        return result
    }

    /**
     * Persists edits to a saved profile, including a possible rename. A rename cannot be a plain
     * `repository.update(...)` (the repository is keyed by name), so it is resolved as add-then-delete:
     * the new name is added first, and the old name (profile and secret) is only removed once that
     * succeeds - a failed rename (the new name collides with another profile) therefore leaves the
     * original, under its original name, untouched.
     */
    fun updateConnection(originalName: String, input: ConnectionProfileInput): SaveConnectionResult {
        val original = profileNamed(originalName)
        val updated = original.copy(
            name = input.name,
            host = input.host,
            virtualHost = input.virtualHost,
            username = input.username,
            streamPort = input.streamPort,
            managementPort = input.managementPort,
            tls = input.tls ?: original.tls,
            topologyControlLevel = input.topologyControlLevel ?: original.topologyControlLevel,
            declaredPermissions = when (val edit = input.declaredPermissions) {
                DeclaredPermissionsEdit.Keep -> original.declaredPermissions
                DeclaredPermissionsEdit.Clear -> null
                is DeclaredPermissionsEdit.Set -> edit.permissions
            },
        )

        val result = if (input.name == originalName) {
            repository.update(updated).also {
                if (it is SaveConnectionResult.Saved) storePasswordIfPresent(originalName, input.password)
            }
        } else {
            renameTo(originalName, updated, input.password)
        }

        refreshAfter(result)
        // The held connection was made with the old settings (spec: closed on edit or rename).
        if (result is SaveConnectionResult.Saved) active?.profileChanged(originalName)
        return result
    }

    /**
     * Saves like [updateConnection] and, only when the save succeeded, re-runs the permission check on
     * the saved profile. A failed save runs no check and keeps the earlier result.
     */
    suspend fun saveProfileChanges(originalName: String, input: ConnectionProfileInput): SaveConnectionResult {
        val result = updateConnection(originalName, input)
        if (result is SaveConnectionResult.Saved && session != null) {
            val name = result.profile.name
            // The result of the old name belongs to the old environment; forget also clears current if it was that name.
            if (name != originalName) session.forget(originalName)
            session.open(result.profile)
        }
        return result
    }

    /**
     * Makes the saved profile [name] the current connection and checks its permissions; with an
     * [ActiveConnection] it also opens the clients and holds the connection. An unknown name does nothing.
     * The Connect (and Retry) of the app bar chip popup.
     */
    suspend fun connect(name: String) {
        val profile = repository.list().firstOrNull { it.name == name } ?: return
        if (active != null) {
            active.connect(profile)
            return
        }
        val activeSession = session ?: return
        activeSession.select(name)
        activeSession.open(profile)
    }

    /**
     * The edit panel's button: reachability first, then the permission check. Both always run, so an
     * unreachable broker shows up as Unreadable instead of a stale pill.
     */
    suspend fun checkConnectionAndPermissions(name: String) {
        if (repository.list().none { it.name == name }) return
        checkConnection(name)
        val activeSession = session ?: return
        // The profile can be deleted or renamed while the reachability check was running.
        val profile = repository.list().firstOrNull { it.name == name } ?: return
        activeSession.open(profile)
    }

    private fun renameTo(originalName: String, updated: ConnectionProfile, newPassword: String?): SaveConnectionResult {
        val result = repository.add(updated)
        if (result is SaveConnectionResult.Saved) {
            val passwordToKeep = newPassword?.takeIf { it.isNotBlank() } ?: secretStore.retrieve(originalName)
            if (passwordToKeep != null) secretStore.store(updated.name, passwordToKeep)
            secretStore.delete(originalName)
            repository.delete(originalName)
        }
        return result
    }

    /** Raises or lowers the self-constraint ceiling for an already-saved profile. */
    fun setTopologyControlLevel(name: String, level: TopologyControlLevel): SaveConnectionResult {
        val result = repository.update(profileNamed(name).copy(topologyControlLevel = level))
        refreshAfter(result)
        return result
    }

    /** Validates [filePath] and, only if it is a valid certificate, saves it on the named profile. */
    fun setCaCertificate(name: String, filePath: String): CaCertificateValidationResult =
        applyCaCertificate(name, filePath, caCertificateValidator.validate(filePath))

    private fun applyCaCertificate(name: String, filePath: String, validation: CaCertificateValidationResult): CaCertificateValidationResult {
        if (validation is CaCertificateValidationResult.Valid) {
            repository.update(profileNamed(name).copy(caCertificatePath = filePath))
            _state.update { it.copy(profiles = repository.list(), lastCaCertificateValidationResult = validation) }
            // The held connection trusts the old CA.
            active?.profileChanged(name)
        } else {
            _state.update { it.copy(lastCaCertificateValidationResult = validation) }
        }
        return validation
    }

    /** Validates a candidate CA certificate file before any profile exists to attach it to. */
    fun validateCaCertificateFile(filePath: String): CaCertificateValidationResult =
        applyCaCertificateFile(caCertificateValidator.validate(filePath))

    private fun applyCaCertificateFile(validation: CaCertificateValidationResult): CaCertificateValidationResult {
        _state.update { it.copy(lastCaCertificateValidationResult = validation) }
        return validation
    }

    /**
     * The form's CA validation (M2-14, review item a of M2-05): reads the file on [dispatcher] (the validator does file IO, so
     * never on the UI thread), then applies the result like [setCaCertificate] ([name] set) or [validateCaCertificateFile]
     * ([name] null). A result is dropped when the user opened another panel meanwhile, so a late answer for one profile is never
     * shown on another. Returns null for a dropped result.
     */
    suspend fun validateCaCertificateOffThread(name: String?, filePath: String, dispatcher: CoroutineDispatcher): CaCertificateValidationResult? {
        val panel = panelEpoch
        val validation = withContext(dispatcher) { caCertificateValidator.validate(filePath) }
        if (panel != panelEpoch) return null
        return if (name == null) applyCaCertificateFile(validation) else applyCaCertificate(name, filePath, validation)
    }

    /** Proves the named profile's stream and management endpoints are reachable over TLS. */
    suspend fun checkConnection(name: String): ConnectionCheckResult {
        val selectedAtStart = _state.value.selectedProfileName
        val result = reachabilityChecker.check(profileNamed(name))
        // The banner belongs to one profile: a result that arrives after that profile was deleted, or after the
        // selection moved, is returned to the caller but never shown.
        val stillExists = repository.list().any { p -> p.name == name }
        _state.update {
            if (it.selectedProfileName == selectedAtStart && stillExists) {
                it.copy(lastCheckResult = result)
            } else {
                it
            }
        }
        return result
    }

    fun requestDelete(name: String) {
        _state.update { it.copy(pendingDeleteName = name) }
    }

    fun confirmDelete() {
        val name = _state.value.pendingDeleteName ?: return
        repository.delete(name)
        secretStore.delete(name)
        session?.forget(name)
        active?.profileDeleted(name)
        _state.update {
            it.copy(
                profiles = repository.list(),
                pendingDeleteName = null,
                selectedProfileName = it.selectedProfileName.takeUnless { selected -> selected == name },
            )
        }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDeleteName = null) }
    }

    // --- Pure UI-selection state: no Gherkin scenario exercises this (Cucumber drives domain
    // behaviour, not Compose selection state), following the same state-in/events-out shape as the
    // rest of this view model - see docs/tasks/M1-02-connection-profiles.md's Design notes, decision 4.

    fun selectConnection(name: String) {
        panelEpoch++
        _state.update { it.copy(selectedProfileName = name).withoutResultBanners() }
    }

    fun startNewConnection() {
        panelEpoch++
        _state.update { it.copy(selectedProfileName = null).withoutResultBanners() }
    }

    // Banners belong to the profile they were produced for; a switch must not carry them over.
    private fun ConnectionsState.withoutResultBanners(): ConnectionsState = copy(
        lastCheckResult = null,
        lastSaveResult = null,
        lastCaCertificateValidationResult = null,
    )

    private fun storePasswordIfPresent(name: String, password: String?) {
        if (!password.isNullOrBlank()) secretStore.store(name, password)
    }

    private fun refreshAfter(result: SaveConnectionResult) {
        _state.update {
            it.copy(
                profiles = repository.list(),
                lastSaveResult = result,
                selectedProfileName = if (result is SaveConnectionResult.Saved) result.profile.name else it.selectedProfileName,
            )
        }
    }

    // Reads the repository, the source of truth, so a profile saved outside this view model is found too.
    private fun profileNamed(name: String): ConnectionProfile =
        repository.list().firstOrNull { it.name == name }
            ?: error("No saved connection named '$name'")
}
