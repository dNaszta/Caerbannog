package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolderChooser
import dev.caerbannog.core.migrations.fakes.InMemoryMigrationFolderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-08c1 scopes 3 to 6: what the view model says about how an apply ended (structured `applyOutcome`, progress while it runs, the composed
 * text of the existing red banner), why Apply is unavailable, when the folder can be shown, and which single banner line wins.
 * Fakes behind the ports only; Dispatchers.Unconfined, so every action has finished when it returns.
 */
class MigrationsViewModelOutcomeTest {

    private val refused = "the broker refused the request"
    private val notRecorded = "The migration was applied but could not be recorded; running it again is safe"

    private fun migration(version: String, vararg queues: String): MigrationFile {
        val actions = queues.joinToString(", ") { """{ "action": "declareQueue", "name": "$it" }""" }
        return (MigrationParser.parse("""{ "version": "$version", "description": "d", "up": [ $actions ] }""") as MigrationValidationResult.Valid).migration
    }

    /** Answers by queue name: a name in [failures] fails with the given result; [onSend] runs before each answer (it can read the view model). */
    private class ScriptedExecutor(val failures: MutableMap<String, ActionResult.Failed> = mutableMapOf()) : TopologyMigrationExecutor {
        val sent = mutableListOf<String>()
        var onSend: (String) -> Unit = {}
        override suspend fun execute(action: MigrationAction): ActionResult {
            val name = (action as MigrationAction.DeclareQueue).name
            sent += name
            onSend(name)
            return failures[name] ?: ActionResult.Success
        }
    }

