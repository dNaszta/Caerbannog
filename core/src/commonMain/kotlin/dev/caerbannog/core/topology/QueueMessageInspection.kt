package dev.caerbannog.core.topology

import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadInspection
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.browser.StreamMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mode used when fetching queue messages via the RabbitMQ Management API (`POST /api/queues/{vhost}/{name}/get`).
 *
 * Non-destructive inspection ALWAYS uses [ACK_REQUEUE_TRUE] (`ackmode = "ack_requeue_true"`), so messages are
 * returned to the queue and remain unacknowledged by the consumer.
 */
object QueueInspectionConstants {
    const val ACK_REQUEUE_TRUE: String = "ack_requeue_true"
    const val DEFAULT_INSPECT_COUNT: Int = 10
    const val MAX_INSPECT_COUNT: Int = 50
    const val NOT_ACKNOWLEDGED_BADGE: String = "Not acknowledged (requeued)"
    const val REQUEUE_MODE_NOTICE: String =
        "Fetched via Management API in requeue mode (ack_requeue_true). Messages are not acknowledged and remain in the queue."
}

/**
 * A single message inspected from a classic or quorum queue via the RabbitMQ Management API in requeue mode.
 *
 * @property index 1-based position within the inspected batch.
 * @property payload Raw message bytes (decoded from UTF-8 string or Base64 according to `payload_encoding`).
 * @property payloadBytes Byte size reported by the broker (`payload_bytes`).
 * @property payloadEncoding Encoding reported by the broker (`string` or `base64`).
 * @property redelivered True if the message was marked redelivered by the broker.
 * @property exchange Exchange the message was published to (`""` for the default exchange).
 * @property routingKey Routing key of the message.
 * @property messageCount Remaining message count in the queue at the moment of inspection (`message_count`).
 * @property properties AMQP message properties (`message_id`, `correlation_id`, `content_type`, `timestamp`, `delivery_mode`, etc.).
 * @property headers AMQP message headers (`properties.headers`).
 * @property acknowledged Always `false` for non-destructive inspection (`ack_requeue_true`).
 */
