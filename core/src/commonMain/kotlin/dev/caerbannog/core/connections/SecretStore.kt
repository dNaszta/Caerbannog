package dev.caerbannog.core.connections

/**
 * Stores a connection profile's password outside the profile file entirely, keyed by profile name.
 * The real implementation (`data-rabbit`'s `JavaKeyringSecretStore`) keeps this in the OS keychain;
 * nothing in `core` ever writes a password to disk itself.
 */
interface SecretStore {
    fun store(profileName: String, password: String)
    fun retrieve(profileName: String): String?
    fun delete(profileName: String)
}
