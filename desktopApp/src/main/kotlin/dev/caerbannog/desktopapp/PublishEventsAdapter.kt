package dev.caerbannog.desktopapp

import dev.caerbannog.core.publish.PublishMessageViewModel
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.ui.publish.PublishEvents

/**
 * Adapts [PublishMessageViewModel] actions to the [PublishEvents] UI interface.
 * [onClose] is invoked when the dialog is dismissed/cancelled.
 */
class PublishEventsAdapter(
    private val vm: PublishMessageViewModel,
    private val onClose: () -> Unit = {},
) : PublishEvents {
    override fun setTargetKind(kind: PublishTargetKind) = vm.setTargetKind(kind)
    override fun setTargetName(name: String) = vm.setTargetName(name)
    override fun setRoutingKey(key: String) = vm.setRoutingKey(key)
    override fun setMessageId(id: String) = vm.setMessageId(id)
    override fun setContentType(type: String) = vm.setContentType(type)
    override fun setBody(body: String) = vm.setBody(body)
    override fun addApplicationProperty(key: String, value: String) = vm.addApplicationProperty(key, value)
    override fun removeApplicationProperty(key: String) = vm.removeApplicationProperty(key)
    override fun publish() = vm.publish()
    override fun cancel() {
        vm.clearFeedback()
        onClose()
    }
    override fun clearFeedback() = vm.clearFeedback()
}
