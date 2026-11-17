package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.BrokerPolicyReader
import dev.caerbannog.core.planner.PolicyReadResult
import dev.caerbannog.core.streams.CatalogResult
import io.ktor.client.HttpClient

/**
 * The planner's one-policy read (M9-08): ONE `GET /api/policies/{vhost}/{name}` through the executor's [ResourceReader], so through
 * [ExecutorPathAllowList] (never the list, never an operator policy, never a path under a queue's name, GET only). The row is parsed with the
 * parser the export already has. Every failure (404, 401, 403, 5xx, silence, a body that is not one policy, a name that cannot be a request) and
 * a definition the model refuses (M9-14b: `definitionComplete` false) is [PolicyReadResult.Unreadable]: the broker's body and exception text
 * never reach a result.
 */
class ManagementBrokerPolicyReader internal constructor(private val reader: ResourceReader) : BrokerPolicyReader {

    override suspend fun read(vhost: String, name: String): PolicyReadResult {
        // The item path is built from a probe action, so the one path rule of the migration reads is the only one.
        val reply = try {
            reader.read(MigrationAction.DeclarePolicy(name = name, pattern = "", vhost = vhost))
        } catch (_: PathNotAllowedException) {
            return PolicyReadResult.Unreadable
        }
        if (reply !is ManagementReply.Response || reply.status != OK) return PolicyReadResult.Unreadable
        val row = (ManagementJson.parsePolicy(reply.body) as? CatalogResult.Success)?.value ?: return PolicyReadResult.Unreadable
        return if (row.definitionComplete) PolicyReadResult.Found(row) else PolicyReadResult.Unreadable
    }

    companion object {
        private const val OK = 200

        /** TEST-ONLY seam, like [ManagementDeclareCheck.overClient]: [baseUrl] is `http://host:port` (a plain, TLS-off profile). Production code never calls it. */
        fun overClient(client: HttpClient, baseUrl: String, username: String, password: String): ManagementBrokerPolicyReader =
            ManagementBrokerPolicyReader(ResourceReader(ManagementHttp(client, username, password), ManagementRequests(baseUrl, BrokerTransport.Plain)))
    }
}
