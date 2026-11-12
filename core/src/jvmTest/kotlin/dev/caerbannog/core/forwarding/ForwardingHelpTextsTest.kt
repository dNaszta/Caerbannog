package dev.caerbannog.core.forwarding

import dev.caerbannog.core.help.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14b (owner decisions D1 and D2): the texts of the forwarding view, rows 29 to 41 of the approved table, ids `fwd-<row>`. Row 36 uses the architect's safe wording. */
class ForwardingHelpTextsTest {
    private val expected = listOf(
        HelpText("fwd-33", "How the 4.x broker reaches the 3.x broker. It can differ from the address you use here."),
        HelpText("fwd-34", "Typed for this run, kept in memory only, never saved. Use a read-only user if you made one."),
        HelpText("fwd-36", "The shovel reaches the 3.x broker over a plain connection: anything sent on it, including the password, can be read on the network."),
        HelpText("fwd-37", "Nothing is sent until the name matches exactly."),
        HelpText("fwd-41", "It deletes only the shovel, never a queue."),
    )

    @Test
    fun `the tips of the forwarding view are the approved texts`() {
        assertEquals(expected.map { it.id }, ForwardingTexts.HELP_TIPS.map { it.id })
        expected.forEach { assertEquals(it.text, ForwardingTexts.HELP_TIPS.firstOrNull { t -> t.id == it.id }?.text, "tip ${it.id}") }
    }

    @Test
    fun `the always visible advice lines are the approved texts`() {
        assertEquals("Move the messages of one queue into the same queue on the 4.x broker. The broker moves them; the app only sets it up.", ForwardingTexts.ADVICE_TOP)
        assertEquals("One queue at a time. The destination queue must already exist on the 4.x broker: apply the migration first.", ForwardingTexts.ADVICE_PICK)
        assertEquals("Read this before you continue: this moves messages and cannot be undone by the app.", ForwardingTexts.ADVICE_MOVE)
        assertEquals("Leaving this screen stops the progress display, not the shovel. Open the queue again to see it.", ForwardingTexts.ADVICE_LEAVE)
    }

    @Test
    fun `what to do after a failure depends on whether the shovel was cleaned up`() {
        assertEquals("What to do: use a connection with the level Full control and click Remove shovel; the broker still holds the source password until then.", ForwardingTexts.failureAdvice(cleanedUp = false))
        assertEquals("Check the AMQP host, port and password of the 3.x broker, then forward again.", ForwardingTexts.failureAdvice(cleanedUp = true))
    }

    @Test
    fun `the existing safety lines are untouched`() {
        assertEquals("The messages are moved: they leave the source queue when the destination confirms. The app cannot undo it.", ForwardingTexts.MOVED)
        assertEquals("Not encrypted: the shovel reaches the source over a plain connection.", ForwardingTexts.NOT_ENCRYPTED)
        assertEquals("Removing a shovel needs Full control", ForwardingTexts.NEEDS_FULL_CONTROL)
    }

    @Test
    fun `no tip or advice holds a secret word, a url or a sentinel`() {
        val all = ForwardingTexts.HELP_TIPS.map { it.text } + listOf(ForwardingTexts.ADVICE_TOP, ForwardingTexts.ADVICE_PICK, ForwardingTexts.ADVICE_MOVE, ForwardingTexts.ADVICE_LEAVE, ForwardingTexts.failureAdvice(true), ForwardingTexts.failureAdvice(false))
        assertTrue(all.size > 5 && all.all { it.isNotBlank() }, "empty texts")
        all.forEach { text -> listOf("SENTINEL", "amqp://", "amqps://", "http://", "https://").forEach { assertTrue(!text.contains(it), "$it in: $text") } }
    }
}
