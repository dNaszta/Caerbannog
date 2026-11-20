package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-15: `brokerVersionAtLeast(text, 4, 4, 1)` compares the three numbers; anything unreadable is false (the caller decides what unknown means). */
class BrokerVersionAtLeastTest {
    private fun atLeast441(text: String?) = brokerVersionAtLeast(text, 4, 4, 1)

    @Test
    fun `the table of the brief`() {
        val table = listOf(
            "4.4.0" to false, "4.4.1" to true, "4.4.10" to true, "4.10.0" to true, "5.0.0" to true, "4.3.99" to false,
            "3.13.7" to false, "4.4.1-rc.1" to true, "4.4.0-rc.1" to false, "4.5.0" to true, "10.0.0" to true, "4.4.2" to true, "4.0.2" to false,
        )
        for ((text, expected) in table) assertEquals(expected, atLeast441(text), text)
    }

    @Test
    fun `an unknown or unparsable version is never at least`() {
        for (text in listOf<String?>(null, "", " ", "4.4", "4", "x", "v4.4.1", "4.x.1", "4.4.x", "-4.4.1", "99999999999.0.0", "4.4.99999999999")) assertEquals(false, atLeast441(text), "[$text]")
    }

    @Test
    fun `numbers are compared as numbers, not as text`() {
        assertEquals(true, brokerVersionAtLeast("4.4.10", 4, 4, 9))
        assertEquals(false, brokerVersionAtLeast("4.4.9", 4, 4, 10))
        assertEquals(true, brokerVersionAtLeast("4.10.0", 4, 9, 9))
    }
}
