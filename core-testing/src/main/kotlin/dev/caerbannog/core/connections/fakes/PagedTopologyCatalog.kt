package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow

/**
 * M5-08a: [FakeTopologyCatalog] with a SMALL page size, so a test can have several queue pages without 500+ rows. The real broker pages at
 * [TopologyCatalog.QUEUE_PAGE_SIZE]; a reader that works for any page size works for 500. Everything but `queues` is delegated; `queues`
 * pages over the rows the inner fake returns for page 1.. (all of its rows are collected through its own paging), name ascending as the broker does.
 * Every `queues` call is recorded in [pagesRequested]. [failPage] makes that page answer [failure].
 */
class PagedTopologyCatalog(private val inner: FakeTopologyCatalog, private val pageSize: Int) : TopologyCatalog {
    init { require(pageSize >= 1) }

    val pagesRequested: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList())
    private var failing: Pair<Int, CatalogResult.Failure>? = null

    fun failPage(page: Int, failure: CatalogResult.Failure) { failing = page to failure }

    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = inner.vhosts()

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> {
        pagesRequested += page
        failing?.let { (p, f) -> if (p == page) return f }
        val all = mutableListOf<QueueRow>()
        var innerPage = 1
        while (true) {
            when (val r = inner.queues(vhost, innerPage)) {
                is CatalogResult.Success -> { all += r.value.rows; if (innerPage >= r.value.pageCount) break; innerPage++ }
                is CatalogResult.Failure -> return r
            }
        }
        val sorted = all.sortedBy { it.name }
        val pageCount = maxOf((sorted.size + pageSize - 1) / pageSize, 1)
        if (page < 1 || page > pageCount) return CatalogResult.BadPaging
        return CatalogResult.Success(QueuePage(sorted.drop((page - 1) * pageSize).take(pageSize), page, pageCount, pageSize, sorted.size))
    }

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = inner.exchanges(vhost)
    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = inner.bindings(vhost)
    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = inner.queueDetail(vhost, name)
}
