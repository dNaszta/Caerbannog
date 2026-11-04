package dev.caerbannog.core.connections

/**
 * A saved RabbitMQ broker connection: its own environment, independent of any other profile.
 *
 * TLS is not a setting on this model - it is deliberately the only mode the app can express (see
 * `01_connections.feature`'s "TLS is always on": there must be no field here that could be read as a
 * toggle, not merely one defaulted to on).
 *
 * The password is never a field here: it lives in [SecretStore], keyed by [name], so nothing that
 * serializes a ConnectionProfile (the JSON file `data-rabbit` writes, logs, `toString()`) can ever
 * contain it. [caCertificatePath] is different - it is public key material, not a secret, so it is
 * kept as a plain file-path reference here.
 */
data class ConnectionProfile(
    val name: String,
    val host: String,
    val virtualHost: String,
    val username: String,
    val streamPort: Int = 5551,
    val managementPort: Int = 15671,
    val caCertificatePath: String? = null,
    val topologyControlLevel: TopologyControlLevel = TopologyControlLevel.READ_ONLY,
    /** The user's own claim, used only when permissions cannot be read. Not persisted yet (M1-06). */
    val declaredPermissions: DeclaredPermissions? = null,
)
