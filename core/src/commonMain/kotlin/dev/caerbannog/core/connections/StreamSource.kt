package dev.caerbannog.core.connections

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessageReader
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
import dev.caerbannog.core.streams.SuperStreamLayout
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow

/**
 * What a screen may use of the held connection: its catalog and offsets, and the virtual host of the profile.
 * Not closable on purpose. Compare sources by reference (`===`): a new connection is a new source even for the
 * same profile.
 */
class StreamSource(
    val catalog: StreamCatalog,
    val offsets: StreamOffsets,
    val vhost: String,
    /** M2-08b: the read-only topology of the same held connection, guarded like [catalog]. */
    val topology: TopologyCatalog,
    /** M3-01: the stream message browser reader of the same held connection, guarded like [catalog]. */
    val messages: StreamMessageReader,
)

// Proxy pattern: a source of a closed or superseded connection must never reach it (the connection may be gone).
internal class GuardedCatalog(private val inner: StreamCatalog, private val isCurrent: () -> Boolean) : StreamCatalog {
    private suspend fun <T> guarded(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        if (isCurrent()) call() else CatalogResult.Unreachable

    override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = guarded { inner.listStreams(request) }

    override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = guarded { inner.streamDetail(vhost, name) }

    override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = guarded { inner.publishers(vhost, stream) }

    override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = guarded { inner.publisherCounts(vhost) }

    override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = guarded { inner.consumers(vhost, stream) }

    override suspend fun superStreams(vhost: String): CatalogResult<List<SuperStreamLayout>> = guarded { inner.superStreams(vhost) }

    override suspend fun capabilities(): CatalogResult<Capabilities> = guarded { inner.capabilities() }
}

internal class GuardedOffsets(private val inner: StreamOffsets, private val isCurrent: () -> Boolean) : StreamOffsets {
    override suspend fun firstOffset(vhost: String, stream: String): FirstOffset =
        if (isCurrent()) inner.firstOffset(vhost, stream) else FirstOffset.Unavailable
}

internal class GuardedTopology(private val inner: TopologyCatalog, private val isCurrent: () -> Boolean) : TopologyCatalog {
    private suspend fun <T> guarded(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        if (isCurrent()) call() else CatalogResult.Unreachable

    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = guarded { inner.vhosts() }

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = guarded { inner.queues(vhost, page) }

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = guarded { inner.exchanges(vhost) }

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = guarded { inner.bindings(vhost) }

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = guarded { inner.queueDetail(vhost, name) }
}

internal class GuardedMessageReader(
    private val inner: StreamMessageReader,
    private val isCurrent: () -> Boolean,
) : StreamMessageReader {
    override suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult =
        if (isCurrent()) inner.readPage(vhost, stream, start, pageSize) else BrowseResult.Failed("Connection closed")
}