    private class FailingHistory : MigrationHistoryRepository {
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = emptyMap()
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) = throw IOException("SENTINEL-disk")
    }

    private val executor = ScriptedExecutor()
    private val files = InMemoryMigrationFileRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun add(vararg migrations: MigrationFile) = runBlocking { migrations.forEach { files.importFile(it.rawJson) } }

    private fun vm(
        history: MigrationHistoryRepository = InMemoryMigrationHistoryRepository(),
        unavailable: String? = null,
        permission: Boolean = true,
        level: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    ) = MigrationsViewModel(
        fileRepository = files,
        runner = MigrationRunner(history, executor),
        hasConfigurePermission = permission,
        controlLevel = level,
        migrationUnavailableReason = unavailable,
        scope = scope,
    )

    // ---- the outcome of an apply ---------------------------------------------------------------------------------------------------

    @Test
    fun `a failed action gives a Failed outcome with its position and no composed text`() = runBlocking<Unit> {
        add(migration("0003", "a", "b", "c"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()

        val result = vm.applyPending()

        assertIs<MigrationApplyResult.ActionFailed>(result)
        val s = vm.state.value
        assertEquals(ApplyOutcome.Failed("0003", 2, 3, refused), s.applyOutcome)
        assertNull(s.errorMessage, "M5-08c2: the outcome is the structured field, the screen words it; it is not composed into errorMessage")
        assertEquals(listOf("a", "b"), executor.sent, "the third action is never sent")
        assertFalse(s.isApplying)
        assertEquals(listOf("0003"), s.entries.filter { it.status is MigrationStatus.Pending }.map { it.file.version }, "0003 stays pending")
    }

    @Test
    fun `a failure of apply single gives the same outcome and no composed text`() = runBlocking<Unit> {
        add(migration("0003", "a", "b", "c"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()

        assertIs<MigrationApplyResult.ActionFailed>(vm.applySingle("0003"))

        val s = vm.state.value
        assertEquals(ApplyOutcome.Failed("0003", 2, 3, refused), s.applyOutcome)
        assertNull(s.errorMessage, "the outcome is not composed into errorMessage")
    }

    @Test
    fun `a conflict by kind gives a Conflict outcome naming the resource`() = runBlocking<Unit> {
        add(migration("0003", "orders", "jobs"))
        executor.failures["orders"] = ActionResult.Failed("orders exists with different settings", FailureKind.Conflict)
        val vm = vm()

        assertIs<MigrationApplyResult.ActionFailed>(vm.applyPending())

        val s = vm.state.value
        assertEquals(ApplyOutcome.Conflict("0003", 1, 2, "orders exists with different settings", resource = "orders"), s.applyOutcome, "the conflict carries the plain name of the resource for the screen")
        assertNull(s.errorMessage, "the outcome is not composed into errorMessage")
    }

    @Test
    fun `the kind is carried from the executor to the apply result`() = runBlocking<Unit> {
        add(migration("0003", "orders"))
        executor.failures["orders"] = ActionResult.Failed("orders exists with different settings", FailureKind.Conflict)

        val result = vm().applyPending()

        assertEquals(FailureKind.Conflict, assertIs<MigrationApplyResult.ActionFailed>(result).kind)
    }

    @Test
    fun `a reason that only contains the conflict words but has kind Other is a failure, not a conflict`() = runBlocking<Unit> {
        add(migration("0003", "orders"))
        executor.failures["orders"] = ActionResult.Failed("orders was found with different settings after it was declared, exists with different settings", FailureKind.Other)
        val vm = vm()

        val result = vm.applyPending()

        assertEquals(FailureKind.Other, assertIs<MigrationApplyResult.ActionFailed>(result).kind)
        assertIs<ApplyOutcome.Failed>(vm.state.value.applyOutcome, "nothing may decide 'conflict' by reading the text")
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `a conflict is a conflict whatever its text says`() = runBlocking<Unit> {
        add(migration("0003", "orders"))
        executor.failures["orders"] = ActionResult.Failed("limits differ", FailureKind.Conflict)
        val vm = vm()

        vm.applyPending()

        assertEquals(ApplyOutcome.Conflict("0003", 1, 1, "limits differ", resource = "orders"), vm.state.value.applyOutcome)
    }

    @Test
    fun `a conflict names the resource only when the echo rule allows it`() = runBlocking<Unit> {
        val hostile = listOf("my queue", "a<b>b", "x".repeat(65), "amqp://user:secret@host/vhost", "caf\u00e9", "..")
        for (name in hostile + listOf("orders", "x".repeat(64), "my_queue.v2-1")) {
            val files = InMemoryMigrationFileRepository()
            val local = ScriptedExecutor()
            local.failures[name] = ActionResult.Failed("The resource exists with different settings", FailureKind.Conflict)
            val vm = MigrationsViewModel(
                fileRepository = files.also { runBlocking { it.importFile(migration("0003", name).rawJson) } },
                runner = MigrationRunner(InMemoryMigrationHistoryRepository(), local), scope = scope,
            )

            vm.applyPending()

            val outcome = assertIs<ApplyOutcome.Conflict>(vm.state.value.applyOutcome, "for the name \"$name\"")
            val expected = if (name in hostile) null else name
            assertEquals(expected, outcome.resource, "the screen may echo a name only when echoableName() says so: \"$name\"")
        }
    }

    @Test
    fun `a history that cannot be written gives a NotRecorded outcome and the fixed text, and the migration stays pending`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        val vm = vm(history = FailingHistory())

        assertIs<MigrationApplyResult.NotRecorded>(vm.applyPending())

        val s = vm.state.value
        assertEquals(ApplyOutcome.NotRecorded("0003", notRecorded), s.applyOutcome)
        assertNull(s.errorMessage, "the outcome is not composed into errorMessage")
        assertEquals(listOf("0003"), s.entries.filter { it.status is MigrationStatus.Pending }.map { it.file.version })
        assertFalse(s.toString().contains("SENTINEL"), "the failure text never reaches the state")
    }

    @Test
    fun `a not recorded apply single gives the same outcome`() = runBlocking<Unit> {
        add(migration("0003", "a"))
        val vm = vm(history = FailingHistory())

        assertIs<MigrationApplyResult.NotRecorded>(vm.applySingle("0003"))

        assertEquals(ApplyOutcome.NotRecorded("0003", notRecorded), vm.state.value.applyOutcome)
        assertNull(vm.state.value.errorMessage, "the outcome is not composed into errorMessage")
    }

    @Test
    fun `a successful apply leaves no outcome and no error`() = runBlocking<Unit> {
        add(migration("0003", "a"))
        val vm = vm()

        assertIs<MigrationApplyResult.Success>(vm.applyPending())

        assertNull(vm.state.value.applyOutcome)
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.applyProgress)
    }

    @Test
    fun `the outcome survives the reload that follows the apply`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        vm.applyPending()

        vm.loadMigrations()

        assertNotNull(vm.state.value.applyOutcome, "only the next apply or dismissOutcome clears it")
    }

    @Test
    fun `the next apply clears the previous outcome and text`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        vm.applyPending()
        assertNotNull(vm.state.value.applyOutcome)

        executor.failures.clear()
        assertIs<MigrationApplyResult.Success>(vm.applyPending())

        assertNull(vm.state.value.applyOutcome)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `the next apply that fails replaces the previous outcome`() = runBlocking<Unit> {
        add(migration("0003", "a", "b", "c"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        vm.applyPending()
        executor.failures.clear()
        executor.failures["c"] = ActionResult.Failed("the broker did not answer")

        vm.applyPending()

        assertEquals(ApplyOutcome.Failed("0003", 3, 3, "the broker did not answer"), vm.state.value.applyOutcome)
    }

    @Test
    fun `dismissOutcome clears the outcome, the progress and the composed error text, and nothing else of the list`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        vm.applyPending()
        val entries = vm.state.value.entries
        vm.importFile("this is not a migration") // an error text of another origin is on the screen too
        assertNotNull(vm.state.value.applyOutcome, "precondition: the apply left an outcome")
        assertNotNull(vm.state.value.errorMessage, "precondition: an error text is shown")

        vm.dismissOutcome()

        assertNull(vm.state.value.applyOutcome)
        assertNull(vm.state.value.applyProgress)
        assertNull(vm.state.value.errorMessage, "dismissOutcome clears the outcome, the progress and the error text (c1 decision, kept)")
        assertEquals(entries, vm.state.value.entries)
    }

    @Test
    fun `dismissError, the banner's Dismiss, also clears the outcome`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        vm.applyPending()
        vm.importFile("this is not a migration")
        assertNotNull(vm.state.value.applyOutcome, "precondition: the apply left an outcome")
        assertNotNull(vm.state.value.errorMessage, "precondition: an error text is shown")

        vm.dismissError()

        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.applyOutcome)
    }

    @Test
    fun `a blocked apply is not an outcome`() = runBlocking<Unit> {
        add(migration("0003", "a"))
        val vm = vm(level = TopologyControlLevel.READ_ONLY)

        assertIs<MigrationApplyResult.Blocked>(vm.applyPending())

        assertNull(vm.state.value.applyOutcome)
        assertTrue(executor.sent.isEmpty())
    }

    // ---- progress -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `progress is filled by the runner hook, one step per action, and cleared afterwards`() = runBlocking<Unit> {
        add(migration("0001", "a", "b"), migration("0002", "c"))
        val vm = vm()
        val seen = mutableListOf<ApplyProgress?>()
        executor.onSend = { seen += vm.state.value.applyProgress }

        assertIs<MigrationApplyResult.Success>(vm.applyPending())

        assertEquals(
            listOf<ApplyProgress?>(ApplyProgress("0001", 1, 2), ApplyProgress("0001", 2, 2), ApplyProgress("0002", 1, 1)),
            seen,
            "the progress shown while an action is sent is that action",
        )
        assertNull(vm.state.value.applyProgress, "cleared when the apply ends")
        assertFalse(vm.state.value.isApplying)
    }

    @Test
    fun `no progress step follows a failure and the progress is cleared`() = runBlocking<Unit> {
        add(migration("0003", "a", "b", "c"))
        executor.failures["b"] = ActionResult.Failed(refused)
        val vm = vm()
        val seen = mutableListOf<ApplyProgress?>()
        executor.onSend = { seen += vm.state.value.applyProgress }

        vm.applyPending()

        assertEquals(listOf<ApplyProgress?>(ApplyProgress("0003", 1, 3), ApplyProgress("0003", 2, 3)), seen)
        assertNull(vm.state.value.applyProgress)
    }

    @Test
    fun `apply single fills and clears the progress too`() = runBlocking<Unit> {
        add(migration("0003", "a", "b"))
        val vm = vm()
        val seen = mutableListOf<ApplyProgress?>()
        executor.onSend = { seen += vm.state.value.applyProgress }

        vm.applySingle("0003")

        assertEquals(listOf<ApplyProgress?>(ApplyProgress("0003", 1, 2), ApplyProgress("0003", 2, 2)), seen)
        assertNull(vm.state.value.applyProgress)
    }

    // ---- a connection that cannot apply ----------------------------------------------------------------------------------------------

    private val unavailable = "This broker runs RabbitMQ 3.x: it is a migration source only"

    @Test
    fun `an unavailable reason disables apply pending and apply single but not import, export or the dry run`() = runBlocking<Unit> {
        add(migration("0001", "a"))
        val vm = vm(unavailable = unavailable)

        val s = vm.state.value
        assertEquals(unavailable, s.unavailableReason)
        assertFalse(s.isApplyPendingEnabled, "Apply pending must be disabled")
        assertFalse(s.isApplySingleEnabled, "Apply single must be disabled")
        assertEquals(listOf("0001"), s.entries.map { it.file.version }, "the files are listed")
        assertNotNull(s.dryRun, "the dry run is shown")
        assertTrue(s.isImportEnabled && s.isExportEnabled)
        assertIs<MigrationValidationResult.Valid>(vm.importFile(migration("0002", "b").rawJson), "import keeps working")
        assertNotNull(vm.exportCurrentTopology(), "export keeps working")

        assertIs<MigrationApplyResult.Blocked>(vm.applyPending())
        assertIs<MigrationApplyResult.Blocked>(vm.applySingle("0001"))
        assertTrue(executor.sent.isEmpty(), "nothing may reach the broker")
    }

    @Test
    fun `without an unavailable reason the same migration can be applied`() {
        add(migration("0001", "a"))

        val s = vm().state.value

        assertNull(s.unavailableReason)
        assertTrue(s.isApplyPendingEnabled)
        assertTrue(s.isApplySingleEnabled)
    }

    // ---- the folder part: canShowFolder and the banner line ----------------------------------------------------------------------------

    private class ScriptedRepository(var listing: FolderListing) : MigrationFileRepository {
        override suspend fun load(): FolderListing = listing
        override suspend fun listFiles(): List<MigrationFile> = (listing as? FolderListing.Listed)?.files.orEmpty()
        override suspend fun importFile(rawJson: String): MigrationValidationResult = error("not used")
        override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile = error("not used")
        override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult = error("not used")
        override suspend fun nextVersion(): String = "0001"
    }

    private class ScriptedHistory(var result: HistoryResult = HistoryResult.Loaded(emptyMap())) : MigrationHistoryRepository {
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = emptyMap()
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) = Unit
        override suspend fun load(profileName: String): HistoryResult = result
    }

    private val folder = "/work/migrations"
    private val store = InMemoryMigrationFolderStore()
    private val chooser = FakeMigrationFolderChooser()
    private val history = ScriptedHistory()
    private val loadsOfChooser get() = chooser.startedIn.size

    private fun folderVm(
        listing: FolderListing?,
        unavailable: String? = null,
        permission: Boolean = true,
        level: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    ): MigrationsViewModel {
        if (listing != null) store.folder = folder
        val repository = listing?.let(::ScriptedRepository)
        return MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(history, executor),
            profileName = "uat-eu",
            hasConfigurePermission = permission,
            controlLevel = level,
            folderPorts = MigrationFolderPorts(store, chooser, { repository ?: error("no folder is chosen") }, history),
            migrationUnavailableReason = unavailable,
            scope = scope,
        )
    }

    private fun listed(readOnly: Boolean = false) = FolderListing.Listed(listOf(migration("0001", "a")), readOnly = readOnly)

    @Test
    fun `canShowFolder is true for a ready folder`() = assertTrue(folderVm(listed()).state.value.canShowFolder)

    @Test
    fun `canShowFolder is true for a read-only folder`() = assertTrue(folderVm(listed(readOnly = true)).state.value.canShowFolder)

    @ParameterizedTest
    @EnumSource(FolderProblem::class)
    fun `canShowFolder is false only for a missing folder`(problem: FolderProblem) {
        val s = folderVm(FolderListing.Unavailable(folder, problem)).state.value

        assertEquals(problem != FolderProblem.MISSING, s.canShowFolder, "a folder that is $problem")
    }

    @Test
    fun `canShowFolder is false while no folder is chosen`() = assertFalse(folderVm(null).state.value.canShowFolder)

    @Test
    fun `no problem at all shows no banner line`() = assertNull(folderVm(listed()).state.value.bannerMessage)

    // M5-08c2: the c1 interim strings are gone. The structured fields are what the screen draws; `bannerMessage` is only the Read-only level and
    // the missing configure permission text (the screen lets the unavailable banner win over it).

    @Test
    fun `an unavailable connection is in unavailableReason, not in the banner string`() {
        val s = folderVm(listed(), unavailable = unavailable).state.value

        assertEquals(unavailable, s.unavailableReason)
        assertNull(s.bannerMessage, "the interim 'Migrations are not offered...' string is gone")
    }

    @Test
    fun `an unreadable history is in historyProblem, not in the banner string`() {
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")

        val s = folderVm(listed()).state.value

        assertEquals(HistoryUnreadable("/h/migration-history.json", "The file is not valid JSON"), s.historyProblem)
        assertNull(s.bannerMessage, "the interim history string is gone")
    }

    @Test
    fun `an unusable folder is in the folder state, not in the banner string`() {
        for (problem in FolderProblem.entries) {
            val s = folderVm(FolderListing.Unavailable(folder, problem)).state.value

            assertEquals(MigrationFolderState.FolderUnavailable(folder, problem), s.folder)
            assertNull(s.bannerMessage, "the interim '<reason>: <path>' string is gone for $problem")
        }
    }

    @Test
    fun `a read-only folder and no folder chosen are in the folder state, not in the banner string`() {
        assertEquals(MigrationFolderState.FolderReadOnly(folder), folderVm(listed(readOnly = true)).state.value.folder)
        assertNull(folderVm(listed(readOnly = true)).state.value.bannerMessage)
        store.folder = null // the view models above chose a folder in this shared store; this one has none
        val none = folderVm(null)
        assertEquals(MigrationFolderState.ChooseFolder, none.state.value.folder)
        assertNull(none.state.value.bannerMessage, "the interim 'Choose a migrations folder first' string is gone")
    }

    @Test
    fun `a cancelled dialog changes nothing`() {
        val vm = folderVm(null)

        chooser.answerNext(null)
        vm.chooseFolder()

        assertEquals(MigrationFolderState.ChooseFolder, vm.state.value.folder)
        assertTrue(vm.state.value.entries.isEmpty())
        assertNull(store.folder)
    }

    @Test
    fun `the Read-only level keeps its banner text`() {
        val s = folderVm(listed(), permission = false, level = TopologyControlLevel.READ_ONLY).state.value

        assertEquals("Apply is disabled: this connection is set to Read-only", s.bannerMessage)
        assertFalse(s.isApplyPendingEnabled)
    }

    @Test
    fun `a missing configure permission keeps its existing text and comes after the level`() {
        val s = folderVm(listed(), permission = false).state.value

        val banner = assertNotNull(s.bannerMessage)
        assertTrue(banner.startsWith("Apply is disabled: the permission check found that user cannot change topology on uat-eu."), banner)
    }

    @Test
    fun `the chooser is never called by loading, retrying, switching the connection or applying`() = runBlocking<Unit> {
        val vm = folderVm(null)

        vm.loadMigrations()
        vm.retry()
        vm.setConnectionContext("prod-eu", "/", true, TopologyControlLevel.FULL_CONTROL)
        vm.applyPending()

        assertEquals(0, loadsOfChooser, "only the user's action (and one call per visit from the screen) opens the dialog")
    }
}
