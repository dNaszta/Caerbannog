package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.PublishTargetKind
import java.util.Collections

/**
 * In-memory test double for [MessagePublisher].
 * Records all published requests and supports simulated refusals/failures.
 */
class FakeMessagePublisher : MessagePublisher {
    val publishedMessages: MutableList<PublishMessageRequest> = Collections.synchronizedList(ArrayList())

    @Volatile
    var refuseNextWith: String? = null

    @Volatile
    var failNextWith: String? = null

    override suspend fun publish(request: PublishMessageRequest): PublishOutcome {
        refuseNextWith?.let { reason ->
            refuseNextWith = null
            return PublishOutcome.Refused(reason)
        }
        failNextWith?.let { err ->
            failNextWith = null
            return PublishOutcome.Failed(err)
        }
        publishedMessages.add(request)
        val targetDesc = when (request.target.kind) {
            PublishTargetKind.STREAM -> "stream '${request.target.name}'"
            PublishTargetKind.EXCHANGE -> "exchange '${request.target.name}'"
            PublishTargetKind.QUEUE -> "queue '${request.target.name}'"
        }
        return PublishOutcome.Success("Message published to $targetDesc")
    }

    fun findPublished(kind: PublishTargetKind, name: String): List<PublishMessageRequest> =
        publishedMessages.filter { it.target.kind == kind && it.target.name == name }

    fun clear() {
        publishedMessages.clear()
        refuseNextWith = null
        failNextWith = null
    }
}
