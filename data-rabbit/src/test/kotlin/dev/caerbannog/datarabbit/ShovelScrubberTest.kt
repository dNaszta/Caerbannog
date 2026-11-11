package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** M8-08: broker text loses every secret and every `scheme://user[:pw]@` before it can become a reason. */
class ShovelScrubberTest {
    @Test
    fun `every occurrence of every secret is removed`() {
        val out = ShovelScrubber.scrub("bad pw1 and pw1 again, also pw2", listOf("pw1", "pw2"))
        assertFalse(out.contains("pw1") || out.contains("pw2"), out)
    }

    @Test
    fun `a uri with user and password becomes stars at, and so does one with a user only`() {
        assertEquals("failed ***@old:5672/x", ShovelScrubber.scrub("failed amqp://bob:pw@old:5672/x"))
        assertEquals("failed ***@old", ShovelScrubber.scrub("failed amqps://bob@old"))
        assertEquals("a ***@h and b ***@h", ShovelScrubber.scrub("a amqp://u:p@h and b amqps://v:q@h"))
    }

    @Test
    fun `text without secrets or uris is unchanged, and an empty secret does not touch it`() {
        assertEquals("failed to connect to source", ShovelScrubber.scrub("failed to connect to source", listOf("")))
        assertEquals("see http://example.org/path", ShovelScrubber.scrub("see http://example.org/path"))
    }
}
