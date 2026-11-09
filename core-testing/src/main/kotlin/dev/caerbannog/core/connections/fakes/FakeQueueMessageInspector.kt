package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.topology.InspectedQueueMessage
import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.QueueInspectionResult
import dev.caerbannog.core.topology.QueueMessageInspector
import java.util.Collections

/**
 * In-memory [QueueMessageInspector] that mirrors the contract of the real one (`ManagementQueueMessageInspector`):
 * every request is a requeue peek (the ack mode is a constant, never an argument), the count is clamped to
 * `1..MAX_INSPECT_COUNT`, an unknown queue is refused as not found and a queue the user may not read is refused for
 * lack of read access. Peeking never removes a message: [holds] always shows what the queue still has.
 */
class FakeQueueMessageInspector : QueueMessageInspector {

    /** What the broker was asked, after clamping: the count it would have received and the (constant) ack mode. */
    data class Request(val vhost: String, val queue: String, val count: Int, val ackMode: String = QueueInspectionConstants.ACK_REQUEUE_TRUE)

    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())
    private val queues = LinkedHashMap<Pair<String, String>, List<InspectedQueueMessage>>()
    private val unreadable = HashSet<Pair<String, String>>()

    /** The queue exists with [count] messages, each with a body, a header and a content type. */
    fun addQueue(queue: String, count: Int = 0, vhost: String = "/") {
        queues[vhost to queue] = (0 until count).map { i ->
            InspectedQueueMessage(
                index = i + 1, payload = "msg-$i".encodeToByteArray(), payloadBytes = "msg-$i".length.toLong(), payloadEncoding = "string",
                redelivered = false, exchange = "", routingKey = queue, messageCount = (count - i - 1).toLong(),
                properties = mapOf("content_type" to "text/plain"), headers = mapOf("trace" to "t-$i"),
            )
        }
    }

    /** The user has no read access to the queue: any peek is refused. */
    fun denyRead(queue: String, vhost: String = "/") {
        unreadable += vhost to queue
        queues.putIfAbsent(vhost to queue, emptyList())
    }

    /** How many messages the queue still holds: peeking must never change it. */
    fun holds(queue: String, vhost: String = "/"): Int = queues[vhost to queue]?.size ?: 0

    override suspend fun inspectQueueMessages(vhost: String, queue: String, count: Int): QueueInspectionResult {
        val clamped = count.coerceIn(1, QueueInspectionConstants.MAX_INSPECT_COUNT)
        requests += Request(vhost, queue, clamped)
        val key = vhost to queue
        if (key in unreadable) return QueueInspectionResult.Refused("User does not have read access to queue '$queue' on virtual host '$vhost'")
        val messages = queues[key] ?: return QueueInspectionResult.Refused("Queue '$queue' not found on virtual host '$vhost'")
        return QueueInspectionResult.Success(messages.take(clamped))
    }
}
