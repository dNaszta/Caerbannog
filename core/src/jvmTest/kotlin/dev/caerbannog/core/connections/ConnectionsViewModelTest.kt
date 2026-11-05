package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.GatedCaCertificateValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives `ConnectionsViewModel.updateConnection`/`renameTo` - the rename/edit orchestration pinned at
 * `docs/tasks/M1-02-connection-profiles.md`'s step 2, decision 1 and decision 4. No Gherkin scenario in
 * `01_connections.feature` exercises a rename (see that same Design notes section and Open points: it
 * has no Gherkin scenario, but is "reachable the first time anyone edits a saved profile's name field"
 * and the senior developer "must not skip it"), so this is the ordinary TDD-style unit coverage for
 * that in-scope, non-trivial logic, using minimal local fakes - `core` must not depend on
 * `acceptance-tests` or `ui` (same reasoning as `ui`'s `ConnectionsScreenUiTest`, whose fake style this
 * follows, but written fresh here since `core` cannot see that module either).
 *
 * Decision 1's rule under test: on a name change, `add()` the new name first; only on success does the
 * old name (profile and secret) get deleted - so a rename that collides with an existing name is
 * non-destructive (the original, under its original name, is left completely untouched).
 */
class ConnectionsViewModelTest {

    private class InMemoryRepository : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>()

