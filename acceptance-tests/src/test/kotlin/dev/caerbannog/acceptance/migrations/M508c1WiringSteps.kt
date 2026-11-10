package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.ApplyOutcome
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationValidationResult
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException

/**
 * M5-08c1 step definitions: the `@m5-08c1` scenarios of tests/bdd/features/10_topology_migrations.feature. The REAL view model, runner and (where the
 * scenario is about the broker) the REAL executor over a scripted broker (steps of M508aExecutorSteps); the history and the executor are fakes
 * where the scenario needs a fault. "The screen says" reads what the view model exposes for the existing banners: `errorMessage` (the composed
 * outcome text) and `bannerMessage` (the one line about why Apply is unavailable). M5-08c2 re-binds the same steps to the Compose screen.
 * The shared scenario state is that of [TopologyMigrationsSteps], reached through `TopologyMigrationsSteps.current`.
 */
class M508c1WiringSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }

    /** A history whose writes fail like a full or read-only disk; reading works. */
    private class UnwritableHistory(private val inner: MigrationHistoryRepository) : MigrationHistoryRepository {
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = inner.getAppliedMigrations(profileName)
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) {
            throw IOException("SENTINEL-history-disk-full")
        }
    }

    private fun sample(version: String) =
        """{ "version": "$version", "description": "m5-08c1", "up": [ { "action": "declareQueue", "name": "queue-$version", "type": "quorum" } ] }"""

    private fun addPending(version: String) = runBlocking {
        val result = s.fileRepository.importFile(sample(version))
        assertTrue(result is MigrationValidationResult.Valid, "the scenario's own file was refused: $result")
    }

    /** Everything the existing banners can say right now. */
    private fun screenText(): String {
        val state = s.getVm().state.value
        return listOfNotNull(state.errorMessage, state.bannerMessage).joinToString("\n")
    }

    // ---- The topology control level of the connection is honoured ------------------------------------------------------------------

    @Given("the connection {string} has the topology control level {string} although the broker would allow changes")
    fun connectionHasLevel(profile: String, level: String) {
        s.profileName = profile
        s.controlLevel = when (level) {
            "Read-only" -> TopologyControlLevel.READ_ONLY
            "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            else -> error("unknown level $level")
        }
        // The user may configure and the fake broker accepts everything: only the level can be the reason. One pending migration makes
        // "disabled" mean something (with nothing pending Apply is disabled anyway).
        s.configurePermission = true
        addPending("0001")
        s.viewModel = null // the Background built one with the default level; the screen is opened again for this connection
    }

    @Then("{string} is disabled and the reason says the level is Read-only")
    fun disabledBecauseOfLevel(button: String) {
        assertEquals("Apply pending", button)
        val state = s.getVm().state.value
        assertEquals(1, state.entries.count { it.status is MigrationStatus.Pending }, "the premise: one migration is pending")
        assertFalse(state.isApplyPendingEnabled, "\"Apply pending\" is enabled on a Read-only connection")
        assertFalse(state.isApplySingleEnabled, "applying one migration is enabled on a Read-only connection")
        assertEquals("Apply is disabled: this connection is set to Read-only", state.bannerMessage)
    }

    // ---- A failed apply and a conflict, on the screen --------------------------------------------------------------------------------

    @When("I apply {string} on the screen")
    fun applyOnScreen(version: String) = runBlocking {
        s.lastApplyResult = s.getVm().applySingle(version)
    }

    @When("I apply the pending migrations on the screen")
    fun applyPendingOnScreen() = runBlocking {
        s.lastApplyResult = s.getVm().applyPending()
    }

    @Then("the screen says {string}")
    fun screenSays(text: String) {
        val shown = screenText()
        assertTrue(shown.contains(text), "the screen should say \"$text\" but says: \"$shown\"")
    }

    @Then("it says migration {string} was not recorded and that running it again is safe")
    fun saysNotRecordedAndSafe(version: String) = runBlocking {
        val shown = screenText()
        assertTrue(shown.contains("Migration $version was not recorded"), "the screen should say migration $version was not recorded: \"$shown\"")
        assertTrue(shown.contains("Running it again is safe"), "the screen should say a rerun is safe: \"$shown\"")
        assertFalse(s.historyRepository.getAppliedMigrations(s.profileName).containsKey(version), "$version was recorded as applied")
    }

    @Then("it says the existing {string} was not changed and {string} was not recorded")
    fun saysExistingNotChanged(resource: String, version: String) = runBlocking {
        val shown = screenText()
        assertTrue(shown.contains("The existing resource was not changed"), "the screen should say the existing $resource was not changed: \"$shown\"")
        assertTrue(shown.contains("exists with different settings") && shown.contains(resource), "the reason should name $resource: \"$shown\"")
        // The conflict scenarios share the 08a step that imports its migration as "0001"; the quoted version of the scenario text is not in the
        // file. The strong check: NOTHING was recorded, and the text names the migration that really ran.
        val history = s.historyRepository.getAppliedMigrations(s.profileName)
        assertTrue(history.isEmpty(), "a migration was recorded as applied although its action stopped: $history")
        assertFalse(history.containsKey(version))
        val failed = s.lastApplyResult as? MigrationApplyResult.ActionFailed ?: error("expected the apply to stop at an action but it gave ${s.lastApplyResult}")
        assertTrue(shown.contains("Migration ${failed.version} was not recorded"), "the screen should say migration ${failed.version} was not recorded: \"$shown\"")
    }

    // ---- An apply that could not be recorded ------------------------------------------------------------------------------------------

    @Given("the history cannot be written after the broker accepted every action of {string}")
    fun historyCannotBeWritten(version: String) {
        addPending(version)
        s.runner = MigrationRunner(UnwritableHistory(s.historyRepository), s.executor)
        s.viewModel = null
    }

    @Then("{string} is still listed as pending")
    fun stillListedAsPending(version: String) {
        val entry = s.getVm().state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry, "$version is not listed")
        assertTrue(entry!!.status is MigrationStatus.Pending, "$version is listed as ${entry.status}")
        assertFalse(s.getVm().state.value.errorMessage.orEmpty().contains("SENTINEL"), "the disk error text reached the screen")
        // The structured outcome is the contract M5-08c2 draws from: an apply that could not be recorded names the migration and the fixed text.
        assertEquals(
            ApplyOutcome.NotRecorded(version, "The migration was applied but could not be recorded; running it again is safe"),
            s.getVm().state.value.applyOutcome,
            "the structured outcome of an apply that could not be recorded",
        )
    }

    // ---- A connection that cannot apply migrations ------------------------------------------------------------------------------------

    @Given("a connection whose migration executor is missing because {string}")
    fun executorMissingBecause(reason: String) {
        s.migrationUnavailableReason = reason
        s.viewModel = null
        addPending("0001")
    }

    @Then("the screen says migrations are not offered on this connection and shows that reason")
    fun saysNotOffered() {
        val state = s.getVm().state.value
        val reason = checkNotNull(s.migrationUnavailableReason)
        assertEquals("Migrations are not offered on this connection: $reason", state.bannerMessage)
        assertEquals(reason, state.unavailableReason)
    }

    @Then("{string} is disabled while files, dry run, Import and Export keep working")
    fun disabledWhileRestWorks(button: String) = runBlocking {
        assertEquals("Apply pending", button)
        val vm = s.getVm()
        val state = vm.state.value
        assertFalse(state.isApplyPendingEnabled, "\"Apply pending\" is enabled on a connection without an executor")
        assertFalse(state.isApplySingleEnabled, "applying one migration is enabled on a connection without an executor")
        assertEquals(listOf("0001"), state.entries.map { it.file.version }, "the files are listed")
        assertNotNull(state.dryRun, "the dry run is not shown")
        assertTrue(state.isImportEnabled && state.isExportEnabled, "Import or Export is disabled")
        assertTrue(vm.importFile(sample("0099")) is MigrationValidationResult.Valid, "Import does not work")
        assertNotNull(vm.exportCurrentTopology(), "Export does not work")
        assertTrue(s.executor.executedActions.isEmpty(), "something was sent to the broker")
    }
}
