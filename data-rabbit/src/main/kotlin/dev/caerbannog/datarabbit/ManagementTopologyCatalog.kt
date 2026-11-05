package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow

/** M2-08a COMPILE STUB by the test developer (no behaviour): the developer builds it next to [ManagementStreamCatalog], every call a GET. */
internal class ManagementTopologyCatalog(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : TopologyCatalog {
    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = CatalogResult.Unexpected

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = CatalogResult.Unexpected

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = CatalogResult.Unexpected

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = CatalogResult.Unexpected

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = CatalogResult.Unexpected
}