data class InspectedQueueMessage(
    val index: Int,
    val payload: ByteArray,
    val payloadBytes: Long,
    val payloadEncoding: String,
    val redelivered: Boolean,
    val exchange: String,
    val routingKey: String,
    val messageCount: Long? = null,
    val properties: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val acknowledged: Boolean = false,
    val ackMode: String = QueueInspectionConstants.ACK_REQUEUE_TRUE,
) {
    /** Status label clearly marking the message as not acknowledged inside the interface. */
    val acknowledgementStatusText: String
        get() = if (!acknowledged) QueueInspectionConstants.NOT_ACKNOWLEDGED_BADGE else "Acknowledged"

    /** Human-readable exchange name (`(default exchange)` when `exchange` is blank). */
    val displayExchange: String
        get() = exchange.ifBlank { "(default exchange)" }

    /** Human-readable routing key (`(empty)` when `routingKey` is blank). */
    val displayRoutingKey: String
        get() = routingKey.ifBlank { "(empty)" }

    /** Human-readable delivery mode (`2 (Persistent)`, `1 (Transient)`, or raw value). */
    val formattedDeliveryMode: String?
        get() = when (val mode = properties["delivery_mode"]) {
            "2" -> "2 (Persistent)"
            "1" -> "1 (Transient)"
            else -> mode
        }

    /** Formats a single AMQP property value with human-readable enrichment (e.g. `delivery_mode`). */
    fun formatPropertyValue(key: String, rawValue: String): String = when (key) {
        "delivery_mode" -> formattedDeliveryMode ?: rawValue
        else -> rawValue
    }

    /** Formats routing metadata, AMQP properties, and message headers for one-click copying. */
    fun formatMetadataForCopy(): String = buildString {
        appendLine("Exchange: $displayExchange")
        appendLine("Routing key: $displayRoutingKey")
        appendLine("Redelivered: ${if (redelivered) "yes" else "no"}")
        appendLine("Payload size: $payloadBytes B ($payloadEncoding)")
        messageCount?.let { appendLine("Remaining in queue: $it") }
        if (properties.isNotEmpty()) {
            appendLine()
            appendLine("Properties:")
            for ((k, v) in properties) {
                appendLine("  $k: ${formatPropertyValue(k, v)}")
            }
        }
        if (headers.isNotEmpty()) {
            appendLine()
            appendLine("Headers:")
            for ((k, v) in headers) {
                appendLine("  $k: $v")
            }
        }
    }.trimEnd()

    /** Converts this queue message to a [StreamMessage] so [PayloadFormatter] can inspect Text/JSON/Hex views. */
    fun toStreamMessage(): StreamMessage = StreamMessage(
        offset = index.toLong(),
        timestamp = properties["timestamp"]?.toLongOrNull() ?: 0L,
        payload = payload,
        properties = properties,
        applicationProperties = headers,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InspectedQueueMessage) return false
        if (index != other.index) return false
        if (!payload.contentEquals(other.payload)) return false
        if (payloadBytes != other.payloadBytes) return false
        if (payloadEncoding != other.payloadEncoding) return false
        if (redelivered != other.redelivered) return false
        if (exchange != other.exchange) return false
        if (routingKey != other.routingKey) return false
        if (messageCount != other.messageCount) return false
        if (properties != other.properties) return false
        if (headers != other.headers) return false
        if (acknowledged != other.acknowledged) return false
        if (ackMode != other.ackMode) return false
        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + payloadBytes.hashCode()
        result = 31 * result + payloadEncoding.hashCode()
        result = 31 * result + redelivered.hashCode()
        result = 31 * result + exchange.hashCode()
        result = 31 * result + routingKey.hashCode()
        result = 31 * result + (messageCount?.hashCode() ?: 0)
        result = 31 * result + properties.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + acknowledged.hashCode()
        result = 31 * result + ackMode.hashCode()
        return result
    }
}

/**
 * Outcome of a non-destructive queue message inspection call.
 */
sealed interface QueueInspectionResult {
    data class Success(val messages: List<InspectedQueueMessage>) : QueueInspectionResult
    data class Refused(val reason: String) : QueueInspectionResult
    data class Failed(val reason: String) : QueueInspectionResult
}

/**
 * Gateway interface for non-destructive queue message inspection via the RabbitMQ Management API
 * (`POST /api/queues/{vhost}/{name}/get` with `ackmode = "ack_requeue_true"`).
 */
interface QueueMessageInspector {
    suspend fun inspectQueueMessages(
        vhost: String,
        queue: String,
        count: Int = QueueInspectionConstants.DEFAULT_INSPECT_COUNT,
    ): QueueInspectionResult
}

object UnavailableQueueMessageInspector : QueueMessageInspector {
    override suspend fun inspectQueueMessages(vhost: String, queue: String, count: Int): QueueInspectionResult =
        QueueInspectionResult.Failed("Queue message inspector is not available on this connection")
}

/**
 * UI state for non-destructive queue message inspection inside the Topology Queue Detail panel.
 */
sealed interface QueueInspectionState {
    data class Idle(
        val vhost: String = "/",
        val queue: String = "",
        val count: Int = QueueInspectionConstants.DEFAULT_INSPECT_COUNT,
    ) : QueueInspectionState

    data class Loading(
        val vhost: String,
        val queue: String,
        val count: Int,
    ) : QueueInspectionState

    data class Empty(
        val vhost: String,
        val queue: String,
        val count: Int,
        val notice: String = QueueInspectionConstants.REQUEUE_MODE_NOTICE,
    ) : QueueInspectionState

