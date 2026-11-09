package dev.caerbannog.desktopapp

import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.ui.mqtt.PublishMqttEvents

/**
 * Adapts [PublishMqttViewModel] actions to the [PublishMqttEvents] UI interface.
 * [onClose] is invoked when the dialog is dismissed/cancelled.
 */
class PublishMqttEventsAdapter(
    private val vm: PublishMqttViewModel,
    private val onClose: () -> Unit = {},
    private val onPublished: () -> Unit = {},
) : PublishMqttEvents {
    override fun setTopic(topic: String) = vm.setTopic(topic)
    override fun setQos(qos: MqttQos) = vm.setQos(qos)
    override fun setRetain(retain: Boolean) = vm.setRetain(retain)
    override fun setPayload(payload: String) = vm.setPayload(payload)
    override fun addHeader() = vm.addHeader()
    override fun setHeader(index: Int, key: String, value: String) = vm.setHeader(index, key, value)
    override fun removeHeader(index: Int) = vm.removeHeader(index)
    override fun publish() = vm.publish { onPublished() }
    override fun cancel() {
        vm.cancel()
        onClose()
    }
    override fun clearFeedback() = vm.clearFeedback()
}
