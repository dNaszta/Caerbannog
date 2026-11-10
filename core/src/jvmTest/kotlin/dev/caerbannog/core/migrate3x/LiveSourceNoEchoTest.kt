package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeLiveSourceProvider
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-06c safety, like M8-06a: a `FakeThreeXReads` whose failure reasons and bodies carry sentinels (password, URL, token) gives no sentinel in any state, picker,
 * report view or text of the screen, for every outcome of the real `LiveDefinitionsSource` behind the real view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceNoEchoTest {
    private val password = "SENTINEL-PASSWORD-live-4711"
    private val url = "https://svc:SENTINEL-URL-PW@legacy-eu.example.com:15672/api/definitions"
    private val token = "SENTINEL-TOKEN-bearer"
    private val sentinels = listOf(password, url, token, "SENTINEL-URL-PW", "svc:")

    private fun assertClean(what: String, text: String) = sentinels.forEach { assertFalse(text.contains(it), "$what holds $it: $text") }

    private data class Run(val name: String, val configure: FakeThreeXReads.() -> Unit, val version: String? = "3.13.7")

    private val runs = listOf(
        Run("refused with a reason", { definitions = ReadOutcome.Failed("HTTP 500 from $url body={\"password\":\"$password\"} token=$token") }),
        Run("not an administrator", { definitions = ReadOutcome.NotPermitted }),
        Run("unreachable", { definitions = ReadOutcome.Unreachable }),
        Run("a body that is not definitions", { definitions = ReadOutcome.Ok("<html>$url $password $token</html>") }),
        Run("a per virtual host export", { definitions = ReadOutcome.Ok("""{"queues":[],"note":"$password"}""") }),
        Run("unreadable version", {}, version = null),
        Run("a 4.x broker", {}, version = "4.1.0"),
        Run("an export that holds secrets", {
            definitions = ReadOutcome.Ok(
                DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))).replaceFirst("\"queues\"", "\"users\":[{\"name\":\"svc\",\"password_hash\":\"$password\",\"tags\":\"administrator\"}],\"queues\""),
            )
        }),
    )

    @Test
    fun `no outcome of a live read puts a sentinel on the screen`() = runTest {
        for (run in runs) {
            val reads = FakeThreeXReads().apply(run.configure)
            val live = FakeLiveSourceProvider(LiveDefinitionsSource("legacy-eu", run.version, reads) { })
            val vm = MigrateFromThreeViewModel(
                FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), AnalysisHolder(), FakeMigrationFolders().ports,
                scope = CoroutineScope(StandardTestDispatcher(testScheduler)), analysisDispatcher = StandardTestDispatcher(testScheduler), liveSource = live,
            )
            advanceUntilIdle()
            vm.analyseLive(); advanceUntilIdle()
            val state = vm.state.value
            assertClean("${run.name}: state", state.toString())
            assertClean("${run.name}: picker", vm.picker.value.toString())
            when (state) {
                is MigrateFromThreeState.LiveFailed -> assertTrue(
                    state.text in setOf(
                        LiveSourceTexts.READ_FAILED, LiveSourceTexts.EXPORT_UNUSABLE, LiveSourceTexts.VERSION_UNREADABLE, LiveSourceTexts.NOT_THREE_X, "legacy-eu could not be reached",
                    ),
                    "${run.name}: the text is not one of the fixed ones: ${state.text}",
                )
                is MigrateFromThreeState.CannotExport -> assertEquals(LiveSourceTexts.CANNOT_EXPORT, state.text)
                is MigrateFromThreeState.Report -> {
                    assertClean("${run.name}: view", state.view.toString())
                    assertClean("${run.name}: summary", state.view.summaryLine + state.view.countsLine + state.view.source)
                    assertClean("${run.name}: report text", vm.reportText())
                    state.view.groups.forEach { vm.toggleGroup(it.key) }
                    advanceUntilIdle()
                    val open = vm.state.value as MigrateFromThreeState.Report
                    open.view.groups.forEach { g -> open.view.rows(g.key).let { rows -> (0 until rows.size).forEach { assertClean("${run.name}: row", rows.at(it).toString()) } } }
                }
                else -> kotlin.test.fail("${run.name}: a live read must end in a report, CannotExport or LiveFailed, but the state is $state")
            }
        }
    }

    @Test
    fun `a failed read shows exactly the fixed text of the source and an unreachable one names only the connection`() = runTest {
        suspend fun textOf(configure: FakeThreeXReads.() -> Unit): String {
            val live = FakeLiveSourceProvider(LiveDefinitionsSource("legacy-eu", "3.13.7", FakeThreeXReads().apply(configure)) { })
            val vm = MigrateFromThreeViewModel(
                FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), AnalysisHolder(), FakeMigrationFolders().ports,
                scope = CoroutineScope(StandardTestDispatcher(testScheduler)), analysisDispatcher = StandardTestDispatcher(testScheduler), liveSource = live,
            )
            advanceUntilIdle(); vm.analyseLive(); advanceUntilIdle()
            return kotlin.test.assertIs<MigrateFromThreeState.LiveFailed>(vm.state.value, "state was ${vm.state.value}").text
        }
        assertEquals(LiveSourceTexts.READ_FAILED, textOf { definitions = ReadOutcome.Failed("$url $password") })
        assertEquals("legacy-eu could not be reached", textOf { definitions = ReadOutcome.Unreachable })
    }
}
