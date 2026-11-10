package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.DefinitionsFileChooser
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.migrate3x.ReportFileSaver
import dev.caerbannog.core.migrate3x.SaveOutcome
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.datarabbit.JvmDefinitionsFileLoader
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-06a: the app side of the Migrate from 3.x screen: the destination, the real loader over a view model, and the two native dialogs behind their seams. */
class MigrateFromThreeWiringTest {
    @TempDir
    lateinit var dir: File

    private fun needsDisplay() = assumeFalse(GraphicsEnvironment.isHeadless(), "no display: the Swing dialog thread cannot run")

    @Test
    fun `the drawer item opens the Migrate from 3 screen`() {
        val navigation = AppNavigation()
        navigation.open(AppDestination.MigrateFromThree)
        assertEquals(AppDestination.MigrateFromThree, navigation.screen.value)
    }

    @Test
    fun `the real file loader reads a definitions file into a report, and a new view model over the same holder shows it again`() = runBlocking {
        val file = File(dir, "legacy.json").apply { writeText("""{"rabbit_version":"3.13.7","queues":[{"name":"orders","vhost":"/","durable":true,"auto_delete":false,"arguments":{}}]}""") }
        val parts = MigrateFromThreeParts(JvmDefinitionsFileLoader(), FakeDefinitionsFileChooser(), FakeReportFileSaver(), AnalysisHolder())
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val first = MigrateFromThreeViewModel(parts.loader, parts.chooser, parts.saver, parts.holder, FakeMigrationFolders().ports, scope)
            first.open(file.path)
            val report = withTimeout(10_000) { first.awaitReport() }
            assertEquals("legacy.json", report.view.fileLabel)
            val second = MigrateFromThreeViewModel(parts.loader, parts.chooser, parts.saver, parts.holder, FakeMigrationFolders().ports, scope)
            assertEquals("legacy.json", assertIs<MigrateFromThreeState.Report>(second.state.value).view.fileLabel)
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun MigrateFromThreeViewModel.awaitReport(): MigrateFromThreeState.Report {
        while (true) {
            (state.value as? MigrateFromThreeState.Report)?.let { return it }
            check(state.value !is MigrateFromThreeState.Rejected) { "rejected: ${state.value}" }
            kotlinx.coroutines.yield()
        }
    }

    @Test
    fun `the chooser returns the picked path, and null when the dialog is cancelled`() {
        needsDisplay()
        val picked = File(dir, "a.json")
        var onEventThread: Boolean? = null
        val chooser: DefinitionsFileChooser = SwingDefinitionsFileChooser { onEventThread = javax.swing.SwingUtilities.isEventDispatchThread(); picked }
        assertEquals(picked.path, runBlocking { chooser.choose() })
        assertEquals(true, onEventThread, "the dialog runs on the Swing thread")
        assertNull(runBlocking { SwingDefinitionsFileChooser { null }.choose() })
    }

    @Test
    fun `the saver writes the text where the user chose and reports the path`() {
        needsDisplay()
        val target = File(dir, "report.json")
        var suggested: String? = null
        val saver: ReportFileSaver = SwingReportFileSaver({ _, name -> suggested = name; target })
        val outcome = runBlocking { saver.save("legacy-compatibility-report.json", "{\"schema\":1}") }
        assertEquals(SaveOutcome.Saved(target.path), outcome)
        assertEquals("legacy-compatibility-report.json", suggested)
        assertEquals("{\"schema\":1}", target.readText())
    }

    @Test
    fun `a cancelled save dialog writes nothing`() {
        needsDisplay()
        var wrote = false
        val outcome = runBlocking { SwingReportFileSaver({ _, _ -> null }, { _, _ -> wrote = true }).save("x.json", "text") }
        assertEquals(SaveOutcome.Cancelled, outcome)
        assertEquals(false, wrote)
    }

    @Test
    fun `a failed write gives a fixed reason, not the exception text`() {
        needsDisplay()
        val target = File(dir, "report.json")
        val outcome = runBlocking { SwingReportFileSaver({ _, _ -> target }, { _, _ -> throw IOException("/secret/path denied") }).save("x.json", "text") }
        val failed = assertIs<SaveOutcome.Failed>(outcome)
        assertEquals(SwingReportFileSaver.WRITE_FAILED, failed.reason)
        assertTrue(!failed.reason.contains("secret"))
    }

    @Test
    fun `a name without an extension gets json, a name with one is kept`() {
        assertEquals(File(dir, "report.json"), withJsonExtension(File(dir, "report")))
        assertEquals(File(dir, "report.txt"), withJsonExtension(File(dir, "report.txt")))
        assertNotNull(dir)
    }
}
