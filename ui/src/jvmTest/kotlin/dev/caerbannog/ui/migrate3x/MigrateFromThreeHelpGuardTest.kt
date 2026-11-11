package dev.caerbannog.ui.migrate3x

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14: no help text is written in a composable (CLAUDE.md: texts in core). The words of the approved table must not appear in the screen sources, and `HelpTip` is never given a literal. */
class MigrateFromThreeHelpGuardTest {
    private val dir = File("src/commonMain/kotlin/dev/caerbannog/ui/migrate3x")
    private val sources = listOf("MigrateFromThreeScreen.kt", "ReportState.kt", "ReviewPanels.kt", "ReviewSheet.kt", "ForwardingView.kt", "FailureBanner.kt")
        .map { File(dir, it) }.onEach { assertTrue(it.exists(), "$it is missing") }
    private val fragments = listOf(
        "Nothing is ever written to the 3.x broker", "one-virtual-host export has none", "GET requests only", "whatever the filter", "in-place upgrade",
        "unlocks when every item is decided", "proposed 4.x declaration", "can be put back", "never writes a delete", "order messages by priority",
        "Nothing is sent to any broker", "Apply the access file first", "applied first", "the app has no password entry", "their permissions are not written", "Delete the file when",
    )

    @Test
    fun `no composable of the three screens holds a word of the approved help texts`() {
        val found = sources.flatMap { f -> fragments.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), found, "texts that belong in MigrateFromThreeTexts or HowToTexts")
    }

    @Test
    fun `a HelpTip is never called with a string literal as its text`() {
        val call = Regex("""HelpTip\(\s*(?:id\s*=\s*)?"[^"]*"\s*,\s*(?:text\s*=\s*)?"""")
        val found = sources.filter { call.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), found, "HelpTip texts come from core")
    }

    @Test
    fun `the screen sources use the shared HelpTip and HowToLink components`() {
        val all = sources.joinToString("\n") { it.readText() }
        assertTrue(all.contains("HelpTip("), "the Migrate screen draws no HelpTip yet")
        assertTrue(all.contains("HowToLink("), "the Migrate screen draws no HowToLink yet")
    }
}
