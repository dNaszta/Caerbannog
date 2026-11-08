package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.QueueInspectionResult
import dev.caerbannog.core.topology.QueueMessageInspector
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Non-destructive queue message inspector over the RabbitMQ Management API (`POST /api/queues/{vhost}/{name}/get`).
 *
 * Always requests `ackmode = "ack_requeue_true"` so that inspected messages are returned to the queue and remain
 * unacknowledged by the consumer.
 */
internal class ManagementQueueMessageInspector(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : QueueMessageInspector {

    override suspend fun inspectQueueMessages(
        vhost: String,
        queue: String,
        count: Int,
    ): QueueInspectionResult {
        val clampedCount = count.coerceIn(1, QueueInspectionConstants.MAX_INSPECT_COUNT)
        val requestBody = buildJsonObject {
            put("count", clampedCount)
            put("ackmode", QueueInspectionConstants.ACK_REQUEUE_TRUE)
            put("encoding", "auto")
            put("truncate", 65536)
        }.toString()

        val url = requests.getQueueMessages(vhost, queue)
        return when (val reply = http.post(url, requestBody)) {
            is ManagementReply.Response -> when (reply.status) {
                200 -> when (val parsed = ManagementJson.parseInspectedQueueMessages(reply.body)) {
                    is CatalogResult.Success -> QueueInspectionResult.Success(parsed.value)
                    is CatalogResult.Failure -> QueueInspectionResult.Failed("Unexpected response format from management API")
                }
                401, 403 -> QueueInspectionResult.Refused("User does not have read access to queue '$queue' on virtual host '$vhost'")
                404 -> QueueInspectionResult.Refused("Queue '$queue' not found on virtual host '$vhost'")
                else -> QueueInspectionResult.Failed("Broker returned HTTP ${reply.status}")
            }
            ManagementReply.Unreachable -> QueueInspectionResult.Failed("Broker management API unreachable")
            ManagementReply.CertificateRejected -> QueueInspectionResult.Failed("Broker certificate rejected")
            ManagementReply.NoTlsAnswer -> QueueInspectionResult.Failed("Broker refused TLS connection")
        }
    }
}
