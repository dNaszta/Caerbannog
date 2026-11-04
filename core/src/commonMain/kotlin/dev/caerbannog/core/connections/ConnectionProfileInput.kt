package dev.caerbannog.core.connections

/**
 * Editable fields for adding or updating a connection profile from the UI's edit panel.
 *
 * [password] is nullable: for [ConnectionsViewModel.updateConnection], null/blank means "leave the
 * stored password unchanged" rather than "clear it" - a user editing host/username does not have to
 * retype a password they are not changing.
 *
 * [topologyControlLevel] is nullable for the same reason: on [ConnectionsViewModel.addConnection] null
 * means [TopologyControlLevel.READ_ONLY]; on [ConnectionsViewModel.updateConnection] null means "keep
 * the saved level", so a caller that does not know the field cannot silently lower a saved ceiling.
 *
 * Deliberately does not carry [ConnectionProfile.caCertificatePath]: it has its own dedicated setter,
 * [ConnectionsViewModel.setCaCertificate]. [ConnectionsViewModel.setTopologyControlLevel] stays for
 * programmatic changes of the level on a saved profile.
 */
data class ConnectionProfileInput(
    val name: String,
    val host: String,
    val virtualHost: String,
    val username: String,
    val password: String?,
    val streamPort: Int = 5551,
    val managementPort: Int = 15671,
    val topologyControlLevel: TopologyControlLevel? = null,
)
