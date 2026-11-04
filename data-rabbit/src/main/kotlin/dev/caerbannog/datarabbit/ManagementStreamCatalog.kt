package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PluginStatus
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.Visibility

/** [StreamCatalog] over the management API of one held connection. Reads only; every call is a GET. */
internal class ManagementStreamCatalog(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : StreamCatalog {

    override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> =
        call(requests.listStreams(request), on404 = CatalogResult.Unexpected, ManagementJson::parseStreamPage)

    override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> =
        call(requests.streamDetail(vhost, name), on404 = CatalogResult.NotFound, ManagementJson::parseStreamDetail)

    override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> =
        callPerStream(requests.publishers(vhost, stream), ManagementJson::parsePublishers)

    override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> =
        callPerStream(requests.consumers(vhost)) { ManagementJson.parseConsumers(it, stream) }

    override suspend fun capabilities(): CatalogResult<Capabilities> {
        val whoami = when (val reply = http.get(requests.whoami())) {
            is ManagementReply.Response -> reply
            else -> return CatalogResult.Unreachable
        }
        if (whoami.status == 401 && ManagementErrors.isNotManagementUser(whoami.body)) {
            // The plugin probe would be a 401 as well for this user, so the plugin stays UNKNOWN.
            return CatalogResult.Success(Capabilities(PluginStatus.UNKNOWN, Visibility.NONE))
        }
        if (whoami.status != 200) return ManagementErrors.map(whoami.status, whoami.body, CatalogResult.Unexpected)
        val tags = when (val parsed = ManagementJson.parseWhoamiTags(whoami.body)) {
            is CatalogResult.Success -> parsed.value
            is CatalogResult.Failure -> return parsed
        }
        val visibility = Visibility.fromTags(tags)
        val plugin = if (visibility == Visibility.NONE) PluginStatus.UNKNOWN else probePlugin()
        return CatalogResult.Success(Capabilities(plugin, visibility))
    }

    private suspend fun probePlugin(): PluginStatus {
        val reply = http.get(requests.pluginProbe())
        return when {
            reply is ManagementReply.Response && reply.status == 200 -> PluginStatus.PRESENT
            reply is ManagementReply.Response && reply.status == 404 -> PluginStatus.MISSING
            else -> PluginStatus.UNKNOWN
        }
    }

    // A 404 on a per-stream path means either a missing plugin or a missing stream: the top-level probe tells.
    private suspend fun <T> callPerStream(url: String, parse: (String) -> CatalogResult<T>): CatalogResult<T> {
        val result = call(url, on404 = CatalogResult.NotFound, parse)
        if (result != CatalogResult.NotFound) return result
        return if (probePlugin() == PluginStatus.MISSING) CatalogResult.PluginMissing else CatalogResult.NotFound
    }

    private suspend fun <T> call(url: String, on404: CatalogResult.Failure, parse: (String) -> CatalogResult<T>): CatalogResult<T> =
        when (val reply = http.get(url)) {
            is ManagementReply.Response ->
                if (reply.status == 200) parse(reply.body) else ManagementErrors.map(reply.status, reply.body, on404)
            ManagementReply.Unreachable, ManagementReply.TlsRefused -> CatalogResult.Unreachable
        }
}
