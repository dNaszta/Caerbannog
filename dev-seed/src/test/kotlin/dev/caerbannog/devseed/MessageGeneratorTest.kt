package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * M2-12, C9: the message generator, tests first.
 * Contract: body(stream, index) is a deterministic, non-empty, valid UTF-8 text that contains the stream name and the
 * index (so a message seen in the Management UI says where it came from); no clock, no randomness (same input, same
 * bytes, on any run). messages(stream, count) is a lazy sequence of exactly `count` bodies for indexes 0 until count,
 * and a negative count is an IllegalArgumentException.
 */
class MessageGeneratorTest {
    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test
    fun `count 0 gives no messages`() {
        assertEquals(0, MessageGenerator.messages("s", 0).count())
    }

    @Test
    fun `count 1 gives exactly one message for index 0`() {
        val list = MessageGenerator.messages("s", 1).toList()
        assertEquals(1, list.size)
        assertArrayEquals(MessageGenerator.body("s", 0), list[0])
    }

    @Test
    fun `count N gives N distinct messages in index order`() {
        val list = MessageGenerator.messages("orders", 1000).map(::text).toList()
        assertEquals(1000, list.size)
        assertEquals(1000, list.toSet().size, "every body must differ")
        assertEquals(text(MessageGenerator.body("orders", 999)), list.last())
    }

    @Test
    fun `a body names its stream and index and is not empty`() {
        val t = text(MessageGenerator.body("dev-orders", 42))
        assertTrue(t.isNotEmpty())
        assertTrue(t.contains("dev-orders"), "body should contain the stream name: $t")
        assertTrue(t.contains("42"), "body should contain the index: $t")
    }

    @Test
    fun `the same input gives the same bytes and other streams give other bytes`() {
        assertArrayEquals(MessageGenerator.body("a", 7), MessageGenerator.body("a", 7))
        assertTrue(!MessageGenerator.body("a", 7).contentEquals(MessageGenerator.body("b", 7)))
    }

    @Test
    fun `a body is valid UTF-8 text`() {
        val bytes = MessageGenerator.body("s", 3)
        assertEquals(text(bytes).toByteArray(Charsets.UTF_8).toList(), bytes.toList())
    }

    @Test
    fun `the sequence is lazy so a big count does not build a big list`() {
        val first = MessageGenerator.messages("s", Int.MAX_VALUE).first()
        assertArrayEquals(MessageGenerator.body("s", 0), first)
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<IllegalArgumentException> { MessageGenerator.messages("s", -1) }
    }
}
