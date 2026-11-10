package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.fakes.FakeMigrationFolderChooser
import dev.caerbannog.core.migrations.fakes.InMemoryMigrationFolderStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-08c2 open points from the M5-08c1 review: (1) a folder chosen through a view model that was rebuilt meanwhile must reach the CURRENT one;
 * (2) editing a file goes through the view model, whose `Invalid` reason is shown, not ignored; (3) an apply while one runs is refused.
 */
class MigrationsViewModelFollowUpTest {

    private fun migration(version: String, vararg queues: String): MigrationFile {
        val actions = queues.joinToString(", ") { """{ "action": "declareQueue", "name": "$it" }""" }
        return (MigrationParser.parse("""{ "version": "$version", "description": "d", "up": [ $actions ] }""") as MigrationValidationResult.Valid).migration
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** For the tests that hold an apply open: real threads, so the test thread is free to call the view model again and every wait has a timeout. */
    private val threads = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Suspends the action named [gateOn] until [release] is called; counts what was sent. */
    private class GatedExecutor(private val gateOn: String) : TopologyMigrationExecutor {
        val sent = mutableListOf<String>()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun execute(action: MigrationAction): ActionResult {
            val name = (action as MigrationAction.DeclareQueue).name
            sent += name
            if (name == gateOn) { reached.complete(Unit); release.await() }
            return ActionResult.Success
        }
    }

    // ---- (3) re-entrancy ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `an apply while another one runs is refused and sends nothing`() = runBlocking<Unit> {
        // (a failing assertion below leaves the gate closed; the test scope is abandoned with the test)
        val files = InMemoryMigrationFileRepository().also { it.addFile(migration("0003", "a", "b")) }
        val executor = GatedExecutor(gateOn = "a")
        val vm = MigrationsViewModel(fileRepository = files, runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor), scope = threads)
        val first = threads.launch { vm.applyPending() }
        withTimeout(5_000) { executor.reached.await() }
        assertTrue(vm.state.value.isApplying, "precondition: the first apply is running")

        // A second apply that is not refused would wait for the gate: the timeout turns that into an assertion, not a hang.
        val pending = withTimeoutOrNull(2_000) { vm.applyPending() }
        val single = withTimeoutOrNull(2_000) { vm.applySingle("0003") }

