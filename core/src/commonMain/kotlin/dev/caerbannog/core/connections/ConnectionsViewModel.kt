package dev.caerbannog.core.connections

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

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
) {
    private val _state = MutableStateFlow(ConnectionsState(profiles = repository.list()))
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    /** Adds a brand-new profile; without an explicit level it starts at [TopologyControlLevel.READ_ONLY]. */
    fun addConnection(input: ConnectionProfileInput): SaveConnectionResult {
        val profile = ConnectionProfile(
            name = input.name,
            host = input.host,
            virtualHost = input.virtualHost,
            username = input.username,
            streamPort = input.streamPort,
            managementPort = input.managementPort,
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
            if (name != originalName) {
                invalidateChecks(originalName)
                session.forget(originalName)
            }
            runCheck(session, result.profile, replacing = originalName)
        }
        return result
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
        runCheck(activeSession, profile, replacing = name)
    }

    // Every check gets a token; its Checking and Done entries are written only while that token is still the
    // current one for the name. Delete, rename and a newer check make it stale, so a late result can never
    // resurrect a removed profile or show up on a new profile that reused the name.
    private suspend fun runCheck(activeSession: ConnectionSession, profile: ConnectionProfile, replacing: String) {
        val name = profile.name
        val token = beginCheck(name)
        writeIfCurrent(name, token) { it - replacing + (name to PermissionCheckStatus.Checking) }
        val permissions = activeSession.open(profile)
        if (!writeIfCurrent(name, token) { it + (name to PermissionCheckStatus.Done(permissions)) } && tokens.value.byName[name] == null) {
            activeSession.forget(name)
        }
    }

    private data class Tokens(val last: Long = 0, val byName: Map<String, Long> = emptyMap())

    private val tokens = MutableStateFlow(Tokens())

    private fun beginCheck(name: String): Long =
        tokens.updateAndGet { it.copy(last = it.last + 1, byName = it.byName + (name to it.last + 1)) }.last

    private fun invalidateChecks(name: String) {
        tokens.update { it.copy(byName = it.byName - name) }
    }

    private fun writeIfCurrent(
        name: String,
        token: Long,
        change: (Map<String, PermissionCheckStatus>) -> Map<String, PermissionCheckStatus>,
    ): Boolean {
        var written = false
        _state.update {
            written = tokens.value.byName[name] == token
            if (written) it.copy(permissionChecks = change(it.permissionChecks)) else it
        }
        return written
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
    fun setCaCertificate(name: String, filePath: String): CaCertificateValidationResult {
        val validation = caCertificateValidator.validate(filePath)
        if (validation is CaCertificateValidationResult.Valid) {
            repository.update(profileNamed(name).copy(caCertificatePath = filePath))
            _state.update { it.copy(profiles = repository.list(), lastCaCertificateValidationResult = validation) }
        } else {
            _state.update { it.copy(lastCaCertificateValidationResult = validation) }
        }
        return validation
    }

    /** Validates a candidate CA certificate file before any profile exists to attach it to. */
    fun validateCaCertificateFile(filePath: String): CaCertificateValidationResult {
        val validation = caCertificateValidator.validate(filePath)
        _state.update { it.copy(lastCaCertificateValidationResult = validation) }
        return validation
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
        invalidateChecks(name)
        session?.forget(name)
        _state.update {
            it.copy(
                profiles = repository.list(),
                permissionChecks = it.permissionChecks - name,
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
        _state.update { it.copy(selectedProfileName = name).withoutResultBanners() }
    }

    fun startNewConnection() {
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
