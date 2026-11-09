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
    fun addHeader()
    fun setHeader(index: Int, key: String, value: String)
    fun removeHeader(index: Int)
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
    override fun addHeader() = Unit
    override fun setHeader(index: Int, key: String, value: String) = Unit
    override fun removeHeader(index: Int) = Unit
    override fun publish() = Unit
    override fun cancel() = Unit
    override fun clearFeedback() = Unit
}