        override fun list(): List<ConnectionProfile> = profiles.values.toList()

        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }

        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            check(profiles.containsKey(profile.name)) { "No saved connection named '${profile.name}'" }
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }

        override fun delete(name: String) {
            profiles.remove(name)
        }
    }

    private class InMemorySecretStore : SecretStore {
        private val secrets = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) {
            secrets[profileName] = password
        }
        override fun retrieve(profileName: String): String? = secrets[profileName]
        override fun delete(profileName: String) {
            secrets.remove(profileName)
        }
    }

    // Not exercised by updateConnection/renameTo at all - a non-suspending throw proves that.
    private class UncalledReachabilityChecker : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult =
            error("updateConnection must never reach the reachability checker")
    }

    private class UncalledCaCertificateValidator : CaCertificateValidator {
        override fun validate(filePath: String): CaCertificateValidationResult =
            error("updateConnection must never reach the CA certificate validator")
    }

    private fun viewModel(repository: ConnectionProfileRepository, secretStore: SecretStore) =
        ConnectionsViewModel(repository, secretStore, UncalledReachabilityChecker(), UncalledCaCertificateValidator())

    private fun profile(name: String, host: String = "rabbit-$name.example.com") = ConnectionProfile(
        name = name,
        host = host,
        virtualHost = "/",
        username = "svc-explorer",
    )

    private fun input(name: String, host: String, password: String? = null) = ConnectionProfileInput(
        name = name,
        host = host,
        virtualHost = "/",
        username = "svc-explorer",
        password = password,
    )

    @Test
    fun `editing fields without renaming updates the profile in place via update, not add-then-delete`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu", host = "old-host.example.com"))
        val secretStore = InMemorySecretStore()
        secretStore.store("prod-eu", "old-password")
        val viewModel = viewModel(repository, secretStore)

        val result = viewModel.updateConnection(
            originalName = "prod-eu",
            input = input(name = "prod-eu", host = "new-host.example.com"),
        )

        assertTrue(result is SaveConnectionResult.Saved, "expected Saved, got $result")
        // Exactly one profile, still named prod-eu, with the edited field - no add-then-delete churn.
        assertEquals(listOf("prod-eu"), repository.list().map { it.name })
        assertEquals("new-host.example.com", repository.list().single().host)
        // Password field was left blank on this edit - the previously stored secret must be untouched.
        assertEquals("old-password", secretStore.retrieve("prod-eu"))
        assertEquals(listOf("prod-eu"), viewModel.state.value.profiles.map { it.name })
    }

    @Test
    fun `editing fields without renaming and supplying a new password updates the same name's secret`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu"))
        val secretStore = InMemorySecretStore()
        secretStore.store("prod-eu", "old-password")
        val viewModel = viewModel(repository, secretStore)

        val result = viewModel.updateConnection(
            originalName = "prod-eu",
            input = input(name = "prod-eu", host = "rabbit-prod-eu.example.com", password = "new-password"),
        )

        assertTrue(result is SaveConnectionResult.Saved, "expected Saved, got $result")
        assertEquals("new-password", secretStore.retrieve("prod-eu"))
    }

    @Test
    fun `renaming a saved profile moves both the profile and its secret to the new name`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu"))
        val secretStore = InMemorySecretStore()
        secretStore.store("prod-eu", "s3cret")
        val viewModel = viewModel(repository, secretStore)

        val result = viewModel.updateConnection(
            originalName = "prod-eu",
            input = input(name = "prod-eu-renamed", host = "rabbit-prod-eu.example.com"),
        )

        assertTrue(result is SaveConnectionResult.Saved, "expected Saved, got $result")
        // Old name is gone, new name is present - a real move, not a copy.
        assertEquals(listOf("prod-eu-renamed"), repository.list().map { it.name })
        // The secret moved with it (no password supplied on the rename, so the old secret is carried over).
        assertEquals("s3cret", secretStore.retrieve("prod-eu-renamed"))
        assertNull(secretStore.retrieve("prod-eu"))
        assertEquals(listOf("prod-eu-renamed"), viewModel.state.value.profiles.map { it.name })
    }

    @Test
    fun `renaming with a new password uses the new password, not the carried-over secret`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu"))
        val secretStore = InMemorySecretStore()
        secretStore.store("prod-eu", "old-secret")
        val viewModel = viewModel(repository, secretStore)

        viewModel.updateConnection(
            originalName = "prod-eu",
            input = input(name = "prod-eu-renamed", host = "rabbit-prod-eu.example.com", password = "brand-new-secret"),
        )

        assertEquals("brand-new-secret", secretStore.retrieve("prod-eu-renamed"))
        assertNull(secretStore.retrieve("prod-eu"))
    }

    @Test
    fun `a rename that collides with an existing name leaves the original profile and secret untouched`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu", host = "rabbit-eu.example.com"))
        repository.add(profile("prod-us", host = "rabbit-us.example.com"))
        val secretStore = InMemorySecretStore()
        secretStore.store("prod-eu", "eu-secret")
        secretStore.store("prod-us", "us-secret")
        val viewModel = viewModel(repository, secretStore)

        val result = viewModel.updateConnection(
            originalName = "prod-eu",
            // Also changing the host, to prove a failed rename does not even half-apply the other field edits.
            input = input(name = "prod-us", host = "rabbit-eu-changed.example.com"),
        )

        assertTrue(result is SaveConnectionResult.NameAlreadyExists, "expected NameAlreadyExists, got $result")
        // Nothing deleted, nothing added: both original profiles are exactly as they were.
        assertEquals(setOf("prod-eu", "prod-us"), repository.list().map { it.name }.toSet())
        assertEquals("rabbit-eu.example.com", repository.list().first { it.name == "prod-eu" }.host)
        assertEquals("rabbit-us.example.com", repository.list().first { it.name == "prod-us" }.host)
        // No secret migration and no overwrite of the colliding profile's own secret.
        assertEquals("eu-secret", secretStore.retrieve("prod-eu"))
        assertEquals("us-secret", secretStore.retrieve("prod-us"))
        assertEquals(setOf("prod-eu", "prod-us"), viewModel.state.value.profiles.map { it.name }.toSet())
    }

    // --- topology control level on the input (M1-10): null on add = READ_ONLY, null on update = keep ---

    private fun inputWithLevel(name: String, host: String, level: TopologyControlLevel?) = ConnectionProfileInput(
        name = name,
        host = host,
        virtualHost = "/",
        username = "svc-explorer",
        password = "pw",
        topologyControlLevel = level,
    )

    @Test
    fun `adding with a level stores that level`() {
        val repository = InMemoryRepository()
        val viewModel = viewModel(repository, InMemorySecretStore())

        val result = viewModel.addConnection(inputWithLevel("uat-eu", "h.example.com", TopologyControlLevel.DECLARE_AND_EDIT))

        assertTrue(result is SaveConnectionResult.Saved, "expected Saved, got $result")
        assertEquals(TopologyControlLevel.DECLARE_AND_EDIT, repository.list().single().topologyControlLevel)
    }

    @Test
    fun `adding with a null level stores READ_ONLY`() {
        val repository = InMemoryRepository()
        val viewModel = viewModel(repository, InMemorySecretStore())

        viewModel.addConnection(inputWithLevel("uat-eu", "h.example.com", null))

        assertEquals(TopologyControlLevel.READ_ONLY, repository.list().single().topologyControlLevel)
    }

    @Test
    fun `updating with a null level keeps the saved level`() {
        val repository = InMemoryRepository()
        repository.add(profile("uat-eu").copy(topologyControlLevel = TopologyControlLevel.FULL_CONTROL))
        val viewModel = viewModel(repository, InMemorySecretStore())

        viewModel.updateConnection("uat-eu", inputWithLevel("uat-eu", "changed.example.com", null))

        val saved = repository.list().single()
        assertEquals("changed.example.com", saved.host)
        assertEquals(TopologyControlLevel.FULL_CONTROL, saved.topologyControlLevel)
    }

    @Test
    fun `renaming with a null level keeps the saved level`() {
        val repository = InMemoryRepository()
        repository.add(profile("uat-eu").copy(topologyControlLevel = TopologyControlLevel.FULL_CONTROL))
        val secretStore = InMemorySecretStore()
        secretStore.store("uat-eu", "pw")
        val viewModel = viewModel(repository, secretStore)

        viewModel.updateConnection("uat-eu", inputWithLevel("uat-eu-2", "rabbit-uat-eu.example.com", null))

        val saved = repository.list().single()
        assertEquals("uat-eu-2", saved.name)
        assertEquals(TopologyControlLevel.FULL_CONTROL, saved.topologyControlLevel)
    }

    @Test
    fun `updating with a value changes the level, also together with a rename`() {
        val repository = InMemoryRepository()
        repository.add(profile("uat-eu").copy(topologyControlLevel = TopologyControlLevel.FULL_CONTROL))
        repository.add(profile("uat-us"))
        val viewModel = viewModel(repository, InMemorySecretStore())

        viewModel.updateConnection("uat-eu", inputWithLevel("uat-eu", "rabbit-uat-eu.example.com", TopologyControlLevel.READ_ONLY))
        assertEquals(TopologyControlLevel.READ_ONLY, repository.list().first { it.name == "uat-eu" }.topologyControlLevel)

        viewModel.updateConnection("uat-us", inputWithLevel("uat-us-2", "rabbit-uat-us.example.com", TopologyControlLevel.DECLARE_AND_EDIT))
        val renamed = repository.list().single { it.name == "uat-us-2" }
        assertEquals(TopologyControlLevel.DECLARE_AND_EDIT, renamed.topologyControlLevel)
    }

    // --- Declared permissions (M1-06): ConnectionProfileInput.declaredPermissions is a DeclaredPermissionsEdit.
    // Red state: DeclaredPermissionsEdit and the new input field do not exist yet.

    private val yesYes = DeclaredPermissions(configure = true, write = true)
    private val noYes = DeclaredPermissions(configure = false, write = true)

    private fun inputDeclaring(name: String, edit: DeclaredPermissionsEdit? = null) =
        if (edit == null) input(name, "rabbit-$name.example.com")
        else input(name, "rabbit-$name.example.com").copy(declaredPermissions = edit)

    private fun savedWith(declared: DeclaredPermissions?): Pair<ConnectionsViewModel, InMemoryRepository> {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu").copy(declaredPermissions = declared))
        return viewModel(repository, InMemorySecretStore()) to repository
    }

    @Test
    fun `the default edit is Keep`() {
        assertEquals(DeclaredPermissionsEdit.Keep, input("a", "h").declaredPermissions)
    }

    @Test
    fun `add with Set stores the declaration`() {
        val repository = InMemoryRepository()
        val vm = viewModel(repository, InMemorySecretStore())
        val result = vm.addConnection(inputDeclaring("prod-eu", DeclaredPermissionsEdit.Set(noYes)))
        assertTrue(result is SaveConnectionResult.Saved, "got $result")
        assertEquals(noYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `add with Keep or Clear stores no declaration`() {
        listOf(DeclaredPermissionsEdit.Keep, DeclaredPermissionsEdit.Clear).forEach { edit ->
            val repository = InMemoryRepository()
            val vm = viewModel(repository, InMemorySecretStore())
            vm.addConnection(inputDeclaring("prod-eu", edit))
            assertNull(repository.list().single().declaredPermissions, "add with $edit must not declare anything")
        }
    }

    @Test
    fun `update with Keep keeps the saved declaration`() {
        val (vm, repository) = savedWith(yesYes)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu", DeclaredPermissionsEdit.Keep))
        assertEquals(yesYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `update with the default input keeps the saved declaration`() {
        val (vm, repository) = savedWith(yesYes)
        vm.updateConnection("prod-eu", input("prod-eu", "changed.example.com"))
        assertEquals(yesYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `update with Keep keeps the saved declaration on rename`() {
        val (vm, repository) = savedWith(noYes)
        val result = vm.updateConnection("prod-eu", inputDeclaring("prod-eu-2", DeclaredPermissionsEdit.Keep))
        assertTrue(result is SaveConnectionResult.Saved, "got $result")
        assertEquals(listOf("prod-eu-2"), repository.list().map { it.name })
        assertEquals(noYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `update with Set replaces the saved declaration`() {
        val (vm, repository) = savedWith(yesYes)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu", DeclaredPermissionsEdit.Set(noYes)))
        assertEquals(noYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `update with Set declares on a profile that had none`() {
        val (vm, repository) = savedWith(null)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu", DeclaredPermissionsEdit.Set(yesYes)))
        assertEquals(yesYes, repository.list().single().declaredPermissions)
    }

    @Test
    fun `update with Clear removes the saved declaration`() {
        val (vm, repository) = savedWith(yesYes)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu", DeclaredPermissionsEdit.Clear))
        assertNull(repository.list().single().declaredPermissions)
        assertNull(vm.state.value.profiles.single().declaredPermissions)
    }

    @Test
    fun `update with Clear on a profile without declaration stays undeclared`() {
        val (vm, repository) = savedWith(null)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu", DeclaredPermissionsEdit.Clear))
        assertNull(repository.list().single().declaredPermissions)
    }

    @Test
    fun `Set on rename moves the new declaration to the new name`() {
        val (vm, repository) = savedWith(yesYes)
        vm.updateConnection("prod-eu", inputDeclaring("prod-eu-2", DeclaredPermissionsEdit.Set(noYes)))
        assertEquals(noYes, repository.list().single { it.name == "prod-eu-2" }.declaredPermissions)
    }

    @Test
    fun `a failed update leaves the saved declaration untouched`() {
        val repository = InMemoryRepository()
        repository.add(profile("prod-eu").copy(declaredPermissions = yesYes))
        repository.add(profile("dev"))
        val vm = viewModel(repository, InMemorySecretStore())
        val result = vm.updateConnection("prod-eu", inputDeclaring("dev", DeclaredPermissionsEdit.Clear))
        assertTrue(result is SaveConnectionResult.NameAlreadyExists, "got $result")
        assertEquals(yesYes, repository.list().single { it.name == "prod-eu" }.declaredPermissions)
    }

    // M2-05 round 2, F1: the CA field validates on every keystroke. The view model passes the typed path
    // to the validator and publishes its typed answer for the field's error text; a validator is not
    // allowed to throw (the contract lives in data-rabbit's X509CaCertificateValidatorTest).
    @Test
    fun `an invalid CA path typed into the field is published as the error text and nothing is thrown`() {
        val seen = mutableListOf<String>()
        val validator = object : CaCertificateValidator {
            override fun validate(filePath: String): CaCertificateValidationResult {
                seen += filePath
                return CaCertificateValidationResult.Invalid("the file could not be read")
            }
        }
        val viewModel = ConnectionsViewModel(InMemoryRepository(), InMemorySecretStore(), UncalledReachabilityChecker(), validator)

        val returned = viewModel.validateCaCertificateFile("/")

        assertEquals(listOf("/"), seen)
        assertEquals(CaCertificateValidationResult.Invalid("the file could not be read"), returned)
        assertEquals(returned, viewModel.state.value.lastCaCertificateValidationResult)
    }

    // --- M2-14d (F2): a late CA validation answer must not land on the wrong profile -----------------------------------------
    // The validator is held at a gate (no sleep); the test changes the state while the file "is being read", then releases it.

    private val lateCa = "/some/ca.pem"

    private class LateCaFixture {
        val repository = InMemoryRepository()
        val secretStore = InMemorySecretStore()
        val gate = GatedCaCertificateValidator()
        val vm = ConnectionsViewModel(repository, secretStore, UncalledReachabilityChecker(), gate)
        fun add(name: String) = vm.addConnection(ConnectionProfileInput(name, "rabbit-$name.example.com", "/", "svc-explorer", password = null))
    }

    /** Starts the check for [name], runs [whileRunning] while it waits at the gate, releases it and returns what it answered. */
    private fun LateCaFixture.answerAfter(name: String?, whileRunning: () -> Unit): CaCertificateValidationResult? {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "late-ca-test") }
        try {
            return runBlocking {
                withTimeout(15_000) {
                    val answer = async(Dispatchers.Unconfined) { vm.validateCaCertificateOffThread(name, lateCa, pool.asCoroutineDispatcher()) }
                    gate.awaitStarted()
                    whileRunning()
                    gate.release()
                    answer.await()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a late CA answer for a deleted profile is dropped without an error and changes nothing`() {
        val f = LateCaFixture().also { it.add("a"); it.add("b"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") { f.vm.requestDelete("a"); f.vm.confirmDelete() }
        assertNull(answer, "a late answer for a deleted profile is dropped (null)")
        assertNull(f.vm.state.value.lastCaCertificateValidationResult, "no CA message for a deleted profile")
        assertEquals(listOf("b"), f.vm.state.value.profiles.map { it.name })
        assertEquals(listOf("b"), f.repository.list().map { it.name })
        assertTrue(f.repository.list().all { it.caCertificatePath == null }, "no profile got a CA path")
    }

    @Test
    fun `a late CA answer for a renamed profile is dropped and the profile under the new name has no CA path`() {
        val f = LateCaFixture().also { it.add("a"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") { f.vm.updateConnection("a", ConnectionProfileInput("a2", "rabbit-a.example.com", "/", "svc-explorer", password = null)) }
        assertNull(answer, "a late answer for a renamed profile is dropped (null)")
        assertNull(f.vm.state.value.lastCaCertificateValidationResult, "no CA message after a dropped answer")
        assertEquals(listOf("a2"), f.repository.list().map { it.name })
        assertNull(f.repository.list().single().caCertificatePath)
    }

    @Test
    fun `a rename bumps the epoch so the late answer is dropped even when the old name is added again`() {
        val f = LateCaFixture().also { it.add("a"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") {
            f.vm.updateConnection("a", ConnectionProfileInput("a2", "rabbit-a.example.com", "/", "svc-explorer", password = null))
            f.add("a") // a different profile now owns the old name: the existence check alone would let the answer through
        }
        assertNull(answer, "the answer belongs to the profile that was renamed, not to the new profile called 'a'")
        assertNull(f.repository.list().first { it.name == "a" }.caCertificatePath)
        assertNull(f.vm.state.value.lastCaCertificateValidationResult)
    }

    @Test
    fun `an update without a rename does not drop a running CA validation`() {
        val f = LateCaFixture().also { it.add("a"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") {
            f.vm.updateConnection("a", ConnectionProfileInput("a", "other-host.example.com", "/", "svc-explorer", password = null))
        }
        assertEquals(CaCertificateValidationResult.Valid, answer)
        val saved = f.repository.list().single()
        assertEquals(lateCa, saved.caCertificatePath, "the validated path is stored")
        assertEquals("other-host.example.com", saved.host, "the edit made meanwhile is kept")
    }

    @Test
    fun `a failed rename does not drop a running CA validation`() {
        val f = LateCaFixture().also { it.add("a"); it.add("b"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") {
            val result = f.vm.updateConnection("a", ConnectionProfileInput("b", "rabbit-a.example.com", "/", "svc-explorer", password = null))
            assertTrue(result is SaveConnectionResult.NameAlreadyExists, "setup: the rename collides, got $result")
        }
        assertEquals(CaCertificateValidationResult.Valid, answer, "nothing changed for 'a', so its answer still applies")
        assertEquals(lateCa, f.repository.list().first { it.name == "a" }.caCertificatePath)
    }

    @Test
    fun `deleting the selected profile bumps the epoch so a running validation without a name is dropped`() {
        val f = LateCaFixture().also { it.add("a"); it.vm.selectConnection("a") }
        val answer = f.answerAfter(null) { f.vm.requestDelete("a"); f.vm.confirmDelete() }
        assertNull(answer, "the panel the answer was for is gone")
        assertNull(f.vm.state.value.lastCaCertificateValidationResult)
    }

    @Test
    fun `deleting another profile does not drop a running validation`() {
        val f = LateCaFixture().also { it.add("a"); it.add("b"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") { f.vm.requestDelete("b"); f.vm.confirmDelete() }
        assertEquals(CaCertificateValidationResult.Valid, answer)
        assertEquals(lateCa, f.repository.list().single { it.name == "a" }.caCertificatePath)
    }

    @Test
    fun `a late CA answer for a profile deleted while another one is selected is dropped by the existence check`() {
        // Deleting a profile that is not selected does not bump the epoch, so only the "still exists" check can drop this answer.
        val f = LateCaFixture().also { it.add("a"); it.add("b"); it.vm.selectConnection("b") }
        val answer = f.answerAfter("a") { f.vm.requestDelete("a"); f.vm.confirmDelete() }
        assertNull(answer)
        assertNull(f.vm.state.value.lastCaCertificateValidationResult)
        assertTrue(f.repository.list().all { it.caCertificatePath == null })
    }

    @Test
    fun `renaming a profile and back while a CA validation runs drops the answer`() {
        val f = LateCaFixture().also { it.add("a"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") {
            f.vm.updateConnection("a", ConnectionProfileInput("a2", "rabbit-a.example.com", "/", "svc-explorer", password = null))
            f.vm.updateConnection("a2", ConnectionProfileInput("a", "rabbit-a.example.com", "/", "svc-explorer", password = null))
        }
        assertNull(answer, "the name is back, but the profile was renamed meanwhile: the epoch drops the answer")
        assertNull(f.repository.list().single().caCertificatePath)
    }

    @Test
    fun `a panel switch still drops the late CA answer`() {
        val f = LateCaFixture().also { it.add("a"); it.add("b"); it.vm.selectConnection("a") }
        val answer = f.answerAfter("a") { f.vm.selectConnection("b") }
        assertNull(answer)
        assertNull(f.vm.state.value.lastCaCertificateValidationResult)
        assertTrue(f.repository.list().all { it.caCertificatePath == null })
    }

    @Test
    fun `a late CA answer for the new connection form with no name still applies`() {
        val f = LateCaFixture().also { it.vm.startNewConnection() }
        val answer = f.answerAfter(null) {}
        assertEquals(CaCertificateValidationResult.Valid, answer)
        assertEquals(CaCertificateValidationResult.Valid, f.vm.state.value.lastCaCertificateValidationResult)
    }
}
