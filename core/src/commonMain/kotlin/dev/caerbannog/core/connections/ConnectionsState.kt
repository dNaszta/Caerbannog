package dev.caerbannog.core.connections

/**
 * State for the Connections screen (unidirectional data flow: this is the one thing that flows into
 * the screen; [ConnectionsViewModel]'s methods are the events that flow back out).
 *
 * [selectedProfileName] null means the "New connection" panel (per `docs/ux/Main.dc.html`'s empty
 * edit-panel state); non-null means the "Editing" panel for that saved profile.
 * [pendingDeleteName] models the delete-confirm step as a nullable name rather than a boolean flag
 * plus a separately tracked name, since [ConnectionsViewModel.requestDelete] already carries the name.
 */
data class ConnectionsState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedProfileName: String? = null,
    val pendingDeleteName: String? = null,
    val lastCheckResult: ConnectionCheckResult? = null,
    val lastSaveResult: SaveConnectionResult? = null,
    val lastCaCertificateValidationResult: CaCertificateValidationResult? = null,
) {
    val selectedProfile: ConnectionProfile?
        get() = profiles.firstOrNull { it.name == selectedProfileName }
}
