package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolderChooser
import dev.caerbannog.core.migrations.fakes.InMemoryMigrationFolderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-08b: the folder states of the view model, with fake ports only (no file I/O in the view model; the real repositories have their own
 * tests). Dispatchers.Unconfined: the actions finish before they return, as a fake answers at once.
 */
class MigrationsViewModelFolderTest {

    private fun migration(version: String): MigrationFile {
        val raw = """{ "version": "$version", "description": "d", "up": [ { "action": "declareQueue", "name": "q$version" } ] }"""
        return (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
    }

    /** A repository that answers [listing] and counts how often it is asked; it holds no files. */
    private class ScriptedRepository(var listing: FolderListing) : MigrationFileRepository {
        var loads = 0
        var exports = 0
        var exportFails = false
        override suspend fun load(): FolderListing = listing.also { loads++ }
        override suspend fun listFiles(): List<MigrationFile> = (load() as? FolderListing.Listed)?.files.orEmpty()
        override suspend fun importFile(rawJson: String): MigrationValidationResult = error("the view model must not import here")
        override suspend fun exportTopology(version: String, description: String, actions: List<MigrationAction>): MigrationFile {
            exports++
            if (exportFails) throw MigrationWriteException("The file could not be written")
            return MigrationFile(version, description, actions, "{}", "x")
        }
        override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult = error("not used")
        override suspend fun nextVersion(): String = "0001"
    }

    private class ScriptedHistory : MigrationHistoryRepository {
        var result: HistoryResult = HistoryResult.Loaded(emptyMap())
        val applied = mutableMapOf<String, MutableMap<String, String>>()
        var moves = 0
        var moveResult: HistoryMoveResult = HistoryMoveResult.Moved("migration-history.json.unreadable-20261109-143005")
        val loadedFor = mutableListOf<String>()
        override suspend fun getAppliedMigrations(profileName: String) = applied[profileName].orEmpty()
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) {
            applied.getOrPut(profileName) { mutableMapOf() }[version] = checksum
        }
        override suspend fun load(profileName: String): HistoryResult {
            loadedFor += profileName
            val r = result
            return if (r is HistoryResult.Loaded) HistoryResult.Loaded(applied[profileName].orEmpty()) else r
        }
        override fun asideFileName() = "migration-history.json.unreadable-20261109-143005"
        override suspend fun moveAside(): HistoryMoveResult {
            moves++
            if (moveResult is HistoryMoveResult.Moved) result = HistoryResult.Loaded(emptyMap())
            return moveResult
        }
    }

    private val store = InMemoryMigrationFolderStore()
    private val chooser = FakeMigrationFolderChooser()
    private val history = ScriptedHistory()
    private val executor = FakeTopologyMigrationExecutor()
    private val repositories = mutableMapOf<String, ScriptedRepository>()
    private val asked = mutableListOf<String>()

    private fun repositoryAt(folder: String, listing: FolderListing): ScriptedRepository =
        ScriptedRepository(listing).also { repositories[folder] = it }

