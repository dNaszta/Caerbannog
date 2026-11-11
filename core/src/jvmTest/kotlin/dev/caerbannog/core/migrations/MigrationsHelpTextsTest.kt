package dev.caerbannog.core.migrations

import dev.caerbannog.core.help.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-14b (owner decision D1): the texts of the Migrations screen, rows 21 to 27 of the approved table, ids `mig-<row>`. */
class MigrationsHelpTextsTest {
    private val expected = listOf(
        HelpText("mig-23", "The folder with your migration files. It is remembered for this machine and may be a git repository. The applied history is kept in the app's own data, not here."),
        HelpText("mig-24", "Shows what each action would do and whether it is allowed. Nothing is sent."),
        HelpText("mig-26", "The file changed after it was applied here."),
        HelpText("mig-27", "The file holds the real hash; the app never shows it."),
    )

    @Test
    fun `the tips of the Migrations screen are the approved texts, in table order`() {
        assertEquals(expected.map { it.id }, MigrationsWording.HELP_TIPS.map { it.id })
        expected.forEach { assertEquals(it.text, MigrationsWording.HELP_TIPS.firstOrNull { t -> t.id == it.id }?.text, "tip ${it.id}") }
    }

    @Test
    fun `the top line advice is the approved text`() {
        assertEquals("Apply migration files to the connected broker, one action at a time. Applying twice is safe: what already exists is left as it is.", MigrationsWording.ADVICE_TOP)
    }

    @Test
    fun `the advice after a failed apply depends on the outcome, and no secret or broker text is in it`() {
        val conflict = ApplyOutcome.Conflict("0003", 2, 5, "queue exists with other arguments", "orders")
        val failed = ApplyOutcome.Failed("0003", 2, 5, "the broker refused")
        val notRecorded = ApplyOutcome.NotRecorded("0003", "the history could not be written")
        assertEquals("Something with that name already exists with other settings. Nothing was overwritten. Change it on the broker or in the migration, then apply again.", MigrationsWording.applyAdvice(conflict))
        assertEquals("The run stopped at this action. Fix the cause and apply again; the file is not marked applied.", MigrationsWording.applyAdvice(failed))
        assertNull(MigrationsWording.applyAdvice(notRecorded), "the history problem has its own explanation; no extra advice")
        assertNull(MigrationsWording.applyAdvice(null))
        listOf(conflict, failed).forEach { o ->
            val advice = MigrationsWording.applyAdvice(o).orEmpty()
            assertTrue(!advice.contains("orders") && !advice.contains("refused") && !advice.contains("arguments"), "the advice is fixed text, not the outcome's: $advice")
        }
    }

    @Test
    fun `no tip or advice holds a secret word, a url or a sentinel`() {
        val all = MigrationsWording.HELP_TIPS.map { it.text } + MigrationsWording.ADVICE_TOP
        assertTrue(all.size > 1 && all.all { it.isNotBlank() }, "empty texts")
        all.forEach { text -> listOf("SENTINEL", "amqp://", "http://", "https://", "password:").forEach { assertTrue(!text.contains(it), "$it in: $text") } }
    }

    @Test
    fun `the existing words are untouched`() {
        assertEquals("This file holds password hashes. Edit it outside the app.", MigrationsWording.HASH_FILE_NO_EDIT)
    }
}
