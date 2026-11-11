package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** M8-06e item 3: the notice after a saved report escapes the path (a user's folder and file name), like every string taken from outside. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportSavedTextTest {
    @Test
    fun `a plain path is unchanged`() {
        assertEquals("Report saved to /home/me/Reports/report 1.json", MigrateFromThreeTexts.reportSaved("/home/me/Reports/report 1.json"))
        assertEquals("Report saved to /home/ünï/報告.json", MigrateFromThreeTexts.reportSaved("/home/ünï/報告.json"))
    }

    @Test
    fun `a newline, a tab and the bidi override are written as unicode escapes`() {
        assertEquals("Report saved to /a\\u000Ab", MigrateFromThreeTexts.reportSaved("/a\nb"))
        assertEquals("Report saved to /a\\u0009b", MigrateFromThreeTexts.reportSaved("/a\tb"))
        assertEquals("Report saved to /a\\u202Eb", MigrateFromThreeTexts.reportSaved("/a\u202Eb"))
    }

    @Test
    fun `a backslash is doubled`() {
        assertEquals("Report saved to C:\\\\Users\\\\me", MigrateFromThreeTexts.reportSaved("C:\\Users\\me"))
    }

    @Test
    fun `the view model shows the escaped text for a path with a control character`() = runTest {
        val held = ScreenFiles.held()
        val holder = AnalysisHolder().also { it.hold(held) }
        val saver = FakeReportFileSaver(SaveOutcome.Saved("/home/me/re\nport\u202E.json"))
        val d = StandardTestDispatcher(testScheduler)
        val vm = MigrateFromThreeViewModel(FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), saver, holder, FakeMigrationFolders().ports, CoroutineScope(d), d)
        vm.exportReportJson(); advanceUntilIdle()
        val notice = assertNotNull((vm.state.value as MigrateFromThreeState.Report).notice, "no notice after the save")
        assertEquals(false, notice.failed)
        assertEquals("Report saved to /home/me/re\\u000Aport\\u202E.json", notice.text)
    }
}
