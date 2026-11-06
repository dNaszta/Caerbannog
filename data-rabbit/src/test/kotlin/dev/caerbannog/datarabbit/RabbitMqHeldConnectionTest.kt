package dev.caerbannog.datarabbit

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * M2-03 part 2 (no Docker), review follow-up F4 of part 1: the real holder logs only the exception CLASS NAME when
 * a `close()` throws (never the message, never a stack trace, which could carry a host, a URL or the password),
 * still closes everything else, never throws, and is idempotent (spec "Connection lifecycle": "Closing is idempotent").
 * The shared fakes of `core` jvmTest are not on this module's classpath; this file uses small local stubs.
 * Contract, package `dev.caerbannog.datarabbit`:
 *   class RabbitMqHeldConnection(catalog: StreamCatalog, offsets: StreamOffsets, topology: TopologyCatalog, resources: List<AutoCloseable>, log: (String) -> Unit) : HeldConnection
 *   `resources` are the HTTP client, the Environment holder and whatever else the holder owns; `close()` closes each exactly once.
 */
class RabbitMqHeldConnectionTest {

    private val secret = "Hunter2-do-not-log"

    private class Counting : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    private class Throwing(private val error: Throwable) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            throw error
        }
    }

    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun holder(vararg resources: AutoCloseable) =
        RabbitMqHeldConnection(StubCatalog, StubOffsets, StubTopology, StubMessageReader, StubPublisher, resources.toList()) { lines += it }

    @Test
    fun `close closes every resource once`() {
        val a = Counting()
        val b = Counting()
        holder(a, b).close()
        assertEquals(listOf(1, 1), listOf(a.closes, b.closes))
    }

    @Test
    fun `close is idempotent`() {
        val a = Counting()
        val held = holder(a)
        held.close()
        held.close()
        held.close()
        assertEquals(1, a.closes, "a second close must not close again")
    }

    @Test
    fun `a throwing close logs the class name only`() {
        val held = holder(Throwing(IllegalStateException("connection to https://guest:$secret@rabbit refused")))

        held.close()

        val text = lines.joinToString("\n")
        assertTrue(text.contains("IllegalStateException"), "expected the exception class name in the log, got: <$text>")
        assertFalse(text.contains(secret), "the message leaked into the log: <$text>")
        assertFalse(text.contains("rabbit refused"), "the message leaked into the log: <$text>")
        assertFalse(text.contains("\tat ") || text.contains(" at dev."), "no stack trace in the log: <$text>")
    }

    @Test
    fun `a throwing close does not stop the others and does not throw`() {
        val before = Counting()
        val bad = Throwing(RuntimeException(secret))
        val after = Counting()

        holder(before, bad, after).close()

        assertEquals(listOf(1, 1, 1), listOf(before.closes, bad.closes, after.closes), "every resource gets its close")
    }

    @Test
    fun `a failed close is not retried by a second close`() {
        val bad = Throwing(RuntimeException("x"))
        val held = holder(bad)
        held.close()
        held.close()
        assertEquals(1, bad.closes)
        assertEquals(1, lines.size, "one failure, one log line: $lines")
    }

    @Test
    fun `an exception whose cause carries the secret logs the outer class name only`() {
        holder(Throwing(RuntimeException("outer", java.io.IOException(secret)))).close()
        val text = lines.joinToString("\n")
        assertTrue(text.contains("RuntimeException"), text)
        assertFalse(text.contains(secret) || text.contains("outer"), text)
    }

    @Test
    fun `the holder exposes the catalog and the offsets it was built with`() {
        val held = holder()
        assertSame(StubCatalog, held.catalog)
        assertSame(StubOffsets, held.offsets)
        assertSame(StubTopology, held.topology)
        assertSame(StubPublisher, held.publisher)
    }
}

private object StubCatalog : StreamCatalog {
    override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = CatalogResult.Unexpected
    override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = CatalogResult.Unexpected
    override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = CatalogResult.Unexpected
    override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = CatalogResult.Unexpected
    override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = CatalogResult.Unexpected
    override suspend fun superStreams(vhost: String): CatalogResult<List<dev.caerbannog.core.streams.SuperStreamLayout>> = CatalogResult.Unexpected
    override suspend fun capabilities(): CatalogResult<Capabilities> = CatalogResult.Unexpected
}

private object StubOffsets : StreamOffsets {
    override suspend fun firstOffset(vhost: String, stream: String): FirstOffset = FirstOffset.Unavailable
}

private object StubTopology : TopologyCatalog {
    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = CatalogResult.Unexpected
    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = CatalogResult.Unexpected
    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = CatalogResult.Unexpected
    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = CatalogResult.Unexpected
    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = CatalogResult.Unexpected
}

private object StubMessageReader : StreamMessageReader {
    override suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult =
        BrowseResult.Failed("Stub")
}

private object StubPublisher : MessagePublisher {
    override suspend fun publish(request: PublishMessageRequest): PublishOutcome =
        PublishOutcome.Failed("Stub")
}
