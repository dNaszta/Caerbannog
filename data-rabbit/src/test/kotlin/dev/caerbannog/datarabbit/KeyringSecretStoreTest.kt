package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * docs/tasks/M1-02-connection-profiles.md, Scope: "a java-keyring-backed implementation [of
 * SecretStore], replacing M1-01's throwaway spike key with real ConnectionProfile wiring."
 *
 * Why this test exists separately from the BDD layer: 01_connections.feature's "Passwords are not
 * stored in the profile file" scenario runs against an in-memory FakeSecretStore (see
 * acceptance-tests, Design notes - fakes are scoped to that feature file, no broker/filesystem/
 * keychain needed there). A fake can honestly prove the application *routes* passwords through the
 * SecretStore abstraction, but it cannot prove anything about the real OS keychain - asserting
 * "the password is kept in the operating system's secret store" against a fake would be exactly the
 * "fake that is too forgiving" trap. This test, plus the already-passing M1-01
 * KeyringRoundTripSpikeTest, is where that real claim is actually proven: a round trip keyed the way
 * the real ConnectionProfile wiring will use it (by profile name), with guaranteed cleanup.
 *
 * Expected to fail to compile right now: dev.caerbannog.datarabbit.JavaKeyringSecretStore (the real
 * SecretStore implementation this task adds) does not exist yet, and neither does
 * dev.caerbannog.core.connections.SecretStore in `core`.
 */
class KeyringSecretStoreTest {

    private val throwawayProfileName = "m1-02-test-developer-throwaway-profile"

    @Test
    fun `store, retrieve and delete a password keyed by profile name round trips through the real OS keychain`() {
        val secretStore = JavaKeyringSecretStore()
        try {
            secretStore.store(throwawayProfileName, "throwaway-s3cret")

            assertEquals("throwaway-s3cret", secretStore.retrieve(throwawayProfileName))
        } finally {
            // No leftover entries in the OS keychain after this test runs, regardless of outcome -
            // same discipline as M1-01's KeyringRoundTripSpikeTest.
            secretStore.delete(throwawayProfileName)
        }

        assertNull(secretStore.retrieve(throwawayProfileName)) {
            "Expected the password to be gone from the OS keychain after delete()"
        }
    }
}
