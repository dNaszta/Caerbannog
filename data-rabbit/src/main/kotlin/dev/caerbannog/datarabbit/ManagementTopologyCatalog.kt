package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow

/**
 * [TopologyCatalog] over the management API of one held connection. Every call is ONE GET of a queues, exchanges, bindings or vhosts path:
 * nothing here can read, get or purge a queue's messages (the guard test in `ManagementTopologyCatalogTest` enforces it).
 * A 404 is [CatalogResult.NotFound] (measured: a vhost the user has no permission on answers 404, never 403), except on the vhost list.
 */
internal class ManagementTopologyCatalog(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : TopologyCatalog {
    override suspend fun vhosts(): CatalogResult<List<VhostRow>> =
        call(requests.vhosts(), on404 = CatalogResult.Unexpected, ManagementJson::parseVhosts)

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> =
        call(requests.queues(vhost, page), on404 = CatalogResult.NotFound) { ManagementJson.parseQueuePage(it, vhost) }

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> =
        call(requests.exchanges(vhost), on404 = CatalogResult.NotFound, ManagementJson::parseExchanges)

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> =
        call(requests.bindings(vhost), on404 = CatalogResult.NotFound, ManagementJson::parseBindingList)

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> =
        call(requests.queueDetail(vhost, name), on404 = CatalogResult.NotFound, ManagementJson::parseQueueDetail)

    private suspend fun <T> call(url: String, on404: CatalogResult.Failure, parse: (String) -> CatalogResult<T>): CatalogResult<T> =
        when (val reply = http.get(url)) {
            is ManagementReply.Response ->
                if (reply.status == 200) parse(reply.body) else ManagementErrors.map(reply.status, on404)
            ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> CatalogResult.Unreachable
        }
}
