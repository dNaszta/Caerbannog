package dev.caerbannog.core.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14b: the two how-tos of the Migrations screen and the forwarding view. `HowToDocsDriftTest` covers both through `HowToTexts.all`. */
class HowToTextsAppliedAndMovedTest {
    @Test
    fun `the three how-tos are all of them, in screen order`() {
        assertEquals(listOf("analyse-generate", "apply-migrations", "move-messages"), HowToTexts.all.map { it.id })
    }

    @Test
    fun `apply a migration names sections 4 and 5, the key phrases and five steps`() {
        val h = HowToTexts.APPLY_MIGRATIONS
        assertEquals("Apply a migration", h.title)
        assertEquals(listOf("4. Apply the migration to a 4.x broker", "5. Migrate users, virtual hosts and permissions"), h.docHeadings)
        assertEquals(setOf("Apply pending", "Full control", "administrator", "dry run", "conflict"), h.keyPhrases.toSet())
        assertEquals(5, h.steps.size)
        assertTrue(h.steps[0].contains("4.x") && h.steps[2].contains("dry run") && h.steps[3].contains("Apply pending"), "steps: ${h.steps}")
        assertTrue(h.needs.joinToString(" ").let { it.contains("administrator") && it.contains("Full control") && it.contains("configure") }, "needs: ${h.needs}")
        assertTrue(h.ifWrong.joinToString(" ").let { it.contains("stops") && it.contains("conflict") && it.contains("overwrite") }, "ifWrong: ${h.ifWrong}")
    }

    @Test
    fun `move the messages of a queue names sections 6 and 7, the key phrases and five steps`() {
        val h = HowToTexts.MOVE_MESSAGES
        assertEquals("Move the messages of a queue", h.title)
        assertEquals(listOf("6. Move the messages of a queue with a Shovel", "7. Check that a Shovel finished, or remove a failed one"), h.docHeadings)
        assertEquals(setOf("Forward messages", "Remove shovel", "Full control", "plugins", "confirm", "destination", "moved"), h.keyPhrases.toSet())
        assertEquals(5, h.steps.size)
        assertTrue(h.steps[0].contains("Forward messages") && h.steps[3].contains("Type") && h.steps[4].contains("Forward messages"), "steps: ${h.steps}")
        assertTrue(h.ifWrong.joinToString(" ").contains("Remove shovel"), "ifWrong: ${h.ifWrong}")
    }
}
