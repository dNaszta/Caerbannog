package dev.caerbannog.core.mqtt

import dev.caerbannog.core.publish.PublishOutcome

/**
 * Publishes test messages to an MQTT broker.
 */
interface MqttMessagePublisher {
    /**
     * Publishes [request] to the MQTT broker.
     * Returns [PublishOutcome.Success] on successful acknowledgement,
     * [PublishOutcome.Refused] on invalid topic or broker permission refusal,
     * or [PublishOutcome.Failed] on network / broker failure.
     */
    suspend fun publish(request: PublishMqttRequest): PublishOutcome
}

/**
 * Fallback publisher when MQTT publishing is not configured or unavailable.
 */
object UnavailableMqttMessagePublisher : MqttMessagePublisher {
    override suspend fun publish(request: PublishMqttRequest): PublishOutcome =
        PublishOutcome.Failed("MQTT publishing is not available")
}
