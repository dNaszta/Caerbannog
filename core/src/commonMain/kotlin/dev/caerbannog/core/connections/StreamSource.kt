package dev.caerbannog.core.connections

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
    val topology: dev.caerbannog.core.topology.TopologyCatalog,
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

// M2-08b COMPILE STUB by the test developer: a pass-through that does NOT guard yet. The developer makes it answer Unreachable when [isCurrent] is false.
internal class GuardedTopology(
    private val inner: dev.caerbannog.core.topology.TopologyCatalog,
    private val isCurrent: () -> Boolean,
) : dev.caerbannog.core.topology.TopologyCatalog by inner
