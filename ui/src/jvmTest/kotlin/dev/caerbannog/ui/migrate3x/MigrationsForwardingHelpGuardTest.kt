package dev.caerbannog.ui.migrate3x

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14b: no help text is written in the composables of the Migrations screen, the forwarding view or the profile card; `HelpTip` is never given a literal. */
class MigrationsForwardingHelpGuardTest {
    private val ui = File("src/commonMain/kotlin/dev/caerbannog/ui")
    private fun src(path: String) = File(ui, path).also { assertTrue(it.exists(), "$it is missing") }
    private val screens = listOf(src("migrations/MigrationsScreen.kt"), src("migrations/MigrationsStates.kt"), src("migrate3x/ForwardingView.kt"), src("connections/ProfileCard.kt"))
    private val fragments = listOf(
        "one action at a time. Applying twice", "kept in the app's own data", "Nothing is sent.", "changed after it was applied here", "never shows it",
        "Nothing was overwritten", "not marked applied", "The broker moves them", "must already exist on the 4.x broker", "cannot be undone by the app",
        "can differ from the address", "kept in memory only", "can be read on the network", "matches exactly", "stops the progress display",
        "Check the AMQP host", "still holds the source password", "never a queue",
    )

    @Test
    fun `no composable of the three screens holds a word of the approved help texts`() {
        val found = screens.flatMap { f -> fragments.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), found, "texts that belong in MigrationsWording, ForwardingTexts or HowToTexts")
    }

    @Test
    fun `a HelpTip is never called with a string literal as its text`() {
        val call = Regex("""HelpTip\(\s*(?:id\s*=\s*)?"[^"]*"\s*,\s*(?:text\s*=\s*)?"""")
        assertEquals(emptyList(), screens.filter { call.containsMatchIn(it.readText()) }.map { it.name }, "HelpTip texts come from core")
    }

    @Test
    fun `every screen draws its shared components`() {
        fun has(f: File, what: String) = f.readText().contains(what)
        assertTrue(has(src("migrations/MigrationsScreen.kt"), "HowToLink(") && has(src("migrations/MigrationsScreen.kt"), "HelpTip("), "the Migrations screen draws no HowToLink and HelpTip yet")
        assertTrue(has(src("migrate3x/ForwardingView.kt"), "HowToLink(") && has(src("migrate3x/ForwardingView.kt"), "HelpTip("), "the forwarding view draws no HowToLink and HelpTip yet")
        assertTrue(has(src("connections/ProfileCard.kt"), "HelpTip("), "the profile card draws no HelpTip yet")
    }
}
