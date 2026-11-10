package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.HistoryKeyResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.ProfileHistoryKeys
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M5-08c2 step definitions for the `@m5-08c2` scenarios that do not need a folder on disk: "Applying shows its progress" (the REAL Migrations
 * screen over the real view model and a gated executor) and the two Connections scenarios (the REAL Connections screen over the real
 * `ConnectionsViewModel`, with a history that cannot be updated). The folder and history-file scenarios are in M508bPersistenceSteps.
 * "The screen says ..." is the step of M508c1WiringSteps: it reads the banners of the Compose screen.
 */
class M508c2ScreenSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }

    /** Answers every action at once, except the action named [gateOn]: it waits until [release] (the second action "is running"). */
    private class GatedExecutor(private val gateOn: String) : TopologyMigrationExecutor {
        val release = CompletableDeferred<Unit>()
        override suspend fun execute(action: MigrationAction): ActionResult {
            if ((action as MigrationAction.DeclareQueue).name == gateOn) release.await()
            return ActionResult.Success
        }
    }

    private var gate: GatedExecutor? = null

    @After
    fun endScenario() {
        gate?.release?.complete(Unit)
        gate = null
        connections?.dispose()
        connections = null
    }

    // ---- Applying shows its progress ---------------------------------------------------------------------------------------------------

    @Given("a pending migration {string} with three actions")
    fun pendingWithThreeActions(version: String) = blocking {
        val actions = (1..3).joinToString(", ") { """{ "action": "declareQueue", "name": "q-$version-$it", "type": "quorum" }""" }
        val result = s.fileRepository.importFile("""{ "version": "$version", "description": "m5-08c2", "up": [ $actions ] }""")
        assertTrue(result is MigrationValidationResult.Valid, "the scenario's own file was refused: $result")
        val held = GatedExecutor(gateOn = "q-$version-2").also { gate = it }
        s.runner = MigrationRunner(s.historyRepository, held)
        s.viewModel = null
    }

    @When("I apply {string} on the screen and the second action is running")
    fun applyAndSecondActionRuns(version: String) {
        assertEquals(version, s.getVm().state.value.selectedVersion, "the premise: $version is the migration shown")
        MigrationsScreens.forViewModel(s.getVm()).click("migrations:applySingleBtn")
        assertTrue(s.getVm().state.value.isApplying, "the apply is not running")
    }

    @Then("Apply pending, Import and Export are disabled")
    fun applyImportExportDisabled() {
        val screen = MigrationsScreens.forViewModel(s.getVm())
        assertFalse(screen.isEnabled("migrations:applyPendingBtn"), "Apply pending is enabled while an apply runs")
        assertFalse(screen.isEnabled("migrations:importBtn"), "Import is enabled while an apply runs")
        assertFalse(screen.isEnabled("migrations:exportBtn"), "Export is enabled while an apply runs")
        assertTrue(screen.isDrawn("migrations:applyProgress"), "no progress bar is drawn while an apply runs")
    }

    // ---- The Connections screen when the history cannot be updated ---------------------------------------------------------------------------

    private class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult { profiles[profile.name] = profile; return SaveConnectionResult.Saved(profile) }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class NoSecrets : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = null
        override fun delete(profileName: String) = Unit
    }

    private class Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class ValidCa : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private class UnwritableKeys : ProfileHistoryKeys {
        override fun rename(oldName: String, newName: String) = HistoryKeyResult.Failed("SENTINEL-disk")
        override fun delete(profileName: String) = HistoryKeyResult.Failed("SENTINEL-disk")
    }

    private val repo = Repo()
    private var connections: ConnectionsScreenDriver? = null

    @Given("the history of the connection {string} cannot be updated")
    fun historyCannotBeUpdated(name: String) {
        repo.add(ConnectionProfile(name = name, host = "$name.example.com", virtualHost = "/", username = "svc"))
        val viewModel = ConnectionsViewModel(repo, NoSecrets(), Reachable(), ValidCa(), historyKeys = UnwritableKeys())
        connections = ConnectionsScreenDriver(viewModel).also { it.selectFirstProfile() }
    }

    @When("I confirm deleting the connection {string}")
    fun confirmDeleting(name: String) {
        checkNotNull(connections) { "no Connections screen" }.deleteSelectedAndConfirm()
    }

    @When("I save the connection {string} under the name {string}")
    fun saveUnderName(oldName: String, newName: String) {
        checkNotNull(connections) { "no Connections screen" }.renameSelectedTo(oldName, newName)
    }

    @Then("the Connections screen shows {string}")
    fun connectionsShows(text: String) {
        val screen = checkNotNull(connections) { "no Connections screen" }
        assertEquals(text, screen.textOf("connections:historyNotUpdatedBanner"), "the Connections screen should show exactly this message")
        assertFalse(screen.textOf("connections:historyNotUpdatedBanner").orEmpty().contains("SENTINEL"), "the disk error text reached the screen")
    }

    @Then("{string} is still listed")
    fun stillListed(name: String) {
        assertTrue(repo.profiles.containsKey(name), "\"$name\" was removed although its history could not be updated; the list is ${repo.profiles.keys}")
    }

    @Then("the connection is still named {string}")
    fun stillNamed(name: String) {
        assertEquals(listOf(name), repo.profiles.keys.toList(), "the profile list after the refused rename")
    }
}
