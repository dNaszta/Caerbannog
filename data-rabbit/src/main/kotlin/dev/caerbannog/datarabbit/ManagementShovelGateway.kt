package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunOutcome
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.RemoveOutcome
import dev.caerbannog.core.forwarding.ShovelGateway
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.SrcDeleteAfter
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StatusOutcome
import dev.caerbannog.core.forwarding.percentEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The [ShovelGateway] over the management API of the held 4.x connection ([http], [requests]); the source probe uses a one-shot client from
 * [sourceHttp] (source profile and its stored password). Every request passes [ShovelPathAllowList]. The source password lives only in a local
 * `val` of [start]; no outcome text is ever broker text except the scrubbed status reason.
 */
internal class ManagementShovelGateway(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val profiles: (String) -> ConnectionProfile?,
    private val secrets: SecretStore,
    private val sourceHttp: (ConnectionProfile, String) -> ManagementHttp = { profile, password ->
        ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.from(profile), 10_000), profile.username, password)
    },
) : ShovelGateway {

    override suspend fun dryRun(request: ForwardingRequest): DryRunOutcome {
        val whoami = get(WHOAMI) as? ManagementReply.Response ?: return DryRunOutcome.DestinationUnreachable
        if (whoami.status != 200) return DryRunOutcome.Failed("The 4.x broker did not answer who you are (HTTP ${whoami.status})")
        val tags = tagsOf(whoami.body)
        val version = readVersion()

        val shovels = get(SHOVELS) as? ManagementReply.Response ?: return DryRunOutcome.DestinationUnreachable
        val plugins = shovels.status != 404
        val existing = if (shovels.status == 200) ShovelStatusMapping.entries(shovels.body).firstOrNull { ShovelStatusMapping.matches(it, request.shovel) }?.let(ShovelStatusMapping::statusOf) else null

        val queue = get(queuePath(request.destinationVhost, request.destinationQueue)) as? ManagementReply.Response ?: return DryRunOutcome.DestinationUnreachable
        val destination = when (queue.status) {
            404 -> DestinationQueue.Missing
            200 -> DestinationQueue.Exists(typeOf((parse(queue.body)?.asObject()?.get("type") as? JsonPrimitive)?.contentOrNull))
            else -> return DryRunOutcome.Failed("The 4.x broker did not let you read the destination queue (HTTP ${queue.status})")
        }
        val counts = when (val probed = probeSource(request)) {
            is Probe.Counts -> probed.counts
            is Probe.Stop -> return probed.outcome
        }
        return DryRunOutcome.Done(DryRunResult(counts, destination, plugins, tags, existing, version))
    }

    override suspend fun start(request: ForwardingRequest): StartOutcome {
        val source = request.source
        val password = source.credentials?.passwordForRequest() ?: secrets.retrieve(source.connectionName)
            ?: return StartOutcome.Failed("The password of ${source.connectionName} is not stored")
        val user = source.credentials?.username ?: source.address.username
        // The URI is built here and only here, from percent-encoded parts; it goes into the body and nowhere else.
        val uri = "${source.address.scheme}://${percentEncode(user)}:${percentEncode(password)}@" +
            "${ManagementRequests.formatAuthority(source.address.host, source.address.port)}/${percentEncode(source.address.vhost)}"
        val body = buildJsonObject {
            put("value", buildJsonObject {
                put("src-protocol", "amqp091")
                put("src-uri", uri)
                put("src-queue", source.queue)
                put("dest-protocol", "amqp091")
                put("dest-uri", "amqp://")
                put("dest-queue", request.destinationQueue)
                put("src-delete-after", if (request.srcDeleteAfter == SrcDeleteAfter.NEVER) "never" else "queue-length")
                put("ack-mode", request.ackMode.wireName)
            })
        }.toString()
        val url = requests.absolute(ShovelPathAllowList.checked("PUT", shovelPath(request.shovel)))
        val reply = http.put(url, body) as? ManagementReply.Response ?: return StartOutcome.Unreachable
        return when (reply.status) {
            201, 204 -> StartOutcome.Submitted
            401, 403 -> StartOutcome.NotPermitted
            // The broker's words are matched, never shown or logged (safeguard 7); the URI is never retried with credentials.
            400 -> if (reply.body.contains(DIRECT_REFUSAL_WORDS)) StartOutcome.DirectDestinationRefused else StartOutcome.Failed("The broker did not accept the shovel definition")
            else -> StartOutcome.Failed("The broker did not create the shovel (HTTP ${reply.status})")
        }
    }

    override suspend fun status(shovel: ShovelRef): StatusOutcome {
        val reply = get(SHOVELS) as? ManagementReply.Response ?: return StatusOutcome.Unreachable
        return when (reply.status) {
            200 -> ShovelStatusMapping.entries(reply.body).firstOrNull { ShovelStatusMapping.matches(it, shovel) }?.let { StatusOutcome.Listed(ShovelStatusMapping.statusOf(it)) } ?: StatusOutcome.Absent
            401, 403 -> StatusOutcome.NotPermitted
            404 -> StatusOutcome.Failed("The shovel plugins are not enabled")
            else -> StatusOutcome.Failed("The broker did not list the shovels (HTTP ${reply.status})")
        }
    }

    override suspend fun remove(shovel: ShovelRef): RemoveOutcome {
        val url = requests.absolute(ShovelPathAllowList.checked("DELETE", shovelPath(shovel)))
        val reply = http.delete(url) as? ManagementReply.Response ?: return RemoveOutcome.Unreachable
        return when (reply.status) {
            200, 204 -> RemoveOutcome.Removed
            404 -> RemoveOutcome.NotFound
            401, 403 -> RemoveOutcome.NotPermitted
            else -> RemoveOutcome.Failed("The broker did not remove the shovel (HTTP ${reply.status})")
        }
    }

    // ---- the source probe: one GET through a one-shot client, closed at once ----

    private sealed interface Probe {
        data class Counts(val counts: QueueCounts) : Probe
        data class Stop(val outcome: DryRunOutcome) : Probe
    }

    private suspend fun probeSource(request: ForwardingRequest): Probe {
        val name = request.source.connectionName
        val profile = profiles(name) ?: return Probe.Stop(DryRunOutcome.SourceNotPermitted)
        val password = secrets.retrieve(name) ?: return Probe.Stop(DryRunOutcome.SourceNotPermitted)
        val path = ShovelPathAllowList.checked("GET", queuePath(request.source.vhost, request.source.queue))
        val url = ManagementRequests.forProfile(profile).absolute(path)
        val reply = sourceHttp(profile, password).use { it.get(url) }
        if (reply !is ManagementReply.Response) return Probe.Stop(DryRunOutcome.SourceUnreachable(name))
        return when (reply.status) {
            200 -> {
                val json = parse(reply.body)?.asObject()
                Probe.Counts(QueueCounts(json.count("messages_ready"), json.count("messages_unacknowledged")))
            }
            401, 403 -> Probe.Stop(DryRunOutcome.SourceNotPermitted)
            404 -> Probe.Stop(DryRunOutcome.Failed("The source queue was not found on $name"))
            else -> Probe.Stop(DryRunOutcome.Failed("The source broker did not answer the queue read (HTTP ${reply.status})"))
        }
    }

    // ---- helpers ----

    /** `rabbitmq_version` from one GET of the overview; null for any failure, status or shape (a user without the management tag gets 401). */
    private suspend fun readVersion(): String? {
        val reply = get(OVERVIEW) as? ManagementReply.Response ?: return null
        if (reply.status != 200) return null
        val primitive = parse(reply.body)?.asObject()?.get("rabbitmq_version") as? JsonPrimitive
        return primitive?.takeIf { it.isString }?.content
    }

    private suspend fun get(path: String): ManagementReply = http.get(requests.absolute(ShovelPathAllowList.checked("GET", path)))

    private fun queuePath(vhost: String, queue: String) = "/api/queues/${ManagementRequests.encode(vhost)}/${ManagementRequests.encode(queue)}"

    private fun shovelPath(ref: ShovelRef) = "/api/parameters/shovel/${ManagementRequests.encode(ref.vhost)}/${ManagementRequests.encode(ref.name)}"

    private fun parse(body: String): JsonElement? = runCatching { Json.parseToJsonElement(body) }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject?.count(key: String): Long = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0

    private fun tagsOf(body: String): Set<String> = when (val tags = parse(body)?.asObject()?.get("tags")) {
        is JsonArray -> tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        is JsonPrimitive -> tags.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        else -> emptySet()
    }

    private fun typeOf(type: String?): DestinationQueueType = when (type) {
        "quorum" -> DestinationQueueType.QUORUM
        "stream" -> DestinationQueueType.STREAM
        "classic" -> DestinationQueueType.CLASSIC
        else -> DestinationQueueType.OTHER
    }

    private companion object {
        const val WHOAMI = "/api/whoami"
        const val SHOVELS = "/api/shovels"
        const val OVERVIEW = "/api/overview"
        const val DIRECT_REFUSAL_WORDS = "without a hostname or a username"
    }
}
