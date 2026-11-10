package dev.caerbannog.ui.migrate3x

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-06c round 1 (finding 4): the fixed words of the live source are in `MigrateFromThreeTexts`, not in the composables (CLAUDE.md: formatting and decisions in core). */
class MigrateFromThreeTextsGuardTest {
    private val dir = File("src/commonMain/kotlin/dev/caerbannog/ui/migrate3x")
    private val moved = listOf("\"Nothing was analysed.\"", "\"Choose definitions file\"", "\"Live\"", "\"File\"", "In the 3.x management UI", "\"Analyse again\"", "\"Try again\"")

    @Test
    fun `no composable of the screen holds a fixed text that core owns`() {
        val files = listOf("MigrateFromThreeScreen.kt", "ReportState.kt").map { File(dir, it) }
        files.forEach { assertTrue(it.exists(), "$it is missing") }
        val found = files.flatMap { f -> moved.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), found, "texts that belong in MigrateFromThreeTexts")
    }
}
