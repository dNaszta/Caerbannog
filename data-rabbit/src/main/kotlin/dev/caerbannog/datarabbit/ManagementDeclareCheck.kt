package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray

/**
 * The read-only [DeclareCheck] over the management API: ONE GET per action through the executor's [ResourceReader], so through
 * [ExecutorPathAllowList] (never a path under a queue's name, never a write). It adds no path and no comparison of its own: a policy is
 * compared by [ConflictCheck], everything else by [DeclaredResourceMatcher] as the apply compares it. The measured rules 3 and 4 (an honoured extra
 * argument, another number kind) are [HonouredArgumentRule]. A prediction only: the apply keeps its own read-back and conflict rule.
 *
 * Every failure (401, 403, 5xx, silence, a refusal, a name that cannot be a request) is [BrokerFinding.Unreadable] with a fixed text of
 * [ExecutorReasons]; the broker's body and exception text never reach a result.
 */
class ManagementDeclareCheck internal constructor(private val reader: ResourceReader) : DeclareCheck {

    override suspend fun find(action: MigrationAction): BrokerFinding {
        val reply = try {
            reader.read(action)
        } catch (_: PathNotAllowedException) {
            return BrokerFinding.Unreadable(ExecutorReasons.NOT_A_REQUEST)
        }
        if (reply !is ManagementReply.Response) return unreadable(reply)
        return when (reply.status) {
            NOT_FOUND -> BrokerFinding.Absent
            OK -> if (isListOnNonBindingPath(action, reply.body)) BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER) else compared(action, reply.body)
            else -> unreadable(reply)
        }
    }

    private fun compared(action: MigrationAction, body: String): BrokerFinding = when (action) {
        // A delete is not compared: the item is there or it is not.
        is MigrationAction.DeleteQueue, is MigrationAction.DeleteExchange, is MigrationAction.DeleteStream, is MigrationAction.DeletePolicy ->
            BrokerFinding.Same
        is MigrationAction.DeleteBinding -> BindingList.entries(body)
            ?.let { if (BindingList.plain(it, action.routingKey).isEmpty()) BrokerFinding.Absent else BrokerFinding.Same }
            ?: BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER)
        // A list of the bindings between two ends: another key or other arguments is another binding, so the declared one is absent.
        is MigrationAction.DeclareBinding -> BindingList.entries(body)
            ?.let { if (DeclaredResourceMatcher.matches(action, body)) BrokerFinding.Same else BrokerFinding.Absent }
            ?: BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER)
        // Rules 3 and 4 (M9-05b) predict the 400 of a repeated PUT; the matcher the read-back shares does not hold them.
        is MigrationAction.DeclareQueue, is MigrationAction.DeclareExchange ->
            if (DeclaredResourceMatcher.matches(action, body) &&
                HonouredArgumentRule.extraOnBroker(action, body) == null &&
                HonouredArgumentRule.otherNumberKind(action, body) == null
            ) BrokerFinding.Same else BrokerFinding.Different
        // A stream is judged by the matcher alone (type and the declared settings), exactly as the executor's pre-read judges it: apply and check
        // agree. Rules 3 and 4 are not applied: the executor never relies on a repeated create for a stream.
        is MigrationAction.DeclareStream,
        is MigrationAction.DeclarePolicy, is MigrationAction.DeclareOperatorPolicy ->
            if (DeclaredResourceMatcher.matches(action, body)) BrokerFinding.Same else BrokerFinding.Different
        // Access actions are never planned; the reader has a path for them but the check has no meaning for them.
        is MigrationAction.DeclareVhost, is MigrationAction.DeclareUser, is MigrationAction.SetPermissions, is MigrationAction.SetTopicPermissions ->
            BrokerFinding.Unreadable(ExecutorReasons.NOT_A_REQUEST)
    }

    /** `GET /api/queues/%2F/%2E` answers the vhost listing: a list is not the item, whatever the step. A binding path answers a list by design. */
    private fun isListOnNonBindingPath(action: MigrationAction, body: String): Boolean {
        if (action is MigrationAction.DeclareBinding || action is MigrationAction.DeleteBinding) return false
        return (parseUntrustedJson(body, LIST_PROBE_DEPTH) as? UntrustedJson.Parsed)?.element is JsonArray
    }

    private fun unreadable(reply: ManagementReply): BrokerFinding = BrokerFinding.Unreadable(ExecutorReasons.forReply(reply).reason)

    companion object {
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val LIST_PROBE_DEPTH = 64

        /**
         * TEST-ONLY seam, like [ManagementTopologyMigrationExecutor.overClient]: for code outside this module that cannot see `ManagementHttp`
         * (the acceptance glue). [baseUrl] is `http://host:port` (a plain, TLS-off profile). Production code never calls it.
         */
        fun overClient(client: HttpClient, baseUrl: String, username: String, password: String): ManagementDeclareCheck =
            ManagementDeclareCheck(ResourceReader(ManagementHttp(client, username, password), ManagementRequests(baseUrl, BrokerTransport.Plain)))
    }
}
