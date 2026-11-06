package dev.caerbannog.core.publish

/**
 * Target kind for a test message publish.
 */
enum class PublishTargetKind {
    STREAM,
    EXCHANGE,
    QUEUE,
}

/**
 * Destination target for publishing a test message.
 */
data class PublishTarget(
    val kind: PublishTargetKind,
    val name: String,
    val routingKey: String? = null,
)

/**
 * Request payload and headers for publishing a test message.
 */
data class PublishMessageRequest(
    val vhost: String,
    val target: PublishTarget,
    val payload: ByteArray,
    val properties: Map<String, String> = emptyMap(),
    val applicationProperties: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublishMessageRequest) return false
        if (vhost != other.vhost) return false
        if (target != other.target) return false
        if (!payload.contentEquals(other.payload)) return false
        if (properties != other.properties) return false
        if (applicationProperties != other.applicationProperties) return false
        return true
    }

    override fun hashCode(): Int {
        var result = vhost.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + properties.hashCode()
        result = 31 * result + applicationProperties.hashCode()
        return result
    }
}

/**
 * Result of attempting to publish a test message.
 */
sealed interface PublishOutcome {
    data class Success(val message: String) : PublishOutcome
    data class Refused(val reason: String) : PublishOutcome
    data class Failed(val error: String) : PublishOutcome
}

/**
 * Publishing service interface.
 */
interface MessagePublisher {
    suspend fun publish(request: PublishMessageRequest): PublishOutcome
}

/**
 * Fallback publisher when publishing is not configured or unavailable.
 */
object UnavailableMessagePublisher : MessagePublisher {
    override suspend fun publish(request: PublishMessageRequest): PublishOutcome =
        PublishOutcome.Failed("Publishing is not available")
}
