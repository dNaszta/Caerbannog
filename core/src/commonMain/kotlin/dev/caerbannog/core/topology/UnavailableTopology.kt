package dev.caerbannog.core.topology

import dev.caerbannog.core.streams.CatalogResult

/** M2-08b COMPILE STUB by the test developer (see `HeldConnection.topology`): every call is `Unreachable`. */
internal object UnavailableTopology : TopologyCatalog {
    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = CatalogResult.Unreachable

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = CatalogResult.Unreachable

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = CatalogResult.Unreachable

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = CatalogResult.Unreachable

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = CatalogResult.Unreachable
}
