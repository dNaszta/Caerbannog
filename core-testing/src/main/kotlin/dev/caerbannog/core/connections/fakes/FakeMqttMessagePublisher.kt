package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.mqtt.MqttMessagePublisher
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import java.util.Collections

/**
 * In-memory test double for [MqttMessagePublisher].
 * Records all published requests and supports simulated refusals/failures.
 */
class FakeMqttMessagePublisher : MqttMessagePublisher {
    val publishedMessages: MutableList<PublishMqttRequest> = Collections.synchronizedList(ArrayList())

    @Volatile
    var refuseNextWith: String? = null

    @Volatile
    var failNextWith: String? = null

    override suspend fun publish(request: PublishMqttRequest): PublishOutcome {
        if (request.topic.isBlank()) {
            return PublishOutcome.Refused("Topic cannot be blank")
        }
        if (request.topic.contains("+") || request.topic.contains("#")) {
            return PublishOutcome.Refused("Topic cannot contain wildcards '+' or '#'")
        }
        refuseNextWith?.let { reason ->
            refuseNextWith = null
            return PublishOutcome.Refused(reason)
        }
        failNextWith?.let { err ->
            failNextWith = null
            return PublishOutcome.Failed(err)
        }
        publishedMessages.add(request)
        return PublishOutcome.Success(
            "Message published to '${request.topic}' (QoS ${request.qos.value}${if (request.retain) ", retained" else ""})",
        )
    }

    fun findPublished(topic: String): List<PublishMqttRequest> =
        publishedMessages.filter { it.topic == topic }

    fun clear() {
        publishedMessages.clear()
        refuseNextWith = null
        failNextWith = null
    }
}
