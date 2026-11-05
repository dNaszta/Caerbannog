package dev.caerbannog.core.connections

/**
 * A saved RabbitMQ broker connection: its own environment, independent of any other profile.
 *
 * [tls] is the per-profile TLS switch (M2-14; it replaces the old "TLS is always on" rule). It defaults to on, and
 * nothing may fall back from TLS to plain, or the other way, on its own: the profile switch is the only source of the
 * mode. With it off, [caCertificatePath] is kept but unused.
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
    val tls: Boolean = true,
    val caCertificatePath: String? = null,
    val topologyControlLevel: TopologyControlLevel = TopologyControlLevel.READ_ONLY,
    /** The user's own claim, used only when permissions cannot be read. Saved with the profile; applied on Save through [ConnectionProfileInput.declaredPermissions]. */
    val declaredPermissions: DeclaredPermissions? = null,
)
