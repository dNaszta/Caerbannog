package dev.caerbannog.core.forwarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-07: the confirmation carries the sentences the spec decided (safeguards 1, 5, 7, 8), no password, and asks for the source queue name.
 * The checks look for the key words of each sentence (case-insensitive), so the wording stays the developer's within the spec.
 */
class ForwardingConfirmationTest {
    private fun confirmation(request: ForwardingRequest = Fwd.request(), facts: DryRunResult = Fwd.facts()): ForwardingConfirmation =
        ForwardingConfirmations.of(request, ForwardingPlanner.plan(Fwd.context(), request, facts))

    private fun ForwardingConfirmation.text() = lines.joinToString("\n")
    private fun ForwardingConfirmation.has(vararg words: String) = words.forEach { assertTrue(text().contains(it, ignoreCase = true), "missing \"$it\" in:\n${text()}") }

    @Test
    fun `the typed text is the source queue name, exactly`() {
        assertEquals("orders", confirmation().requiredText)
        assertEquals("a b-1", confirmation(Fwd.request(queue = "a b-1", destinationQueue = "other")).requiredText, "the source queue, not the destination")
    }

    @Test
    fun `it names the source broker, vhost and queue and the destination broker, vhost and queue`() {
        confirmation().has("legacy-eu", "new-eu", "orders", "/")
        val destination = confirmation(Fwd.request(destinationQueue = "orders-v2"))
        destination.has("orders-v2", "orders")
    }

    @Test
    fun `it says the messages are moved and leave the source when the destination confirms, and cannot be undone`() {
        confirmation().has("moved", "destination confirms", "undo")
    }

    @Test
    fun `it says the source queue is not deleted and the shovel keeps credentials on the destination until it is removed`() {
        confirmation().has("not deleted", "keeps credentials", "new-eu", "removed")
    }

    @Test
    fun `it says the source password is stored in clear text and any administrator of the destination broker can read it`() {
        confirmation().has("clear text", "administrator", "new-eu", "finishes")
    }

    @Test
    fun `never adds the warning that shovel and credentials stay`() {
        val plain = confirmation(Fwd.request(never = false))
        val never = confirmation(Fwd.request(never = true))
        assertTrue(never.lines.size > plain.lines.size, "no extra line for never")
        never.has("stay")
        assertFalse(plain.text().contains("never", ignoreCase = true))
    }

    @Test
    fun `a plain source address says Not encrypted and sets the flag, a TLS one does not`() {
        val plain = confirmation(Fwd.request(tls = false))
        assertTrue(plain.notEncrypted)
        plain.has("Not encrypted")
        val tls = confirmation(Fwd.request(tls = true))
        assertFalse(tls.notEncrypted)
        assertFalse(tls.text().contains("Not encrypted", ignoreCase = true))
    }

    @Test
    fun `the address in the lines has no password, whatever the user typed`() {
        val c = confirmation(Fwd.request(credentials = Fwd.creds()))
        assertTrue(c.lines.isNotEmpty())
        c.has("amqps://mover@legacy.example:5671/%2F")
        assertFalse(c.text().contains(Fwd.SENTINEL))
        val pattern = Regex("amqps?://[^@\\s]*:[^@\\s]*@")
        c.lines.forEach { assertFalse(pattern.containsMatchIn(it), "a password-looking address in: $it") }
    }
}
