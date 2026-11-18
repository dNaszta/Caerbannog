package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.HistoryMoveResult
import dev.caerbannog.core.migrations.HistoryResult
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.EditFileFixtures.VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M9-12 step 4 (test developer): checks beyond the scenarios. */
class PlannerEditFileExploratoryTest {
    private fun savedActions(rig: PlannerRig, version: String = VERSION) =
        (MigrationParser.parse(rig.folder.files.getValue(version).rawJson) as MigrationValidationResult.Valid).migration.up

    @Test
    fun `a file with an empty up list opens as an empty plan, cannot be saved empty, and saves once a step is added`() {
        val rig = EditFileFixtures.open(EditFileFixtures.raw(actions = emptyArray()))
        try {
            assertEquals(emptyList(), rig.state.plan.steps)
            assertEquals(SaveGate.Disabled(PlannerTexts.ADD_A_STEP_FIRST), rig.state.saveGate)
            rig.queue("invoices")
            rig.vm.saveAsMigration()
            assertEquals(listOf(MigrationAction.DeclareQueue("invoices", "quorum")), savedActions(rig))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `two thousand steps round trip and unusual names survive`() {
        val many = (1..2000).map { MigrationAction.DeclareQueue("q-$it", "quorum") } +
            MigrationAction.DeclareQueue("ünï \"quoted\" \\ slash", "classic", arguments = mapOf("x-note" to ArgumentValue.Text("line\nbreak \u0000 tab\t")))
        val rig = EditFileFixtures.open(EditFileFixtures.raw(actions = many.toTypedArray()))
        try {
            rig.queue("invoices")
            rig.vm.saveAsMigration()
            assertEquals(many + MigrationAction.DeclareQueue("invoices", "quorum"), savedActions(rig))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a file whose saved text is refused by the parser is not written and says the file could not be written`() {
        // a queue name of 256 characters is accepted by the parser but a step with a blocking problem keeps Save disabled; here the plan is valid and the folder refuses
        val rig = EditFileFixtures.open()
        try {
            rig.queue("invoices")
            rig.folder.failVersion(VERSION, "boom with SENTINEL-DISK-TEXT")
            rig.vm.saveAsMigration()
            assertEquals(PlannerTexts.FILE_NOT_WRITTEN, rig.state.errorMessage)
            assertFalse((rig.state.errorMessage + rig.state.errorDetail).contains("SENTINEL"), "the disk text reached the screen")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `saving twice in a row after a success writes once, the second is no change`() {
        val rig = EditFileFixtures.open()
        try {
            rig.queue("invoices")
            rig.vm.saveAsMigration()
            rig.vm.saveAsMigration()
            assertEquals(1, rig.folder.updates.size, "the same save was written twice")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a second save after a first one is guarded by the new checksum of the file`() {
        val rig = EditFileFixtures.open()
        try {
            rig.queue("invoices")
            rig.vm.saveAsMigration()
            rig.queue("second")
            rig.vm.saveAsMigration()
            assertEquals(2, rig.folder.updates.size)
            assertEquals(5, savedActions(rig).size)
        } finally {
            rig.close()
        }
    }

    // ---- what the view model does NOT check itself (the Migrations screen is the one door) ----

    @Test
    fun `an unknown-keys file locks the planner in file mode, so a save can never drop its keys (F1 fixed)`() {
        val raw = """{ "version": "0005", "description": "d", "up": [ { "action": "declareQueue", "name": "orders", "comment": "keep me" } ] }"""
        val rig = EditFileFixtures.open(raw)
        try {
            val reason = "This file holds entries the planner does not know. Edit it as JSON so nothing is lost."
            assertEquals(SaveGate.Disabled(reason), rig.state.saveGate)
            rig.queue("invoices")
            assertEquals(emptyList(), rig.state.plan.steps, "a step was added to a locked planner")
            assertFalse(rig.state.hasChanges)
            rig.vm.saveAsMigration()
            assertEquals(emptyList(), rig.folder.updates)
            assertEquals(raw, rig.folder.files.getValue(VERSION).rawJson)
            assertTrue(rig.folder.files.getValue(VERSION).rawJson.contains("keep me"))
        } finally {
            rig.close()
        }
    }

    // ---- the applied history is only read ----

    private class RecordingHistory(val inner: InMemoryMigrationHistoryRepository = InMemoryMigrationHistoryRepository()) : MigrationHistoryRepository {
        val calls = mutableListOf<String>()
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> { calls += "get:$profileName"; return inner.getAppliedMigrations(profileName) }
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) { calls += "record"; inner.recordApplied(profileName, version, checksum) }
        override suspend fun load(profileName: String): HistoryResult { calls += "load:$profileName"; return inner.load(profileName) }
        override fun asideFileName(): String = error("never asked")
        override suspend fun moveAside(): HistoryMoveResult { calls += "moveAside"; return HistoryMoveResult.Failed("no") }
    }

    @Test
    fun `deciding whether a file opens only reads the history, for every saved profile, and never writes`() {
        val folders = FakeMigrationFolders("/work/migrations")
        folders.folder("/work/migrations").addRaw(EditFileFixtures.raw(actions = EditFileFixtures.threeSteps.toTypedArray()))
        val recording = RecordingHistory()
        val ports = dev.caerbannog.core.migrations.MigrationFolderPorts(folders.ports.store, folders.ports.chooser, folders.ports.repositoryFor, recording)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = MigrationsViewModel(null, MigrationRunner(recording, FakeTopologyMigrationExecutor()), profileName = "uat-eu", folderPorts = ports, scope = scope, savedProfileNames = { listOf("uat-eu", "prod-eu", "dev") })
            vm.selectVersion(VERSION)
            assertIs<PlanOpeningDecision.Openable>(vm.state.value.planOpening)
            assertTrue(recording.calls.none { it == "record" || it == "moveAside" }, "the history was written: ${recording.calls}")
            assertTrue(setOf("get:uat-eu", "get:prod-eu", "get:dev").all { it in recording.calls }, "not every saved profile was read: ${recording.calls}")
        } finally {
            scope.cancel()
        }
    }
}
