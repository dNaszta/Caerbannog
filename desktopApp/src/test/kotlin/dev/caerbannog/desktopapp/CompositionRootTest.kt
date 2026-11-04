package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M1-05: proves `createConnectionsViewModel` assembles the real JSON-file repository. Safety rule from
 * the brief: this test never uses the default parameters, `JavaKeyringSecretStore` or `defaultFile()`,
 * so the real `~/.caerbannog` and the OS keychain are untouched. Only a temp file and an in-memory
 * secret store are used; no broker is contacted.
 */
class CompositionRootTest {

    @TempDir
    lateinit var dir: File

    private class InMemorySecretStore : SecretStore {
        private val secrets = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { secrets[profileName] = password }
        override fun retrieve(profileName: String): String? = secrets[profileName]
        override fun delete(profileName: String) { secrets.remove(profileName) }
    }

    private val input = ConnectionProfileInput(
        name = "m1-05-test-profile",
        host = "localhost",
        virtualHost = "/",
        username = "guest",
        password = "not-a-real-secret",
    )

    @Test
    fun `a fresh profiles file gives an empty view model`() {
        val file = File(dir, "connections.json")

        val viewModel = createConnectionsViewModel(file, InMemorySecretStore())

        assertEquals(emptyList(), viewModel.state.value.profiles.map { it.name })
    }

    @Test
    fun `adding a profile writes the file and a second call reads it back`() {
        val file = File(dir, "connections.json")
        val first = createConnectionsViewModel(file, InMemorySecretStore())

        val result = first.addConnection(input)

        assertIs<SaveConnectionResult.Saved>(result)
        assertTrue(file.exists(), "expected the profiles file to be written at ${file.path}")
        assertTrue(file.readText().contains(input.name), "expected ${input.name} in the file, got: ${file.readText()}")

        val second = createConnectionsViewModel(file, InMemorySecretStore())

        assertEquals(listOf(input.name), second.state.value.profiles.map { it.name })
        assertEquals(input.host, second.state.value.profiles.single().host)
    }

    @Test
    fun `the password goes to the injected secret store, not into the file`() {
        val file = File(dir, "connections.json")
        val secrets = InMemorySecretStore()

        createConnectionsViewModel(file, secrets).addConnection(input)

        assertEquals(input.password, secrets.retrieve(input.name))
        assertTrue(!file.readText().contains(input.password!!), "password must not be written to the profiles file")
    }

    // --- M1-07: the view model is wired to a ConnectionSession built from the injected checker ------
    // Fake checkers only: no socket, no network, deterministic.

    private class FakeReachability : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class FakePermissions(val result: PermissionCheckResult) : PermissionChecker {
        val passwords = mutableListOf<String>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            passwords += password
            return result
        }
    }

    @Test
    fun `the view model shows the injected checker's result through the session`() = runBlocking {
        val secrets = InMemorySecretStore()
        val fake = FakePermissions(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = false))
        val viewModel = createConnectionsViewModel(
            File(dir, "connections.json"), secrets, permissionChecker = fake, reachabilityChecker = FakeReachability(),
        )
        viewModel.addConnection(input)

        viewModel.checkConnectionAndPermissions(input.name)

        assertEquals(
            PermissionCheckStatus.Done(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = false)),
            viewModel.state.value.permissionChecks[input.name],
        )
        assertEquals(ConnectionCheckResult.BothReachable, viewModel.state.value.lastCheckResult)
        assertEquals(listOf(input.password), fake.passwords, "the session must read the password from the same secret store")
    }

    @Test
    fun `a fresh view model has not checked anything`() {
        val fake = FakePermissions(PermissionCheckResult.Checked(true, true))
        val viewModel = createConnectionsViewModel(
            File(dir, "connections.json"), InMemorySecretStore(), permissionChecker = fake, reachabilityChecker = FakeReachability(),
        )

        assertEquals(emptyMap(), viewModel.state.value.permissionChecks)
        assertTrue(fake.passwords.isEmpty(), "no check at start-up")
    }
}
