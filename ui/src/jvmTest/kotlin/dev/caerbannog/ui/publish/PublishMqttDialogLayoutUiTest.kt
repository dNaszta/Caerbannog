package dev.caerbannog.ui.publish

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpRect
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
 * M9-10b round 2: the Publish MQTT dialog opened by the test message (pre-filled `correlation-id` header, the notice, the default payload) must keep
 * Cancel and Publish and the notice on screen without any scrolling, at 1280x800 and at 1000x600; the body scrolls and shows a scrollbar only on overflow
 * (`publishMqttBody`, `publishMqttBodyScrollbar`); the footer does not move when the body scrolls; error and confirmation banners never push the buttons out.
 * Real dialog, real view model, fake publisher. Bounds are asserted through `getUnclippedBoundsInRoot` (density 1, so dp equals px).
 */
@OptIn(ExperimentalTestApi::class)
class PublishMqttDialogLayoutUiTest {
    private val noticeText = "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property."

    private class Host(val vm: PublishMqttViewModel, val publisher: FakeMqttMessagePublisher, val scope: CoroutineScope)

    private fun host(withHeader: Boolean = true): Host {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val publisher = FakeMqttMessagePublisher()
        val vm = PublishMqttViewModel(
            publisher = publisher, connectionName = "uat-eu", scope = scope, initialTopic = "devices/d42/answer",
            initialHeaders = if (withHeader) listOf(MqttHeader("correlation-id", "abc-123")) else emptyList(),
            headerNotice = if (withHeader) HeaderNotice("correlation-id", noticeText) else null,
        )
        return Host(vm, publisher, scope)
    }

