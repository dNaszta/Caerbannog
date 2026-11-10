package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import io.cucumber.java.Before
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import io.cucumber.java.Scenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * M8-06a: the state of one screen scenario. [active] is true only for `@m8-06a` scenarios; a few older steps with the same words
 * (give the virtual host name, the report for X is shown, export the report, no request was sent) check it and hand over to the screen steps.
 * Virtual time: the view model runs on a test scheduler, [settle] runs it until idle. Plain fields, no logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object Migrate3xScreenWorld {
    var active = false
    val testScope = TestScope()
    var path = "/data/legacy.json"

    /** Path to canned load result; the one fake loader reads this map, so a file added later is seen by a view model that already exists. */
    val files = mutableMapOf<String, FileLoadResult>()
    val loader = FakeDefinitionsFileLoader(files)
    var chooser = FakeDefinitionsFileChooser()
    var saver = FakeReportFileSaver()

    /** M8-06b: the migrations folder the review saves into and the Migrations screen reads; `/migrations` is chosen unless a scenario says otherwise. */
    var folders = FakeMigrationFolders("/migrations")
    var holder = AnalysisHolder()
    var vm: MigrateFromThreeViewModel? = null
    var clipboard: String? = null
    var copiedName: String? = null
    var copiedExpected: String? = null
    var copiedLoss: String? = null
    var connectedTo: String? = null

    /** The only kind of request this screen could make; there is no gateway, so it is always empty. A step asserts it. */
    val brokerRequests = mutableListOf<String>()

    fun newViewModel(): MigrateFromThreeViewModel {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        return MigrateFromThreeViewModel(loader, chooser, saver, holder, folders.ports, CoroutineScope(dispatcher), dispatcher).also { vm = it }
    }

    fun viewModel(): MigrateFromThreeViewModel = vm ?: newViewModel()

    /**
     * M8-06e item 4: `reportText()` is `suspend` and renders on the view model's dispatcher, which here is a test dispatcher that only runs when
     * [settle] does. `runBlocking` would wait for it forever, so the call is started on the test scope and settled.
     */
    fun reportText(): String {
        val text = testScope.async { viewModel().reportText() }
        settle()
        return text.getCompleted()
    }

    fun setFile(text: String, label: String = "legacy.json") {
        path = "/data/$label"
        files[path] = FileLoadResult.Loaded(text)
    }

    /** Opens [path] on a fresh view model; a file that an older step left in [Migrate3xWorld.text] is used when none was set up here. */
    fun open() {
        if (files[path] == null && Migrate3xWorld.text.isNotEmpty()) files[path] = FileLoadResult.Loaded(Migrate3xWorld.text)
        newViewModel().open(path)
        settle()
    }

    /** The body of the older step "the report for {string} is shown" in `@m8-06a` scenarios: a file with a user, a hash and a shovel secret. */
    fun reportForFileIsShown(label: String) {
        setFile(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER), label)
        open()
        check(state() is MigrateFromThreeState.Report) { "expected a report, the screen is in ${state()}" }
    }

    /** The body of the older step "no report is shown" in `@m8-06a` scenarios. */
    fun assertNoReport() {
        assertTrue(state() !is MigrateFromThreeState.Report, "a report is shown: ${state()}")
        assertTrue(holder.held.value == null, "an analysis is held: ${holder.held.value}")
    }

    fun exportReport() {
        viewModel().exportReportJson()
        settle()
    }

    fun giveVirtualHost(name: String) {
        viewModel().giveVirtualHost(name)
        settle()
    }

    /** The body of the older step "no request was sent to any broker" in `@m8-06a` scenarios. */
    fun assertNoRequest() {
        assertTrue(state() is MigrateFromThreeState.Report, "nothing was analysed, so \"no request\" proves nothing: ${state()}")
        assertEquals(1, saver.saves.size, "the export did not happen, so \"no request\" proves nothing")
        assertEquals(emptyList<String>(), brokerRequests)
    }

    fun settle() = testScope.testScheduler.advanceUntilIdle()

    fun state(): MigrateFromThreeState = viewModel().state.value

    fun reset(active: Boolean) {
        this.active = active
        path = "/data/legacy.json"; files.clear(); loader.requestedPaths.clear()
        chooser = FakeDefinitionsFileChooser(); saver = FakeReportFileSaver(); holder = AnalysisHolder(); folders = FakeMigrationFolders("/migrations")
        vm = null; clipboard = null; copiedName = null; copiedExpected = null; copiedLoss = null; connectedTo = null; brokerRequests.clear()
    }
}

class Migrate3xScreenHooks {
    @Before
    fun resetScreenWorld(scenario: Scenario) = Migrate3xScreenWorld.reset("@m8-06a" in scenario.sourceTagNames)
}
