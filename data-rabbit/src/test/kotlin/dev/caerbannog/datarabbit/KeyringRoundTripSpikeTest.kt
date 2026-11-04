package dev.caerbannog.datarabbit

import com.github.javakeyring.Keyring
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import com.github.javakeyring.PasswordAccessException

/**
 * M1-01 skeleton spike, Acceptance item 7: "One keychain round-trip test (write, read back, delete)
 * passes locally on macOS; Windows is noted as unverified until run on Windows hardware or CI."
 *
 * Per the task brief's Design notes: "do not reuse or invent the real `ConnectionProfile` domain
 * model for this test... Use a throwaway key/value pair local to the spike test, and make sure the
 * test deletes what it wrote (no leftover entries in the OS keychain after a run)." This test is
 * self-contained: it writes its own throwaway secret and always deletes it again, even if an
 * assertion fails.
 *
 * Candidate library per the brief: `com.github.javakeyring:java-keyring`. Expected to fail to
 * compile right now: `data-rabbit/build.gradle.kts` declares no keychain dependency yet - this is
 * the engineering call the senior developer confirms (or replaces) while making this pass, recording
 * the final choice in docs/spec.md's "Settings and secrets" row.
 */
@Tag("keychain")
class KeyringRoundTripSpikeTest {

    private val service = "dev.caerbannog.spike.m1-01"
    private val account = "throwaway-spike-account"

    @Test
    fun `write, read back and delete a throwaway secret round trips`() {
        Keyring.create().use { keyring ->
            try {
                keyring.setPassword(service, account, "throwaway-secret-value")

                val readBack = keyring.getPassword(service, account)
                assertEquals("throwaway-secret-value", readBack)
            } finally {
                // Always clean up: no leftover entries in the OS keychain after this test runs,
                // regardless of whether the assertion above passed.
                runCatching { keyring.deletePassword(service, account) }
            }

            // Confirm the delete actually happened: reading again must fail.
            assertThrows(PasswordAccessException::class.java) {
                keyring.getPassword(service, account)
            }
        }
    }
}