        assertEquals(MigrationApplyResult.Blocked("An apply is already running"), pending)
        assertEquals(MigrationApplyResult.Blocked("An apply is already running"), single)
        assertEquals(listOf("a"), executor.sent, "the refused calls must not send anything")
        executor.release.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertEquals(listOf("a", "b"), executor.sent)
        assertTrue(!vm.state.value.isApplying)
    }

    @Test
    fun `a refused second apply does not disturb the running one or leave an outcome`() = runBlocking<Unit> {
        val files = InMemoryMigrationFileRepository().also { it.addFile(migration("0003", "a")) }
        val executor = GatedExecutor(gateOn = "a")
        val vm = MigrationsViewModel(fileRepository = files, runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor), scope = threads)
        val first = threads.launch { vm.applyPending() }
        withTimeout(5_000) { executor.reached.await() }
        val progress = vm.state.value.applyProgress

        val refused = withTimeoutOrNull(2_000) { vm.applyPending() }

        assertEquals(MigrationApplyResult.Blocked("An apply is already running"), refused)
        assertEquals(progress, vm.state.value.applyProgress)
        assertNull(vm.state.value.applyOutcome)
        assertTrue(vm.state.value.isApplying)
        executor.release.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertNull(vm.state.value.applyOutcome)
    }

    @Test
    fun `after an apply ended a new one is allowed`() = runBlocking<Unit> {
        val files = InMemoryMigrationFileRepository().also { it.addFile(migration("0003", "a")) }
        val vm = MigrationsViewModel(fileRepository = files, runner = MigrationRunner(InMemoryMigrationHistoryRepository(), GatedExecutor(gateOn = "none")), scope = scope)

        assertIs<MigrationApplyResult.Success>(vm.applyPending())
        assertIs<MigrationApplyResult.Success>(vm.applyPending())
    }

    // ---- (2) edit file through the view model --------------------------------------------------------------------------------------------

    private class RecordingRepository(var listing: FolderListing, var verdict: (String) -> MigrationValidationResult) : MigrationFileRepository {
        val updates = mutableListOf<Pair<String, String>>()
        override suspend fun load(): FolderListing = listing
        override suspend fun listFiles(): List<MigrationFile> = (listing as? FolderListing.Listed)?.files.orEmpty()
        override suspend fun importFile(rawJson: String): MigrationValidationResult = error("not used")
        override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile = error("not used")
        override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult {
            updates += version to newRawJson
            return verdict(newRawJson)
        }
        override suspend fun nextVersion(): String = "0001"
    }

    private class QuietHistory : MigrationHistoryRepository {
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = emptyMap()
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) = Unit
    }

    private val folder = "/work/migrations"

    private fun folderVm(repository: MigrationFileRepository?, store: MigrationFolderStore = InMemoryMigrationFolderStore(folder), chooser: MigrationFolderChooser = FakeMigrationFolderChooser()): MigrationsViewModel {
        val history = QuietHistory()
        return MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(history, GatedExecutor("none")),
            folderPorts = MigrationFolderPorts(store, chooser, { repository ?: error("no repository") }, history),
            scope = scope,
        )
    }

    @Test
    fun `a valid edit is saved through the folder in use and the list is read again`() = runBlocking<Unit> {
        val repo = RecordingRepository(FolderListing.Listed(listOf(migration("0003", "a"))), verdict = { MigrationValidationResult.Valid(migration("0003", "a")) })
        val vm = folderVm(repo)
        val newText = """{ "version": "0003", "description": "changed", "up": [ { "action": "declareQueue", "name": "a" } ] }"""

        val result = vm.updateFile("0003", newText)

        assertIs<MigrationValidationResult.Valid>(result)
        assertEquals(listOf("0003" to newText), repo.updates)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `an invalid edit shows the parser's reason in errorMessage and changes nothing else`() = runBlocking<Unit> {
        val repo = RecordingRepository(FolderListing.Listed(listOf(migration("0003", "a"))), verdict = { MigrationValidationResult.Invalid("the value of \"name\" has the wrong type") })
        val vm = folderVm(repo)
        val entries = vm.state.value.entries

        val result = vm.updateFile("0003", "{}")

        assertEquals(MigrationValidationResult.Invalid("the value of \"name\" has the wrong type"), result)
        assertEquals("the value of \"name\" has the wrong type", vm.state.value.errorMessage, "the reason must be shown, not ignored")
        assertEquals(entries, vm.state.value.entries)
    }

    @Test
    fun `an edit with no folder chosen touches nothing and says it was not saved`() = runBlocking<Unit> {
        val vm = folderVm(repository = null, store = InMemoryMigrationFolderStore(null))

        val result = vm.updateFile("0003", "{}")

        assertIs<MigrationValidationResult.Invalid>(result)
        assertTrue(!vm.state.value.errorMessage.isNullOrBlank(), "the user must be told the file was not saved")
    }

    // ---- (1) a folder chosen reaches the current view model --------------------------------------------------------------------------------

    @Test
    fun `a folder chosen while the view model is rebuilt is shown by the current one`() = runBlocking<Unit> {
        val store = InMemoryMigrationFolderStore()
        val gate = CompletableDeferred<String?>()
        val gatedChooser = object : MigrationFolderChooser {
            override suspend fun choose(startIn: String?): String? = gate.await()
        }
        val repo = RecordingRepository(FolderListing.Listed(listOf(migration("0001", "a"))), verdict = { error("not used") })
        val first = folderVm(repo, store, gatedChooser)
        val asking = scope.launch { first.chooseFolder() }
        // The inputs change while the dialog is open (a permission check finished): the window builds a new view model on the same ports.
        val current = folderVm(repo, store, gatedChooser)
        assertEquals(MigrationFolderState.ChooseFolder, current.state.value.folder, "precondition: the new view model has no folder yet")

        gate.complete(folder)
        asking.join()

        assertEquals(folder, store.folder)
        assertEquals(MigrationFolderState.Ready(folder), current.state.value.folder, "the view model the user looks at must not stay on 'Choose folder'")
        assertEquals(listOf("0001"), current.state.value.entries.map { it.file.version })
    }

    @Test
    fun `a folder chosen without any rebuild is shown as before`() = runBlocking<Unit> {
        val store = InMemoryMigrationFolderStore()
        val chooser = FakeMigrationFolderChooser(folder)
        val repo = RecordingRepository(FolderListing.Listed(listOf(migration("0001", "a"))), verdict = { error("not used") })
        val vm = folderVm(repo, store, chooser)

        vm.chooseFolder()

        assertEquals(MigrationFolderState.Ready(folder), vm.state.value.folder)
    }
}
