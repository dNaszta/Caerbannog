package dev.caerbannog.core.topology

import dev.caerbannog.core.streams.CatalogResult

/**
 * M2-08a: read-only view of the queues, exchanges and bindings of ONE virtual host, served by the management API.
 * Gateway pattern like [dev.caerbannog.core.streams.StreamCatalog]: core knows no HTTP client, `data-rabbit` implements it, tests use a fake.
 * No method can read, get, consume or purge a message: that is the type-level half of the safety rule (the other half is the guard test
 * in `data-rabbit`). Failures are [CatalogResult] values: [CatalogResult.NotAuthorised] (401 or 403: no management tag), [CatalogResult.NotFound]
 * (a vhost the user cannot see, a queue that is gone), [CatalogResult.Unreachable], [CatalogResult.BadPaging], [CatalogResult.Unexpected].
 * There is no `PluginMissing` here: topology needs no plugin.
 */
interface TopologyCatalog {
    /**
     * M2-08a round 2 (R3, spike S-E): the virtual hosts the broker lists for this user, in the broker's order (name ascending in practice).
     * MEASURED: an administrator and a `monitoring` user are listed EVERY vhost (the `monitoring` user can read only those it has a permission
     * on: the others answer [CatalogResult.NotFound] on the queue, exchange and binding calls), a `management` user only the vhosts it has a
     * permission on; no tag is [CatalogResult.NotAuthorised].
     */
    suspend fun vhosts(): CatalogResult<List<VhostRow>>

    /**
     * One page ([page] is 1-based, [QUEUE_PAGE_SIZE] queues) of the queues of [vhost], name ascending as the broker gives them.
     * Queues of type `stream` are NEVER returned (a stream is no queue here); the page numbers are the broker's, over all queues.
     */
    suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage>

    /** Every exchange of [vhost], the default exchange (name `""`) included; the caller hides it. */
    suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>>

    /** Every binding of [vhost] in one call, the implicit bindings of the default exchange included; the caller filters. */
    suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>>

    /**
     * M9-14: EVERY queue and stream of [vhost] with the flags and typed arguments the broker stores, for the topology export. Unpaged for the caller
     * (the real catalog reads the pages itself and returns all of them or one failure, never a partial list). Streams are included, unlike [queues].
     */
    suspend fun queueDeclarations(vhost: String): CatalogResult<List<QueueDeclaration>>

    /** M9-14: the policies of [vhost] (`GET /api/policies/{vhost}`); operator policies are NOT in it. */
    suspend fun policies(vhost: String): CatalogResult<List<PolicyRow>>

    /** M9-14: the operator policies of [vhost] (`GET /api/operator-policies/{vhost}`). */
    suspend fun operatorPolicies(vhost: String): CatalogResult<List<PolicyRow>>

    /** One queue, [CatalogResult.NotFound] when it does not exist (any more) or is a stream. */
    suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow>

    companion object {
        /** The broker's maximum page size, used for every queue page. */
        const val QUEUE_PAGE_SIZE: Int = 500
    }
}