    private fun vm(profile: String = "uat-eu"): MigrationsViewModel = MigrationsViewModel(
        fileRepository = InMemoryMigrationFileRepository(),
        runner = MigrationRunner(history, executor),
        profileName = profile,
        folderPorts = MigrationFolderPorts(
            store = store,
            chooser = chooser,
            repositoryFor = { folder -> asked += folder; repositories.getValue(folder) },
            history = history,
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val folder = "/work/migrations"
    private fun listed(vararg versions: String, invalid: List<InvalidMigrationFile> = emptyList(), readOnly: Boolean = false) =
        FolderListing.Listed(versions.map(::migration), invalid, readOnly)

    @Test
    fun `no folder yet asks for one, reads nothing and disables apply, import and export`() {
        val vm = vm()
        val s = vm.state.value
        assertEquals(MigrationFolderState.ChooseFolder, s.folder)
        assertTrue(asked.isEmpty(), "no repository may be made without a folder: $asked")
        assertFalse(s.isApplyPendingEnabled)
        assertFalse(s.isImportEnabled)
        assertFalse(s.isExportEnabled)
        assertTrue(chooser.startedIn.isEmpty(), "the dialog opens on the user's action, not by itself")
    }

    @Test
    fun `choosing a folder stores it, lists its files and enables import and export`() {
        repositoryAt(folder, listed("0001", "0002"))
        chooser.answerNext(folder)
        val vm = vm()

        vm.chooseFolder()

        assertEquals(folder, store.folder)
        val s = vm.state.value
        assertEquals(MigrationFolderState.Ready(folder), s.folder)
        assertEquals(listOf("0001", "0002"), s.entries.map { it.file.version })
        assertTrue(s.isImportEnabled && s.isExportEnabled)
        assertTrue(s.isApplyPendingEnabled)
    }

    @Test
    fun `a cancelled chooser changes nothing`() {
        chooser.answerNext(null)
        val vm = vm()

        vm.chooseFolder()

        assertNull(store.folder)
        assertEquals(MigrationFolderState.ChooseFolder, vm.state.value.folder)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a remembered folder is listed at once without the dialog`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))

        val vm = vm("prod-eu")

        assertEquals(listOf("0001"), vm.state.value.entries.map { it.file.version })
        assertTrue(chooser.startedIn.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(FolderProblem::class)
    fun `a folder that cannot be used shows its path and the fixed reason and blocks apply, import and export`(problem: FolderProblem) {
        store.folder = folder
        repositoryAt(folder, FolderListing.Unavailable(folder, problem))

        val s = vm().state.value

        assertEquals(MigrationFolderState.FolderUnavailable(folder, problem), s.folder)
        assertEquals(
            when (problem) {
                FolderProblem.MISSING -> "The folder does not exist"
                FolderProblem.NOT_A_FOLDER -> "The path is not a folder"
                FolderProblem.UNREADABLE -> "The folder cannot be read"
            },
            problem.message,
        )
        assertFalse(s.isApplyPendingEnabled)
        assertFalse(s.isImportEnabled)
        assertFalse(s.isExportEnabled)
        assertTrue(s.entries.isEmpty())
    }

    @Test
    fun `a read-only folder lists its files but disables import and export with the read-only reason`() {
        store.folder = folder
        repositoryAt(folder, listed("0001", readOnly = true))

        val s = vm().state.value

        assertEquals(MigrationFolderState.FolderReadOnly(folder, "The folder is read-only"), s.folder)
        assertEquals(listOf("0001"), s.entries.map { it.file.version })
        assertFalse(s.isImportEnabled)
        assertFalse(s.isExportEnabled)
        assertTrue(s.isApplyPendingEnabled, "applying writes the history in the app's own folder, not the user's")
    }

    @Test
    fun `invalid files are listed apart and are not entries`() {
        store.folder = folder
        val bad = InvalidMigrationFile("0007.json", "the file is not valid JSON")
        repositoryAt(folder, listed("0001", invalid = listOf(bad)))

        val s = vm().state.value

        assertEquals(listOf(bad), s.invalidFiles)
        assertEquals(listOf("0001"), s.entries.map { it.file.version })
    }

    @Test
    fun `an unreadable history blocks applying, keeps the list and the apply call is refused without touching the broker`() = runBlocking {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/home/u/.caerbannog/migration-history.json", "The file is not valid JSON")
        val vm = vm()

        val s = vm.state.value
        assertEquals(HistoryUnreadable("/home/u/.caerbannog/migration-history.json", "The file is not valid JSON"), s.historyProblem)
        assertFalse(s.isApplyPendingEnabled)
        assertFalse(s.isApplySingleEnabled)
        assertEquals(listOf("0001"), s.entries.map { it.file.version })

        val result = vm.applyPending()

        assertIs<MigrationApplyResult.Blocked>(result)
        assertTrue(executor.executedActions.isEmpty(), "nothing may reach the broker while the history cannot be trusted")
        assertTrue(history.applied.isEmpty(), "nothing may be recorded")
        assertEquals(0, history.moves, "applying never moves the history file")
    }

    @Test
    fun `retry lists the folder again after the problem is gone`() {
        store.folder = folder
        val repo = repositoryAt(folder, FolderListing.Unavailable(folder, FolderProblem.MISSING))
        val vm = vm()
        assertIs<MigrationFolderState.FolderUnavailable>(vm.state.value.folder)

        repo.listing = listed("0001")
        vm.retry()

        assertEquals(MigrationFolderState.Ready(folder), vm.state.value.folder)
        assertEquals(listOf("0001"), vm.state.value.entries.map { it.file.version })
    }

    @Test
    fun `retry while the problem remains keeps the unavailable state and does not choose or create anything`() {
        store.folder = folder
        val repo = repositoryAt(folder, FolderListing.Unavailable(folder, FolderProblem.MISSING))
        val vm = vm()

        vm.retry()

        assertEquals(MigrationFolderState.FolderUnavailable(folder, FolderProblem.MISSING), vm.state.value.folder)
        assertEquals(folder, store.folder)
        assertTrue(chooser.startedIn.isEmpty())
        assertTrue(repo.loads >= 2, "retry must ask the folder again")
    }

    @Test
    fun `retry also re-reads an unreadable history`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        val vm = vm()
        assertNotNull(vm.state.value.historyProblem)

        history.result = HistoryResult.Loaded(emptyMap())
        vm.retry()

        assertNull(vm.state.value.historyProblem)
        assertTrue(vm.state.value.isApplyPendingEnabled)
    }

    @Test
    fun `changing the folder starts the dialog in the current folder, keeps the history and the connection`() {
        store.folder = folder
        val other = "/work/other"
        repositoryAt(folder, listed("0001"))
        repositoryAt(other, listed("0001", "0002"))
        history.applied["uat-eu"] = mutableMapOf("0001" to repositories.getValue(folder).listing.let { (it as FolderListing.Listed).files[0].checksum })
        chooser.answerNext(other)
        val vm = vm()

        vm.changeFolder()

        assertEquals(listOf<String?>(folder), chooser.startedIn, "the dialog gets the folder path and nothing else")
        assertEquals(other, store.folder)
        assertEquals(MigrationFolderState.Ready(other), vm.state.value.folder)
        assertEquals("uat-eu", vm.state.value.profileName)
        val status = vm.state.value.entries.associate { it.file.version to it.status }
        assertIs<MigrationStatus.Applied>(status["0001"], "the history belongs to the connection, not to the folder")
        assertIs<MigrationStatus.Pending>(status["0002"])
    }

    @Test
    fun `cancelling the change keeps the current folder`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        chooser.answerNext(null)
        val vm = vm()

        vm.changeFolder()

        assertEquals(folder, store.folder)
        assertEquals(MigrationFolderState.Ready(folder), vm.state.value.folder)
    }

    @Test
    fun `changing from an unavailable folder works and the chooser still only gets the path`() {
        store.folder = folder
        repositoryAt(folder, FolderListing.Unavailable(folder, FolderProblem.NOT_A_FOLDER))
        repositoryAt("/work/ok", listed("0001"))
        chooser.answerNext("/work/ok")
        val vm = vm()

        vm.changeFolder()

        assertEquals(listOf<String?>(folder), chooser.startedIn)
        assertEquals(MigrationFolderState.Ready("/work/ok"), vm.state.value.folder)
    }

    @Test
    fun `switching the connection keeps the folder and reads the history of the new connection`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.applied["uat-eu"] = mutableMapOf("0001" to repositories.getValue(folder).listing.let { (it as FolderListing.Listed).files[0].checksum })
        val vm = vm("uat-eu")
        assertIs<MigrationStatus.Applied>(vm.state.value.entries.single().status)

        vm.setConnectionContext("prod-eu")

        assertIs<MigrationStatus.Pending>(vm.state.value.entries.single().status)
        assertEquals(MigrationFolderState.Ready(folder), vm.state.value.folder)
        assertTrue(chooser.startedIn.isEmpty())
        assertEquals("prod-eu", history.loadedFor.last())
    }

    @Test
    fun `starting with an empty history asks first with the exact text and moves nothing yet`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        val vm = vm()

        vm.startWithEmptyHistory()

        assertEquals(
            "Move migration-history.json to migration-history.json.unreadable-20261109-143005 and start with an empty history? The old file is kept.",
            vm.state.value.confirmation,
        )
        assertEquals(0, history.moves)
        assertNotNull(vm.state.value.historyProblem)
    }

    @Test
    fun `confirming moves the file aside once, clears the problem and every migration is pending`() {
        store.folder = folder
        repositoryAt(folder, listed("0001", "0002"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        val vm = vm()
        vm.startWithEmptyHistory()

        vm.confirmEmptyHistory()

        assertEquals(1, history.moves)
        val s = vm.state.value
        assertNull(s.confirmation)
        assertNull(s.historyProblem)
        assertTrue(s.entries.all { it.status is MigrationStatus.Pending })
        assertTrue(s.isApplyPendingEnabled)
    }

    @Test
    fun `cancelling the question moves nothing and keeps applying blocked`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        val vm = vm()
        vm.startWithEmptyHistory()

        vm.cancelEmptyHistory()

        assertEquals(0, history.moves)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.historyProblem)
        assertFalse(vm.state.value.isApplyPendingEnabled)
    }

    @Test
    fun `confirming without a question asks nothing and moves nothing`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        val vm = vm()

        vm.confirmEmptyHistory()

        assertEquals(0, history.moves, "only a question the user saw may be confirmed")
    }

    @Test
    fun `a failed move shows a fixed message without the failure text and stays blocked`() {
        store.folder = folder
        repositoryAt(folder, listed("0001"))
        history.result = HistoryResult.Unreadable("/h/migration-history.json", "The file is not valid JSON")
        history.moveResult = HistoryMoveResult.Failed("SENTINEL-disk-text /secret/path")
        val vm = vm()
        vm.startWithEmptyHistory()

        vm.confirmEmptyHistory()

        val s = vm.state.value
        assertNotNull(s.historyProblem)
        assertFalse(s.isApplyPendingEnabled)
        val message = assertNotNull(s.errorMessage)
        assertFalse(message.contains("SENTINEL"), "the message must not carry the failure text: $message")
    }

    @Test
    fun `an export whose write fails returns null with a fixed message, writes no file and never throws`() = runBlocking {
        store.folder = folder
        val repo = repositoryAt(folder, listed("0001"))
        repo.exportFails = true
        val vm = vm()

        val result = runCatching { vm.exportCurrentTopology() }

        assertTrue(result.isSuccess, "the view model must not throw out of an export: ${result.exceptionOrNull()}")
        assertNull(result.getOrNull())
        assertEquals(1, repo.exports, "the export must have reached the folder repository")
        val message = assertNotNull(vm.state.value.errorMessage)
        assertFalse(message.contains("SENTINEL") || message.isBlank())
        assertEquals(listOf("0001"), vm.state.value.entries.map { it.file.version }, "the list is unchanged")
    }
}
