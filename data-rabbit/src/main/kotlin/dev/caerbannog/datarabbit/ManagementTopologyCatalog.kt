package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow

/**
 * [TopologyCatalog] over the management API of one held connection. Every call is a GET (the declarations one per page) of a queues, exchanges, bindings, policies, operator-policies or vhosts path:
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

    /**
     * M9-14: every page of the declarations, one GET per page, in order. A page that fails ends the read with that failure (never a partial list); a page
     * that is not the one asked for ends it with [CatalogResult.Unexpected] instead of looping on the same page.
     */
    override suspend fun queueDeclarations(vhost: String): CatalogResult<List<QueueDeclaration>> {
        val all = mutableListOf<QueueDeclaration>()
        var page = 1
        while (page <= MAX_DECLARATION_PAGES) {
            val reply = call(requests.queueDeclarations(vhost, page), on404 = CatalogResult.NotFound) { ManagementJson.parseQueueDeclarations(it, vhost) }
            val read = when (reply) {
                is CatalogResult.Success -> reply.value
                is CatalogResult.Failure -> return reply
            }
            if (read.page != page) return CatalogResult.Unexpected
            all += read.rows
            if (page >= read.pageCount) return CatalogResult.Success(all)
            page++
        }
        return CatalogResult.Unexpected
    }

    override suspend fun policies(vhost: String): CatalogResult<List<PolicyRow>> =
        call(requests.policies(vhost), on404 = CatalogResult.NotFound, ManagementJson::parsePolicies)

    override suspend fun operatorPolicies(vhost: String): CatalogResult<List<PolicyRow>> =
        call(requests.operatorPolicies(vhost), on404 = CatalogResult.NotFound, ManagementJson::parsePolicies)

    private suspend fun <T> call(url: String, on404: CatalogResult.Failure, parse: (String) -> CatalogResult<T>): CatalogResult<T> =
        when (val reply = http.get(url)) {
            is ManagementReply.Response ->
                if (reply.status == 200) parse(reply.body) else ManagementErrors.map(reply.status, on404)
            ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> CatalogResult.Unreachable
        }

    private companion object {
        /** A stop for a broker that keeps raising its own page count: 2,000 pages are a million queues. */
        const val MAX_DECLARATION_PAGES = 2_000
    }
}