    data class Loaded(
        val vhost: String,
        val queue: String,
        val count: Int,
        val messages: List<InspectedQueueMessage>,
        val selectedIndex: Int = 1,
        val payloadViewKind: PayloadViewKind? = null,
        val notice: String = QueueInspectionConstants.REQUEUE_MODE_NOTICE,
        val statusBadgeText: String = QueueInspectionConstants.NOT_ACKNOWLEDGED_BADGE,
    ) : QueueInspectionState {
        val selectedMessage: InspectedQueueMessage?
            get() = messages.firstOrNull { it.index == selectedIndex } ?: messages.firstOrNull()

        fun inspectSelected(formatter: PayloadFormatter = PayloadFormatter()): PayloadInspection? =
            selectedMessage?.let { msg -> formatter.inspect(msg.toStreamMessage(), requestedKind = payloadViewKind) }
    }

    data class Error(
        val vhost: String,
        val queue: String,
        val count: Int,
        val reason: String,
    ) : QueueInspectionState
}

/**
 * State holder for non-destructive queue message inspection in requeue mode.
 */
class QueueMessageInspectionViewModel(
    private val inspector: QueueMessageInspector,
    private val vhost: String,
    private val queue: String,
    private val scope: CoroutineScope,
    initialCount: Int = QueueInspectionConstants.DEFAULT_INSPECT_COUNT,
) {
    private var requestedCount: Int = initialCount.coerceIn(1, QueueInspectionConstants.MAX_INSPECT_COUNT)

    private val _state = MutableStateFlow<QueueInspectionState>(
        QueueInspectionState.Idle(vhost = vhost, queue = queue, count = requestedCount),
    )
    val state: StateFlow<QueueInspectionState> = _state.asStateFlow()

    fun setCount(count: Int) {
        scope.launch {
            requestedCount = count.coerceIn(1, QueueInspectionConstants.MAX_INSPECT_COUNT)
            val current = _state.value
            _state.value = when (current) {
                is QueueInspectionState.Idle -> current.copy(count = requestedCount)
                is QueueInspectionState.Loading -> current.copy(count = requestedCount)
                is QueueInspectionState.Empty -> current.copy(count = requestedCount)
                is QueueInspectionState.Loaded -> current.copy(count = requestedCount)
                is QueueInspectionState.Error -> current.copy(count = requestedCount)
            }
        }
    }

    fun inspectMessages() {
        scope.launch {
            val count = requestedCount
            _state.value = QueueInspectionState.Loading(vhost = vhost, queue = queue, count = count)
            when (val result = inspector.inspectQueueMessages(vhost = vhost, queue = queue, count = count)) {
                is QueueInspectionResult.Success -> {
                    if (result.messages.isEmpty()) {
                        _state.value = QueueInspectionState.Empty(vhost = vhost, queue = queue, count = count)
                    } else {
                        _state.value = QueueInspectionState.Loaded(
                            vhost = vhost,
                            queue = queue,
                            count = count,
                            messages = result.messages,
                            selectedIndex = result.messages.first().index,
                        )
                    }
                }
                is QueueInspectionResult.Refused -> {
                    _state.value = QueueInspectionState.Error(
                        vhost = vhost,
                        queue = queue,
                        count = count,
                        reason = result.reason,
                    )
                }
                is QueueInspectionResult.Failed -> {
                    _state.value = QueueInspectionState.Error(
                        vhost = vhost,
                        queue = queue,
                        count = count,
                        reason = result.reason,
                    )
                }
            }
        }
    }

    fun selectMessage(index: Int) {
        scope.launch {
            val current = _state.value
            if (current is QueueInspectionState.Loaded && current.messages.any { it.index == index }) {
                _state.value = current.copy(selectedIndex = index, payloadViewKind = null)
            }
        }
    }

    fun setPayloadViewKind(kind: PayloadViewKind?) {
        scope.launch {
            val current = _state.value
            if (current is QueueInspectionState.Loaded) {
                _state.value = current.copy(payloadViewKind = kind)
            }
        }
    }

    fun clear() {
        scope.launch {
            _state.value = QueueInspectionState.Idle(vhost = vhost, queue = queue, count = requestedCount)
        }
    }
}
