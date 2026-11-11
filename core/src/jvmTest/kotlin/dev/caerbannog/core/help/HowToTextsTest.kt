package dev.caerbannog.core.help

import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14: the how-tos are complete, speak the labels of the screens and hold no secret. */
class HowToTextsTest {
    private val analyse = HowToTexts.ANALYSE_AND_GENERATE

    @Test
    fun `every how-to has an id, a title, needs, numbered steps, an if-it-goes-wrong list and key phrases`() {
        assertTrue(HowToTexts.all.isNotEmpty())
        HowToTexts.all.forEach { h ->
            assertTrue(h.id.isNotBlank() && h.title.isNotBlank(), "id or title of ${h.id}")
            assertTrue(h.needs.isNotEmpty() && h.steps.size >= 3 && h.ifWrong.isNotEmpty(), "${h.id}: needs ${h.needs.size}, steps ${h.steps.size}, ifWrong ${h.ifWrong.size}")
            assertTrue(h.keyPhrases.isNotEmpty() && h.docHeadings.isNotEmpty(), "${h.id}: key phrases and doc headings")
            assertEquals(h.id, h.id.lowercase().replace(' ', '-'), "the id is a tag suffix")
        }
        assertEquals(HowToTexts.all.size, HowToTexts.all.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun `analyse and generate says the five steps of the brief in order`() {
        assertEquals("analyse-generate", analyse.id)
        assertEquals("Analyse and generate", analyse.title)
        assertEquals(5, analyse.steps.size)
        val keys = listOf("Definitions file", "message-loss", "decision", "Generate migration", "Open Migrations")
        keys.zip(analyse.steps).forEach { (key, step) -> assertTrue(step.contains(key, ignoreCase = key == "decision"), "step \"$step\" should mention $key") }
        assertTrue(analyse.needs.joinToString(" ").contains("administrator"), "the live source needs the administrator tag")
    }

    @Test
    fun `the key phrases are the labels the screen draws`() {
        assertTrue(MigrateFromThreeTexts.SOURCE_FILE in analyse.keyPhrases, "Definitions file")
        assertTrue(MigrateFromThreeTexts.LIVE_DISABLED in analyse.keyPhrases, "Live connection")
        assertTrue("Generate migration" in analyse.keyPhrases && "Open Migrations" in analyse.keyPhrases)
    }

    @Test
    fun `the doc headings are the three sections of the guide`() {
        assertEquals(
            listOf("1. Analyse a 3.x broker from a definitions file", "2. Analyse a live 3.x broker", "3. Review the report and generate migration files"),
            analyse.docHeadings,
        )
    }

    @Test
    fun `no how-to holds a secret word, a url or a sentinel`() {
        HowToTexts.all.forEach { h ->
            val text = (listOf(h.title) + h.needs + h.steps + h.ifWrong).joinToString("\n")
            listOf("SENTINEL", "amqp://", "http://", "https://", "docs/").forEach { assertTrue(!text.contains(it), "${h.id} holds $it") }
        }
    }
}
