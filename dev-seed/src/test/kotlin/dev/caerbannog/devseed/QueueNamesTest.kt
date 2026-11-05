package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * M2-12, C9: the queue-name generator for the many-queues vhost (D3 refresh-cost trial), tests first.
 * Contract: generate(count) returns exactly `count` names, all distinct, deterministic, every name valid for
 * RabbitMQ (non-empty, at most 255 bytes, no leading "amq.", plain ASCII letters digits and "-"), and listed in
 * lexical order equal to numeric order (zero-padded) so the broker's sorted list reads naturally. Negative count is
 * an IllegalArgumentException.
 */
class QueueNamesTest {
    @Test
    fun `count 0 gives an empty list`() {
        assertEquals(emptyList<String>(), QueueNames.generate(0))
    }

    @Test
    fun `count 1 gives one valid name`() {
        val names = QueueNames.generate(1)
        assertEquals(1, names.size)
        assertTrue(valid(names[0]), "invalid name: ${names[0]}")
    }

    @Test
    fun `count N gives N distinct valid names`() {
        for (n in listOf(2, 9, 10, 99, 100, 1000, 1001, 12345)) {
            val names = QueueNames.generate(n)
            assertEquals(n, names.size, "count $n")
            assertEquals(n, names.toSet().size, "names must be distinct for count $n")
            names.firstOrNull { !valid(it) }?.let { throw AssertionError("invalid name for count $n: $it") }
        }
    }

    @Test
    fun `lexical order equals numeric order`() {
        for (n in listOf(10, 1000, 1001, 12345)) {
            val names = QueueNames.generate(n)
            assertEquals(names, names.sorted(), "count $n")
        }
    }

    @Test
    fun `the result is deterministic and a smaller count is a prefix of a larger one`() {
        assertEquals(QueueNames.generate(1000), QueueNames.generate(1000))
        assertEquals(QueueNames.generate(5), QueueNames.generate(1000).take(5))
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<IllegalArgumentException> { QueueNames.generate(-1) }
    }

    private fun valid(name: String) =
        name.isNotEmpty() && name.toByteArray().size <= 255 && !name.startsWith("amq.") &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
}
