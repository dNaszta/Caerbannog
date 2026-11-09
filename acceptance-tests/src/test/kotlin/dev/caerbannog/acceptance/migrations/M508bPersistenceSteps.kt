package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.HistoryResult
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFileRepository
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolderChooser
import dev.caerbannog.datarabbit.FolderMigrationFileRepository
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.datarabbit.JsonFileMigrationHistoryRepository
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.opentest4j.TestAbortedException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * M5-08b step definitions: the `@m5-08b` scenarios of tests/bdd/features/10_topology_migrations.feature. The REAL repositories
 * ([FolderMigrationFileRepository], [JsonFileMigrationHistoryRepository], [JsonFileAppSettings] as the folder store) run on a private temp
 * folder; the folder chooser and the clock are fakes; no UI. The scenario's paths such as "/work/migrations" are labels: each is mapped to a
 * folder below the temp root, and the screen is asked for that real path. A "restart" builds every object again from the same files.
 * The shared view model and runner are those of [TopologyMigrationsSteps], reached through `TopologyMigrationsSteps.current`.
 * Scenarios that need a folder the machine cannot make unreadable or read-only (running as root) are skipped, not passed.
 */
class M508bPersistenceSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }

    private lateinit var root: Path
    private val dataDir: Path get() = root.resolve("app-data")
    private val settingsFile: File get() = dataDir.resolve("settings.json").toFile()
    private val historyFile: Path get() = dataDir.resolve("migration-history.json")

    private var clock: LocalDateTime = LocalDateTime.of(2026, 11, 9, 10, 0, 0)
    private val zone: ZoneId = ZoneId.of("UTC")
    private var chooser = FakeMigrationFolderChooser()
    private var repositoriesMade = mutableListOf<String>()
    private lateinit var settings: JsonFileAppSettings
    private lateinit var history: JsonFileMigrationHistoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val labelFolders = mutableMapOf<String, Path>()
    private var importedText = ""
    private var restoreLater = mutableListOf<Path>()
    private var snapshot: Map<String, String> = emptyMap()
    private var historyBytes: ByteArray = ByteArray(0)
    private var lastImport: MigrationValidationResult? = null

    @Before
    fun makeRoot() {
        root = Files.createTempDirectory("caerbannog-m508b-")
        clock = LocalDateTime.of(2026, 11, 9, 10, 0, 0)
        chooser = FakeMigrationFolderChooser()
        repositoriesMade = mutableListOf()
        labelFolders.clear()
        restoreLater = mutableListOf()
        importedText = ""
        lastImport = null
    }

    @After
    fun removeRoot() {
        restoreLater.forEach { it.toFile().setReadable(true); it.toFile().setWritable(true); it.toFile().setExecutable(true) }
        root.toFile().deleteRecursively()
    }

    // ---- world ----

    private fun folderFor(label: String): Path = labelFolders.getOrPut(label) { root.resolve("user-files").resolve(label.trimStart('/')) }

    private fun makeFolder(label: String): Path = Files.createDirectories(folderFor(label))

    /** Builds every app object from the files on disk (also what a restart does) and hooks the view model of the shared scenario state to them. */
    private fun activate() {
        Files.createDirectories(dataDir)
        settings = JsonFileAppSettings(settingsFile) {}
        history = JsonFileMigrationHistoryRepository(historyFile, NowProvider { clock.atZone(zone).toInstant().toEpochMilli() }, zone = zone)
        s.runner = MigrationRunner(history, s.executor)
        s.viewModel = null
        s.vmFactory = { newViewModel(s.profileName) }
    }

    private fun newViewModel(profile: String): MigrationsViewModel = MigrationsViewModel(
        fileRepository = dev.caerbannog.core.migrations.InMemoryMigrationFileRepository(),
        runner = s.runner,
        profileName = profile,
        vhost = "/",
        folderPorts = MigrationFolderPorts(
            store = settings,
            chooser = chooser,
            repositoryFor = { folder ->
                repositoriesMade += folder
                FolderMigrationFileRepository(Path.of(folder)) as MigrationFileRepository
            },
            history = history,
        ),
        scope = scope,
    )

    private fun openScreen(profile: String): MigrationsViewModel {
        s.profileName = profile
        s.viewModel = null
        return s.getVm()
    }

    private fun restart() {
        chooser = FakeMigrationFolderChooser()
        repositoriesMade = mutableListOf()
        activate()
    }

    private val vm: MigrationsViewModel get() = s.viewModel ?: s.getVm()

    private fun migrationText(version: String): String =
        "{\r\n\t\"version\": \"$version\",\r\n\t\"description\": \"Café $version\",\r\n\t\"up\": [ { \"action\": \"declareQueue\", \"name\": \"queue-$version\", \"type\": \"quorum\" } ]\r\n}\r\n"

    private fun writeUserFile(folder: Path, name: String, text: String) =
        Files.write(folder.resolve(name), text.toByteArray(Charsets.UTF_8))

    private fun tree(): Map<String, String> = Files.walk(root).use { stream ->
        stream.toList().associate { p ->
            root.relativize(p).toString() to if (Files.isRegularFile(p)) Files.readAllBytes(p).joinToString(",") else "<dir>"
        }
    }

    private fun versionsListed() = vm.state.value.entries.map { it.file.version }

    /** A folder with one pending migration "0001", chosen and remembered; the default of scenarios that only care about the history. */
    private fun ensureDefaultFolder() {
        if (settings.folder == null) {
            val folder = makeFolder("/work/migrations")
            writeUserFile(folder, "0001.json", migrationText("0001"))
            settings.folder = folder.toString()
        }
    }

    // ---- restart and the folder remembered ----

    @Given("the migrations folder is {string} and the migration {string} was imported and applied to {string}")
    fun folderWithAppliedMigration(label: String, version: String, profile: String) = runBlocking {
        activate()
        val folder = makeFolder(label)
        settings.folder = folder.toString()
        val screen = openScreen(profile)
        importedText = migrationText(version)
        assertIs<MigrationValidationResult.Valid>(screen.importFile(importedText))
        val result = screen.applyPending()
        assertIs<MigrationApplyResult.Success>(result)
        assertEquals(listOf(version), result.appliedVersions)
    }

    @When("the app is restarted")
    fun theAppIsRestarted() {
        restart()
    }

    @Then("{string} is listed and shown as applied for {string}")
    fun listedAndApplied(version: String, profile: String) {
        val screen = openScreen(profile)
        val entry = screen.state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry, "\"$version\" is not listed; the list is ${versionsListed()}, the folder state ${screen.state.value.folder}")
        assertIs<MigrationStatus.Applied>(entry.status, "\"$version\" should be shown as applied for $profile")
    }

    @Then("its file in the folder holds exactly the imported text")
    fun fileHoldsImportedText() {
        val folder = Path.of(checkNotNull(settings.folder) { "no folder is remembered" })
        val stored = Files.readAllBytes(folder.resolve("0001.json"))
        assertTrue(importedText.toByteArray(Charsets.UTF_8).contentEquals(stored), "the stored file differs from the imported text")
    }

    @Given("no migrations folder has been chosen on this machine")
    fun noFolderChosen() {
        activate()
        snapshot = tree()
    }

    @Then("I am asked to choose the folder where my migration files live")
    fun askedToChoose() {
        assertEquals(MigrationFolderState.ChooseFolder, vm.state.value.folder)
    }

    @Then("no file is read or written and {string}, {string} and {string} are disabled")
    fun nothingTouchedAndDisabled(apply: String, import: String, export: String) {
        assertEquals("Apply pending", apply); assertEquals("Import", import); assertEquals("Export", export)
        assertTrue(repositoriesMade.isEmpty(), "a repository was made without a folder: $repositoriesMade")
        assertEquals(snapshot, tree(), "files changed although no folder was chosen")
        val st = vm.state.value
        assertFalse(st.isApplyPendingEnabled, "Apply pending must be disabled")
        assertFalse(st.isImportEnabled, "Import must be disabled")
        assertFalse(st.isExportEnabled, "Export must be disabled")
    }

    @Given("I chose the folder {string} on the Migrations screen of {string}")
    fun iChoseTheFolder(label: String, profile: String) {
        activate()
        val folder = makeFolder(label)
        writeUserFile(folder, "0001.json", migrationText("0001"))
        writeUserFile(folder, "0002.json", migrationText("0002"))
        chooser.answerNext(folder.toString())
        openScreen(profile).chooseFolder()
    }

    @When("I open the migrations screen of {string} after a restart")
    fun openAfterRestart(profile: String) {
        restart()
        openScreen(profile)
    }

    @When("I open the migrations screen of {string}")
    fun openScreenOf(profile: String) {
        openScreen(profile)
    }

    @Then("the files of {string} are listed")
    fun filesOfFolderListed(label: String) {
        assertEquals(MigrationFolderState.Ready(folderFor(label).toString()), vm.state.value.folder)
        assertEquals(listOf("0001", "0002"), versionsListed())
    }

    @Then("I am not asked for a folder again")
    fun notAskedAgain() {
        assertTrue(chooser.startedIn.isEmpty(), "the folder dialog was opened again: ${chooser.startedIn}")
        assertIs<MigrationFolderState.Ready>(vm.state.value.folder)
    }

    // ---- history stays with the connection ----

    @Given("{string} is applied to {string} and I choose another folder that also holds {string} with the same content")
    fun appliedThenOtherFolder(version: String, profile: String, sameVersion: String) = runBlocking {
        folderWithAppliedMigration("/work/migrations", version, profile)
        val other = makeFolder("/work/other-migrations")
        writeUserFile(other, "$sameVersion.json", importedText)
        chooser.answerNext(other.toString())
        vm.changeFolder()
    }

    @Then("{string} is shown as applied")
    fun shownAsApplied(version: String) {
        val entry = vm.state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry, "\"$version\" is not listed; the list is ${versionsListed()}")
        assertIs<MigrationStatus.Applied>(entry.status)
    }

    @Then("the history is kept in the app's own data, not in the chosen folder")
    fun historyInAppData() {
        assertTrue(Files.isRegularFile(historyFile), "the history file is missing from the app's data folder")
        val chosen = Path.of(checkNotNull(settings.folder))
        assertEquals(folderFor("/work/other-migrations"), chosen)
        val names = Files.list(chosen).use { st -> st.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("0001.json"), names, "the chosen folder must hold migration files only")
    }

    // ---- folder problems ----

    @Given("the chosen folder {string} no longer exists")
    fun folderGone(label: String) {
        activate()
        settings.folder = folderFor(label).toString()
        snapshot = tree()
    }

    @Then("I see the path and that the folder does not exist")
    fun seePathMissing() {
        val path = checkNotNull(settings.folder)
        assertEquals(MigrationFolderState.FolderUnavailable(path, FolderProblem.MISSING.message), vm.state.value.folder)
        assertEquals("The folder does not exist", FolderProblem.MISSING.message)
    }

    @Then("I can try again or choose another folder")
    fun tryAgainOrChoose() {
        val path = checkNotNull(settings.folder)
        vm.retry()
        assertIs<MigrationFolderState.FolderUnavailable>(vm.state.value.folder, "a retry with the folder still missing must keep saying so")
        chooser.answerNext(null)
        vm.changeFolder()
        assertEquals(listOf<String?>(path), chooser.startedIn, "Choose another folder opens the dialog on the folder that is gone")
        assertIs<MigrationFolderState.FolderUnavailable>(vm.state.value.folder)
    }

    @Then("the folder was not created and {string} is disabled")
    fun notCreatedAndDisabled(button: String) {
        assertEquals("Apply pending", button)
        val path = Path.of(checkNotNull(settings.folder))
        assertFalse(Files.exists(path), "the app created the missing folder")
        assertFalse(Files.exists(path.parent), "the app created a parent of the missing folder")
        assertFalse(vm.state.value.isApplyPendingEnabled)
        assertEquals(snapshot, tree())
    }

    @Given("the chosen folder cannot be read")
    fun folderCannotBeRead() {
        activate()
        val folder = makeFolder("/work/migrations")
        writeUserFile(folder, "0001.json", migrationText("0001"))
        folder.toFile().setReadable(false); folder.toFile().setExecutable(false)
        restoreLater.add(folder)
        if (Files.isReadable(folder)) throw TestAbortedException("cannot make a folder unreadable on this machine (running as root?)")
        settings.folder = folder.toString()
    }

    @Then("I see the path and that the folder cannot be read")
    fun seePathUnreadable() {
        assertEquals(
            MigrationFolderState.FolderUnavailable(folderFor("/work/migrations").toString(), FolderProblem.UNREADABLE.message),
            vm.state.value.folder,
        )
        assertEquals("The folder cannot be read", FolderProblem.UNREADABLE.message)
    }

    @Then("no file was written")
    fun noFileWritten() {
        val folder = folderFor("/work/migrations")
        folder.toFile().setReadable(true); folder.toFile().setExecutable(true)
        assertEquals(listOf("0001.json"), Files.list(folder).use { st -> st.map { it.fileName.toString() }.toList() })
        assertFalse(Files.exists(historyFile), "a history file was written")
    }

    @Given("the chosen folder can be read but not written")
    fun folderReadOnly() {
        activate()
        val folder = makeFolder("/work/migrations")
        writeUserFile(folder, "0001.json", migrationText("0001"))
        folder.toFile().setWritable(false)
        restoreLater.add(folder)
        if (Files.isWritable(folder)) throw TestAbortedException("cannot make a folder read-only on this machine (running as root?)")
        settings.folder = folder.toString()
    }

    @Then("its migrations are listed")
    fun itsMigrationsListed() {
        assertEquals(listOf("0001"), versionsListed())
    }

    @Then("{string} and {string} are disabled and the reason says the folder is read-only")
    fun importExportDisabledReadOnly(import: String, export: String) {
        assertEquals("Import", import); assertEquals("Export", export)
        val st = vm.state.value
        assertFalse(st.isImportEnabled)
        assertFalse(st.isExportEnabled)
        assertEquals(
            MigrationFolderState.FolderReadOnly(folderFor("/work/migrations").toString(), "The folder is read-only"),
            st.folder,
        )
    }

    // ---- history file problems ----

    @Given("the applied-migrations history file on disk is not valid JSON")
    fun historyNotJson() {
        activate()
        ensureDefaultFolder()
        historyBytes = "{\"schema\":1,\"profiles\":{ \"uat-eu\": SENTINEL-not-json".toByteArray(Charsets.UTF_8)
        Files.write(historyFile, historyBytes)
    }

    @Then("I see the path of the file and that it could not be read")
    fun seeHistoryPath() {
        val problem = assertNotNull(vm.state.value.historyProblem, "the screen did not report the unreadable history")
        assertEquals(historyFile.toString(), problem.path)
        assertTrue(problem.reason.isNotBlank())
        assertFalse(problem.reason.contains("SENTINEL"), "the reason must not carry file content")
        assertIs<HistoryResult.Unreadable>(runBlocking { history.load("uat-eu") })
    }

    @Then("the history file still has exactly its original bytes")
    fun historyBytesUnchanged() {
        assertTrue(historyBytes.contentEquals(Files.readAllBytes(historyFile)), "the unreadable history file was changed")
    }

    @Given("^the clock says (\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})$")
    fun clockSays(y: String, mo: String, d: String, h: String, mi: String, sec: String) {
        clock = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), sec.toInt())
    }

    @When("I choose {string}")
    fun iChoose(action: String) {
        assertEquals("Start with an empty history", action)
        vm.startWithEmptyHistory()
    }

    @Then("I am asked {string}")
    fun iAmAsked(question: String) {
        assertEquals(question, vm.state.value.confirmation)
    }

    @When("I confirm")
    fun iConfirm() {
        vm.confirmEmptyHistory()
    }

    @Then("the old file exists under the new name with its original bytes and every migration is pending")
    fun oldFileUnderNewName() {
        val moved = dataDir.resolve("migration-history.json.unreadable-20261109-143005")
        assertTrue(Files.isRegularFile(moved), "the old history is not under its new name; the folder holds ${Files.list(dataDir).use { st -> st.map { it.fileName.toString() }.toList() }}")
        assertTrue(historyBytes.contentEquals(Files.readAllBytes(moved)), "the kept file has other bytes than the original")
        assertTrue(vm.state.value.entries.isNotEmpty() && vm.state.value.entries.all { it.status is MigrationStatus.Pending })
        assertEquals(null, vm.state.value.historyProblem)
    }

    // ---- invalid files and versions ----

    @Given("the migrations folder holds a file {string} that is not valid JSON")
    fun folderHoldsInvalidFile(name: String) {
        activate()
        val folder = makeFolder("/work/migrations")
        importedText = "{ this is not json SENTINEL"
        writeUserFile(folder, name, importedText)
        settings.folder = folder.toString()
    }

    @Then("{string} is listed as invalid with the reason")
    fun listedInvalid(name: String) {
        val invalid = vm.state.value.invalidFiles.firstOrNull { it.fileName == name }
        assertNotNull(invalid, "\"$name\" is not in the invalid list: ${vm.state.value.invalidFiles}")
        assertTrue(invalid.reason.isNotBlank())
        assertFalse(invalid.reason.contains("SENTINEL"))
        assertFalse(versionsListed().contains(name.removeSuffix(".json")), "an invalid file must not be a migration")
    }

    @Then("the file still exists unchanged")
    fun fileUnchanged() {
        val folder = folderFor("/work/migrations")
        val name = vm.state.value.invalidFiles.single().fileName
        assertEquals(importedText, String(Files.readAllBytes(folder.resolve(name)), Charsets.UTF_8))
    }

    @When("^I import a migration file whose version is (.*)$")
    fun importWithVersion(versionJson: String) = runBlocking {
        activate()
        val folder = makeFolder("/work/migrations")
        settings.folder = folder.toString()
        val screen = openScreen("uat-eu")
        snapshot = tree()
        val text = """{ "version": $versionJson, "description": "d", "up": [ { "action": "declareQueue", "name": "q" } ] }"""
        lastImport = screen.importFile(text)
    }

    @Then("the import is refused and nothing is written outside the migrations folder")
    fun importRefusedNothingWritten() {
        assertIs<MigrationValidationResult.Invalid>(lastImport, "the import was not refused: $lastImport")
        assertEquals(snapshot, tree(), "files were written although the version was refused")
    }
}
