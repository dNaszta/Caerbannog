package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.PlanOpeningDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * M9-12 step 2 (RED by compilation until `MigrationsScreenState.planOpening` and the `savedProfileNames` parameter exist): the Migrations view model makes the
 * decision `PlanOpening.decide(file, rawKeysKnown = !MigrationParser.hasUnknownKeys(file.rawJson), appliedOn = ...)` for the SELECTED file, from the history of
 * EVERY saved profile (the current one always counts), and puts it in `state.planOpening`:
 *  - `MigrationsViewModel(..., savedProfileNames: () -> List<String> = { emptyList() })`: every saved profile of this machine (the desktop app passes the profile list);
 *  - `MigrationsScreenState.planOpening: PlanOpeningDecision = PlanOpeningDecision.Openable`.
 */
class MigrationsViewModelPlanOpeningTest {
    private val folders = FakeMigrationFolders("/work/migrations")
    private val folder = folders.folder("/work/migrations")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var saved: List<String> = listOf("uat-eu", "prod-eu")

    @AfterTest
    fun close() = scope.cancel()

    private fun vm() = MigrationsViewModel(
        fileRepository = null, runner = MigrationRunner(folders.history, FakeTopologyMigrationExecutor()), profileName = "uat-eu",
        folderPorts = folders.ports, scope = scope, savedProfileNames = { saved },
    )

    private fun put(version: String, vararg actions: MigrationAction) = folder.addRaw(MigrationParser.exportToJson(version, "d", actions.toList()))

    private fun decisionFor(version: String): PlanOpeningDecision {
        val vm = vm()
        vm.selectVersion(version)
        return vm.state.value.planOpening
    }

    @Test
    fun `a pending file that is applied nowhere is openable`() {
        put("0005", MigrationAction.DeclareQueue("orders"))
        assertEquals(PlanOpeningDecision.Openable, decisionFor("0005"))
    }

    @Test
    fun `a file applied on another saved profile says which one`() {
        put("0002", MigrationAction.DeclareQueue("billing"))
        runBlocking { folders.history.recordApplied("prod-eu", "0002", "c") }
        val d = assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0002"))
        assertEquals("0002 is applied on prod-eu, so it is not edited", d.reason)
        assertEquals(true, d.canCopy)
    }

    @Test
    fun `a file applied on the current profile is refused too`() {
        put("0002", MigrationAction.DeclareQueue("billing"))
        runBlocking { folders.history.recordApplied("uat-eu", "0002", "c") }
        assertEquals("0002 is applied on uat-eu, so it is not edited", assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0002")).reason)
    }

    @Test
    fun `a file applied on a profile that is no longer saved does not count`() {
        put("0002", MigrationAction.DeclareQueue("billing"))
        runBlocking { folders.history.recordApplied("gone", "0002", "c") }
        assertEquals(PlanOpeningDecision.Openable, decisionFor("0002"))
    }

    @Test
    fun `a modified file, whose checksum differs from the recorded one, is applied there as well`() {
        put("0002", MigrationAction.DeclareQueue("billing"))
        runBlocking { folders.history.recordApplied("prod-eu", "0002", "an-older-checksum") }
        assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0002"))
    }

    @Test
    fun `a file with a hash, an access action or unknown keys is refused with its own text`() {
        put("0001", MigrationAction.DeclareUser("svc", SecretValue.of("H"), "rabbit_password_hashing_sha256"))
        put("0003", MigrationAction.DeclareVhost("orders"))
        folder.addRaw("""{ "version": "0007", "description": "d", "up": [ { "action": "declareQueue", "name": "q", "comment": "x" } ] }""")
        assertEquals("This file holds password hashes. Edit it outside the app.", assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0001")).reason)
        assertEquals(
            "This file holds virtual hosts, users or permissions. The planner works on topology only.",
            assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0003")).reason,
        )
        assertEquals(
            "This file holds entries the planner does not know. Edit it as JSON so nothing is lost.",
            assertIs<PlanOpeningDecision.NotOffered>(decisionFor("0007")).reason,
        )
    }

    @Test
    fun `selecting another file changes the decision`() {
        put("0002", MigrationAction.DeclareQueue("billing"))
        put("0005", MigrationAction.DeclareQueue("orders"))
        runBlocking { folders.history.recordApplied("prod-eu", "0002", "c") }
        val vm = vm()
        vm.selectVersion("0002")
        assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening)
        vm.selectVersion("0005")
        assertEquals(PlanOpeningDecision.Openable, vm.state.value.planOpening)
    }

    @Test
    fun `a history that is recorded after the screen opened is seen when the list is read again`() {
        put("0005", MigrationAction.DeclareQueue("orders"))
        val vm = vm()
        vm.selectVersion("0005")
        assertEquals(PlanOpeningDecision.Openable, vm.state.value.planOpening)
        runBlocking { folders.history.recordApplied("prod-eu", "0005", "c") }
        vm.loadMigrations()
        assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening)
    }

    @Test
    fun `the view model never writes a file or the history to decide`() {
        put("0005", MigrationAction.DeclareQueue("orders"))
        decisionFor("0005")
        assertEquals(emptyList(), folder.updates)
        assertEquals(emptyList(), folder.attempts)
    }
}
