package dev.caerbannog.ui.publish

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.mqtt.HeaderNotice
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.ui.mqtt.PublishMqttDialog
import dev.caerbannog.ui.mqtt.PublishMqttEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10b step 2: the warning banner of the Publish MQTT dialog (tag `publishMqttHeaderNotice`). It reads `state.headerNoticeText`, sits under the headers list
 * (after the rows, before the confirmation and error banners), is a polite live region and is a different element from `publishMqttWarningBanner`.
 * The dialog is the real one over the real view model and a fake publisher.
 */
@OptIn(ExperimentalTestApi::class)
class PublishMqttHeaderNoticeUiTest {
    private val noticeText = "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property."

    private class Host(val vm: PublishMqttViewModel, val publisher: FakeMqttMessagePublisher, val scope: CoroutineScope)

    private fun host(withNotice: Boolean = true): Host {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val publisher = FakeMqttMessagePublisher()
        val vm = PublishMqttViewModel(
            publisher = publisher, connectionName = "uat-eu", scope = scope, initialTopic = "devices/d42/answer",
            initialHeaders = listOf(MqttHeader("correlation-id", "abc-123")),
            headerNotice = if (withNotice) HeaderNotice("correlation-id", noticeText) else null,
        )
        return Host(vm, publisher, scope)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(h: Host) = setContent {
        val state by h.vm.state.collectAsState()
        PublishMqttDialog(
            state = state,
            events = object : PublishMqttEvents {
                override fun setTopic(topic: String) = h.vm.setTopic(topic)
                override fun setQos(qos: MqttQos) = h.vm.setQos(qos)
                override fun setRetain(retain: Boolean) = h.vm.setRetain(retain)
                override fun setPayload(payload: String) = h.vm.setPayload(payload)
                override fun addHeader() = h.vm.addHeader()
                override fun setHeader(index: Int, key: String, value: String) = h.vm.setHeader(index, key, value)
                override fun removeHeader(index: Int) = h.vm.removeHeader(index)
                override fun publish() = h.vm.publish()
                override fun cancel() = h.vm.cancel()
                override fun clearFeedback() = h.vm.clearFeedback()
            },
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String): String {
        fun all(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { all(it) }
        return all(onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }

    @Test
    fun `the dialog opens with the topic, the pre-filled header and the banner, and publishes nothing by itself`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            assertEquals(noticeText, textOf("publishMqttHeaderNotice"))
            assertEquals(1, onAllNodesWithTag("publishMqttHeaderKey:0", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue(h.publisher.publishedMessages.isEmpty(), "nothing is published until Publish is pressed")
            onNodeWithTag("publishMqttPublishButton").assertIsEnabled()
        } finally { h.scope.cancel() }
    }

    @Test
    fun `the banner sits under the headers list and above the Publish button`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            val notice = onNodeWithTag("publishMqttHeaderNotice").getUnclippedBoundsInRoot()
            val lastRow = onNodeWithTag("publishMqttHeaderKey:0").getUnclippedBoundsInRoot()
            val publish = onNodeWithTag("publishMqttPublishButton").getUnclippedBoundsInRoot()
            assertTrue(notice.top >= lastRow.bottom, "banner top ${notice.top} is above the last header row bottom ${lastRow.bottom}")
            assertTrue(notice.bottom <= publish.top, "banner bottom ${notice.bottom} is below the Publish button top ${publish.top}")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `the banner is a polite live region`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            val config = onNodeWithTag("publishMqttHeaderNotice").fetchSemanticsNode().config
            assertEquals(LiveRegionMode.Polite, config.getOrNull(SemanticsProperties.LiveRegion), "live region")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `renaming the header removes the banner and renaming it back shows it again, Publish stays enabled`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            onNodeWithTag("publishMqttHeaderKey:0").performTextReplacement("trace-id")
            waitForIdle()
            assertTrue(!drawn("publishMqttHeaderNotice"))
            onNodeWithTag("publishMqttPublishButton").assertIsEnabled()
            onNodeWithTag("publishMqttHeaderKey:0").performTextReplacement("correlation-id")
            waitForIdle()
            assertTrue(drawn("publishMqttHeaderNotice"))
            onNodeWithTag("publishMqttPublishButton").assertIsEnabled()
        } finally { h.scope.cancel() }
    }

    @Test
    fun `removing the header removes the banner`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            onNodeWithTag("publishMqttRemoveHeader:0").performClick()
            waitForIdle()
            assertTrue(!drawn("publishMqttHeaderNotice"))
        } finally { h.scope.cancel() }
    }

    @Test
    fun `a dialog opened without a notice shows no banner`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host(withNotice = false).also { show(it) }
        try {
            waitForIdle()
            assertTrue(!drawn("publishMqttHeaderNotice"))
            assertEquals(1, onAllNodesWithTag("publishMqttHeaderKey:0", useUnmergedTree = true).fetchSemanticsNodes().size)
        } finally { h.scope.cancel() }
    }

    @Test
    fun `the broker-name banner is a different element and keeps its words`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            assertTrue(drawn("publishMqttWarningBanner"))
            assertEquals(
                "This message will be sent over MQTT to the broker on uat-eu. Connected subscribers with matching topics will receive it.",
                textOf("publishMqttWarningBanner"),
            )
        } finally { h.scope.cancel() }
    }

    @Test
    fun `the banner stays above the error banner when the broker refuses`() = runDesktopComposeUiTest(1280, 1100) {
        val h = host().also { show(it) }
        try {
            h.publisher.refuseNextWith = "broker said no"
            onNodeWithTag("publishMqttPublishButton").performClick()
            waitForIdle()
            val notice = onNodeWithTag("publishMqttHeaderNotice").getUnclippedBoundsInRoot()
            val error = onNodeWithTag("publishMqttError").getUnclippedBoundsInRoot()
            assertTrue(notice.bottom <= error.top, "the notice is above the error banner")
        } finally { h.scope.cancel() }
    }
}
