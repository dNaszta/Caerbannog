package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** M2-06-F-A: the hold logic through the HoldConnector seam, no broker. */
class HoldTest {
    private class FakeConnector(private val failCloseOf: Set<String> = emptySet()) : HoldConnector {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        private fun handle(key: String): AutoCloseable {
            opened += key
            return AutoCloseable {
                closed += key
                if (key in failCloseOf) throw IllegalStateException("close failed for $key")
            }
        }
        override fun openPublisher(stream: String, reference: String) = handle("publisher:$stream:$reference")
        override fun openConsumer(stream: String, name: String) = handle("consumer:$stream:$name")
    }

    private val streams = listOf("dev-orders", "dev-events")
    private val logs = mutableListOf<String>()

    private fun hold(c: HoldConnector) = Hold(c, streams, logs::add)

    @Test
    fun `one named publisher and one named consumer connect per stream`() {
        val c = FakeConnector()
        hold(c).run { assertEquals(4, c.opened.size, "opened while holding: ${c.opened}") }
        assertEquals(
            setOf(
                "publisher:dev-orders:dev-seed-publisher", "consumer:dev-orders:dev-seed-consumer",
                "publisher:dev-events:dev-seed-publisher", "consumer:dev-events:dev-seed-consumer",
            ),
            c.opened.toSet(),
        )
        assertEquals(4, c.opened.toSet().size, "duplicates in ${c.opened}")
    }

    @Test
    fun `nothing is closed while holding and everything is closed after Ctrl+C`() {
        val c = FakeConnector()
        hold(c).run { assertTrue(c.closed.isEmpty(), "closed too early: ${c.closed}") }
        assertEquals(c.opened.toSet(), c.closed.toSet())
    }

    @Test
    fun `cancellation while waiting still closes everything`() {
        val c = FakeConnector()
        assertFailsWith<InterruptedException> { hold(c).run { throw InterruptedException() } }
        assertEquals(c.opened.toSet(), c.closed.toSet())
        assertEquals(4, c.closed.size)
    }

    @Test
    fun `one failing close does not stop the others`() {
        val c = FakeConnector(failCloseOf = setOf("publisher:dev-orders:dev-seed-publisher"))
        runCatching { hold(c).run { } }
        assertEquals(4, c.closed.size, "closed only ${c.closed}")
    }

    @Test
    fun `a failing close of the handle closed first still closes all the others`() {
        val c = FakeConnector(failCloseOf = setOf("consumer:dev-events:dev-seed-consumer"))
        runCatching { hold(c).run { } }
        assertEquals(4, c.closed.size, "closed only ${c.closed}")
    }

    @Test
    fun `close is safe to call twice and closes each handle once`() {
        val c = FakeConnector()
        val h = hold(c)
        h.run { }
        h.close()
        assertEquals(c.closed.size, c.closed.toSet().size, "closed twice: ${c.closed}")
    }

    @Test
    fun `a failing open closes what was already opened`() {
        val c = object : HoldConnector {
            val inner = FakeConnector()
            override fun openPublisher(stream: String, reference: String) = inner.openPublisher(stream, reference)
            override fun openConsumer(stream: String, name: String): AutoCloseable = throw IllegalStateException("refused")
        }
        assertFailsWith<IllegalStateException> { Hold(c, streams, logs::add).run { } }
        assertEquals(c.inner.opened.toSet(), c.inner.closed.toSet())
    }

    @Test
    fun `logs name the streams and carry no secret`() {
        val c = FakeConnector()
        hold(c).run { }
        assertTrue(logs.isNotEmpty(), "nothing logged")
        assertTrue(logs.any { "dev-orders" in it })
        assertFalse(logs.any { "password" in it.lowercase() }, "log mentions a password: $logs")
    }

    @Test
    fun `the reference and consumer name are the readable ones`() {
        assertEquals("dev-seed-publisher", Hold.PUBLISHER_REFERENCE)
        assertEquals("dev-seed-consumer", Hold.CONSUMER_NAME)
    }
}
