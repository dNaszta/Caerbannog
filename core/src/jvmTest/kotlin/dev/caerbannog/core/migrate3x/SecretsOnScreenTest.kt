package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-06a: the M8-01 sentinels (password hash, shovel password) reach no state, no row, no text and no export of the screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretsOnScreenTest {
    private val sentinels = listOf(ScreenFiles.SENTINEL_HASH, ScreenFiles.SENTINEL_PASSWORD)

    private fun assertClean(what: String, text: String) = sentinels.forEach { assertFalse(text.contains(it), "$what holds $it") }

    @Test
    fun `no state, row, text export or json export holds a sentinel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val saver = FakeReportFileSaver()
        val loader = FakeDefinitionsFileLoader(mapOf("/x/legacy.json" to FileLoadResult.Loaded(ScreenFiles.withUserAndShovel)))
        val vm = MigrateFromThreeViewModel(loader, FakeDefinitionsFileChooser(), saver, AnalysisHolder(), dev.caerbannog.core.migrations.fakes.FakeMigrationFolders().ports, kotlinx.coroutines.CoroutineScope(dispatcher), dispatcher)
        vm.open("/x/legacy.json")
        advanceUntilIdle()
        var state = vm.state.value as? MigrateFromThreeState.Report ?: error("expected a report, got ${vm.state.value}")
        state.view.groups.forEach { vm.toggleGroup(it.key) }
        advanceUntilIdle()
        state = vm.state.value as MigrateFromThreeState.Report
        val rows = state.view.groups.flatMap { g -> state.view.rows(g.key).let { r -> (0 until r.size).map { r.at(it) } } }
        assertTrue(rows.any { it.user != null }, "the premise: the file has a user row")
        rows.forEach { assertClean("row $it", it.toString()) }
        assertClean("state", state.toString())
        assertClean("view", state.view.toString())
        assertClean("text export", vm.reportText())
        vm.exportReportJson()
        advanceUntilIdle()
        assertClean("json export", saver.saves.single().text)
        state.view.lossBlock?.let { assertClean("loss block", it.toString()) }
    }

    @Test
    fun `no state of the screen holds a sentinel`() {
        val held = ScreenFiles.held(ScreenFiles.withUserAndShovel)
        assertClean("held analysis", held.toString())
        listOf(
            MigrateFromThreeState.ChooseSource, MigrateFromThreeState.Loading("x"), MigrateFromThreeState.Rejected("x", "y"),
            MigrateFromThreeState.NeedsVirtualHost("x", null), MigrateFromThreeState.Report(ReportViewBuilder.build(held), null),
        ).forEach { assertClean(it.toString(), it.toString()) }
    }

    @Test
    fun `source guard - the new files never call expose and never import java`() {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/migrate3x")
        val names = listOf("MigrateFromThreeViewModel.kt", "MigrateFromThreePorts.kt", "MigrateFromThreeTexts.kt", "ReportView.kt", "ReportViewBuilder.kt", "FindingRowFormatter.kt")
        names.forEach { name ->
            val file = File(dir, name)
            assertTrue(file.exists(), "$file is missing")
            val text = file.readText()
            assertFalse(text.contains("expose("), "$name calls expose(")
            assertFalse(Regex("""(?m)^\s*import\s+java\.""").containsMatchIn(text), "$name imports java.*")
            assertFalse(Regex("""(?m)^\s*import\s+dev\.caerbannog\.core\.(connections|topology|streams)\.""").containsMatchIn(text), "$name imports a broker-facing package: the screen has no gateway")
        }
        assertEquals(names.size, names.toSet().size)
    }
}
