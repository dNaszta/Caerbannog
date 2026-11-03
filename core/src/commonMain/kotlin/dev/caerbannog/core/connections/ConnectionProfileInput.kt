package dev.caerbannog.core.connections

/**
 * Editable fields for adding or updating a connection profile from the UI's edit panel.
 *
 * [password] is nullable: for [ConnectionsViewModel.updateConnection], null/blank means "leave the
 * stored password unchanged" rather than "clear it" - a user editing host/username does not have to
 * retype a password they are not changing.
 *
 * Deliberately does not carry [ConnectionProfile.caCertificatePath] or
 * [ConnectionProfile.topologyControlLevel]: those have their own dedicated setters on
 * [ConnectionsViewModel] ([ConnectionsViewModel.setCaCertificate],
 * [ConnectionsViewModel.setTopologyControlLevel]).
 */
data class ConnectionProfileInput(
    val name: String,
    val host: String,
    val virtualHost: String,
    val username: String,
    val password: String?,
    val streamPort: Int = 5551,
    val managementPort: Int = 15671,
)
