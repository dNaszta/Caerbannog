package dev.caerbannog.datarabbit

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import dev.caerbannog.core.connections.SecretStore

/**
 * OS-keychain-backed [SecretStore] (`com.github.javakeyring:java-keyring`), replacing M1-01's
 * throwaway spike key (`KeyringRoundTripSpikeTest`) with real [dev.caerbannog.core.connections.
 * ConnectionProfile] wiring: every saved connection's password lives under the one fixed [service]
 * name in the OS keychain, keyed by profile name as the "account".
 *
 * A new [Keyring] handle is opened and closed per call (`Keyring.create().use { ... }`, the same
 * pattern `KeyringRoundTripSpikeTest` proved) rather than held open for this store's lifetime - there
 * is no evidence a long-lived handle is required, or safe across every OS keychain backend.
 */
class JavaKeyringSecretStore(
    private val service: String = "dev.caerbannog.connections",
) : SecretStore {

    override fun store(profileName: String, password: String) {
        Keyring.create().use { it.setPassword(service, profileName, password) }
    }

    override fun retrieve(profileName: String): String? =
        Keyring.create().use {
            try {
                it.getPassword(service, profileName)
            } catch (e: PasswordAccessException) {
                null
            }
        }

    override fun delete(profileName: String) {
        Keyring.create().use {
            try {
                it.deletePassword(service, profileName)
            } catch (e: PasswordAccessException) {
                // Already absent: delete is idempotent from the caller's point of view (e.g. deleting
                // a profile that never had a password saved, or deleting twice).
            }
        }
    }
}
