package dev.caerbannog.ui.connections

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import org.junit.jupiter.api.Test

/**
 * Ordinary (non-Gherkin) coverage for the Compose Connections screen built by this task: no scenario
 * in `01_connections.feature` exercises the screen directly (Cucumber drives domain behaviour through
 * `ConnectionsViewModel`, not Compose rendering), so this is plain TDD-style coverage for the one
 * piece of UI production code this task adds, using minimal local fakes (this module cannot depend on
 * `acceptance-tests`, nor should it - a Compose screen test has no business depending on a
 * Cucumber-step module).
 */
class ConnectionsScreenUiTest {

    private class InMemoryRepository : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list(): List<ConnectionProfile> = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class NoOpSecretStore : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = null
        override fun delete(profileName: String) = Unit
    }

    private class AlwaysReachableChecker : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class AlwaysValidCertificate : CaCertificateValidator {
        override fun validate(filePath: String): CaCertificateValidationResult = CaCertificateValidationResult.Valid
    }

    private fun testViewModel(seed: ConnectionProfile? = null): ConnectionsViewModel {
        val repository = InMemoryRepository()
        seed?.let { repository.add(it) }
        return ConnectionsViewModel(repository, NoOpSecretStore(), AlwaysReachableChecker(), AlwaysValidCertificate())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `empty state shows no saved connections`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        onNodeWithText("No saved connections yet.").assertExists()
        onNodeWithText("Permission check has not run yet.").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting a saved profile shows its fields in the editing panel`() = runDesktopComposeUiTest {
        val profile = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(seed = profile)) }

        onNodeWithText("Select").performClick()

        onNodeWithText("Edit connection").assertExists()
        onNodeWithText("Delete profile").assertExists()
    }
}