    private fun ComposeUiTest.show(h: Host) = setContent {
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

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertInside(name: String, r: DpRect, width: Int, height: Int) {
        assertTrue(
            r.left.value >= 0f && r.top.value >= 0f && r.right.value <= width && r.bottom.value <= height,
            "$name is not fully inside the ${width}x$height window: left=${r.left} top=${r.top} right=${r.right} bottom=${r.bottom}",
        )
    }

    private fun footerAndNoticeVisible(width: Int, height: Int) = runDesktopComposeUiTest(width, height) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            for (tag in listOf("publishMqttPublishButton", "publishMqttCancelButton")) {
                assertInside(tag, bounds(tag), width, height)
                onNodeWithTag(tag).assertIsDisplayed()
            }
            onNodeWithTag("publishMqttHeaderNotice").assertIsDisplayed()
            val notice = bounds("publishMqttHeaderNotice")
            assertInside("publishMqttHeaderNotice", notice, width, height)
            val buttonsTop = minOf(bounds("publishMqttPublishButton").top.value, bounds("publishMqttCancelButton").top.value)
            assertTrue(notice.bottom.value <= buttonsTop, "the notice bottom ${notice.bottom} is not above the buttons top ${buttonsTop}dp")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `at 1280x800 the buttons and the notice are on screen without scrolling`() = footerAndNoticeVisible(1280, 800)

    @Test
    fun `at 1000x600 the buttons and the notice are on screen without scrolling`() = footerAndNoticeVisible(1000, 600)

    @Test
    fun `the body has a scrollbar when it overflows`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            assertTrue(drawn("publishMqttBody"), "the scrolling body is tagged publishMqttBody")
            assertTrue(drawn("publishMqttBodyScrollbar"), "an overflowing body shows publishMqttBodyScrollbar")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `a tall window without extra header and notice has no scrollbar`() = runDesktopComposeUiTest(1280, 1600) {
        val h = host(withHeader = false).also { show(it) }
        try {
            waitForIdle()
            assertTrue(drawn("publishMqttBody"), "the scrolling body is tagged publishMqttBody")
            assertTrue(!drawn("publishMqttBodyScrollbar"), "no overflow, so no scrollbar")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `scrolling the body does not move the buttons`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            val publishBefore = bounds("publishMqttPublishButton")
            val cancelBefore = bounds("publishMqttCancelButton")
            onNodeWithTag("publishMqttBody").performTouchInput { swipeUp() }
            waitForIdle()
            assertEquals(publishBefore, bounds("publishMqttPublishButton"), "Publish moved with the body")
            assertEquals(cancelBefore, bounds("publishMqttCancelButton"), "Cancel moved with the body")
            assertInside("publishMqttPublishButton", publishBefore, 1000, 600)
        } finally { h.scope.cancel() }
    }

    @Test
    fun `an error banner keeps the buttons inside the window`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            h.publisher.refuseNextWith = "broker said no, and here is a long explanation that may wrap onto several lines of the banner in a narrow window"
            h.vm.publish()
            waitForIdle()
            assertTrue(drawn("publishMqttError"), "the error banner is shown")
            assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), 1000, 600)
            assertInside("publishMqttCancelButton", bounds("publishMqttCancelButton"), 1000, 600)
        } finally { h.scope.cancel() }
    }

    @Test
    fun `a confirmation banner keeps the buttons inside the window`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            h.vm.publish()
            waitForIdle()
            assertTrue(drawn("publishMqttConfirmation"), "the confirmation banner is shown")
            assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), 1000, 600)
            assertInside("publishMqttCancelButton", bounds("publishMqttCancelButton"), 1000, 600)
        } finally { h.scope.cancel() }
    }

    // ---- round 3: the notice and the feedback banners are pinned in the footer, nothing scrolls on its own ----

    private fun noticePinned(width: Int, height: Int) = runDesktopComposeUiTest(width, height) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            // Scroll the body to its top first: a notice that lives in the body would leave the window here, a pinned one does not.
            onNodeWithTag("publishMqttBody").performTouchInput { swipeDown() }
            waitForIdle()
            onNodeWithTag("publishMqttHeaderNotice").assertIsDisplayed()
            val before = bounds("publishMqttHeaderNotice")
            assertInside("publishMqttHeaderNotice", before, width, height)
            val buttonsTop = minOf(bounds("publishMqttPublishButton").top.value, bounds("publishMqttCancelButton").top.value)
            assertTrue(before.bottom.value <= buttonsTop, "the notice bottom ${before.bottom} is below the buttons top ${buttonsTop}dp")
            onNodeWithTag("publishMqttBody").performTouchInput { swipeUp() }
            waitForIdle()
            assertEquals(before, bounds("publishMqttHeaderNotice"), "the notice moved with the body: it is not pinned")
        } finally { h.scope.cancel() }
    }

    @Test
    fun `at 1280x800 the notice is pinned above the buttons and does not move when the body scrolls`() = noticePinned(1280, 800)

    @Test
    fun `at 1000x600 the notice is pinned above the buttons and does not move when the body scrolls`() = noticePinned(1000, 600)

    @Test
    fun `with the notice and a 400-character error at 1000x600 the body keeps 120dp and title, warning and topic are on screen`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            h.publisher.refuseNextWith = "The broker refused this topic: ".padEnd(400, 'x')
            h.vm.publish()
            waitForIdle()
            assertTrue(drawn("publishMqttError") && drawn("publishMqttHeaderNotice"))
            val body = bounds("publishMqttBody")
            assertTrue(body.bottom.value - body.top.value >= 120f, "the body is only ${body.bottom - body.top} high")
            for (tag in listOf("publishMqttDialogTitle", "publishMqttWarningBanner", "publishMqttTopicInput", "publishMqttHeaderNotice", "publishMqttError")) {
                assertInside(tag, bounds(tag), 1000, 600)
            }
            assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), 1000, 600)
        } finally { h.scope.cancel() }
    }

    private fun confirmationInside(width: Int, height: Int) = runDesktopComposeUiTest(width, height) {
        val h = host().also { show(it) }
        try {
            h.vm.publish()
            waitForIdle()
            assertTrue(drawn("publishMqttConfirmation"), "the confirmation is shown")
            onNodeWithTag("publishMqttConfirmation").assertIsDisplayed()
            assertInside("publishMqttConfirmation", bounds("publishMqttConfirmation"), width, height)
        } finally { h.scope.cancel() }
    }

    @Test
    fun `a confirmation after Publish is inside the window with no scroll at 1280x800`() = confirmationInside(1280, 800)

    @Test
    fun `a confirmation after Publish is inside the window with no scroll at 1000x600`() = confirmationInside(1000, 600)
}
