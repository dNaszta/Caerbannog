package dev.caerbannog.ui.mqtt

import dev.caerbannog.core.mqtt.MqttQos

/**
 * UI user actions emitted by the Publish MQTT Message dialog.
 */
interface PublishMqttEvents {
    fun setTopic(topic: String)
    fun setQos(qos: MqttQos)
    fun setRetain(retain: Boolean)
    fun setPayload(payload: String)
    fun publish()
    fun cancel()
    fun clearFeedback()
}

/**
 * No-op implementation for previews and tests.
 */
object EmptyPublishMqttEvents : PublishMqttEvents {
    override fun setTopic(topic: String) = Unit
    override fun setQos(qos: MqttQos) = Unit
    override fun setRetain(retain: Boolean) = Unit
    override fun setPayload(payload: String) = Unit
    override fun publish() = Unit
    override fun cancel() = Unit
    override fun clearFeedback() = Unit
}
