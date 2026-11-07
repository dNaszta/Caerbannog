package dev.caerbannog.ui.publish

import dev.caerbannog.core.publish.PublishTargetKind

/**
 * UI user actions emitted by the Publish Test Message dialog.
 */
interface PublishEvents {
    fun setTargetKind(kind: PublishTargetKind)
    fun setTargetName(name: String)
    fun setRoutingKey(key: String)
    fun setMessageId(id: String)
    fun setContentType(type: String)
    fun setBody(body: String)
    fun addApplicationProperty(key: String, value: String)
    fun removeApplicationProperty(key: String)
    fun publish()
    fun cancel()
    fun clearFeedback()
}

/**
 * No-op implementation for previews and tests.
 */
object EmptyPublishEvents : PublishEvents {
    override fun setTargetKind(kind: PublishTargetKind) = Unit
    override fun setTargetName(name: String) = Unit
    override fun setRoutingKey(key: String) = Unit
    override fun setMessageId(id: String) = Unit
    override fun setContentType(type: String) = Unit
    override fun setBody(body: String) = Unit
    override fun addApplicationProperty(key: String, value: String) = Unit
    override fun removeApplicationProperty(key: String) = Unit
    override fun publish() = Unit
    override fun cancel() = Unit
    override fun clearFeedback() = Unit
}
