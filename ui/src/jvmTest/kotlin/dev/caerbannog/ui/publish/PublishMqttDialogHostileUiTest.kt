package dev.caerbannog.ui.publish

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10b step 4: the Publish MQTT dialog on very short windows, with 50 headers, with the notice switched on and off many times, with an error banner AND the
 * notice, and with a larger font. The buttons are pinned (round 2), so they must stay inside the window whatever the body holds.
 */
@OptIn(ExperimentalTestApi::class)
class PublishMqttDialogHostileUiTest {
    private val noticeText = "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property."

    private class Host(val vm: PublishMqttViewModel, val publisher: FakeMqttMessagePublisher, val scope: CoroutineScope)

    private fun host(headers: List<MqttHeader> = listOf(MqttHeader("correlation-id", "abc-123"))): Host {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val publisher = FakeMqttMessagePublisher()
        val vm = PublishMqttViewModel(
            publisher = publisher, connectionName = "uat-eu", scope = scope, initialTopic = "devices/d42/answer",
            initialHeaders = headers, headerNotice = HeaderNotice("correlation-id", noticeText),
        )
        return Host(vm, publisher, scope)
    }

    private fun ComposeUiTest.show(h: Host, fontScale: Float = 1f) = setContent {
        val state by h.vm.state.collectAsState()
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
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
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertInside(name: String, r: DpRect, width: Int, height: Int) = assertTrue(
        r.left.value >= 0f && r.top.value >= 0f && r.right.value <= width && r.bottom.value <= height,
        "$name is not fully inside the ${width}x$height window: left=${r.left} top=${r.top} right=${r.right} bottom=${r.bottom}",
    )

    private fun buttonsInside(width: Int, height: Int, fontScale: Float = 1f, headers: List<MqttHeader> = listOf(MqttHeader("correlation-id", "abc-123"))) =
        runDesktopComposeUiTest(width, height) {
            val h = host(headers).also { show(it, fontScale) }
            try {
                waitForIdle()
                assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), width, height)
                assertInside("publishMqttCancelButton", bounds("publishMqttCancelButton"), width, height)
                assertTrue(drawn("publishMqttBodyScrollbar"), "a body that does not fit shows its scrollbar")
            } finally { h.scope.cancel() }
        }

    @Test
    fun `at 300x300 the buttons are inside the window and the body scrolls`() = buttonsInside(300, 300)

    @Test
    fun `at 200x200 the buttons are inside the window and the body scrolls`() = buttonsInside(200, 200)

    @Test
    fun `with 50 headers and the notice the buttons are inside the window at 1280x800`() =
        buttonsInside(1280, 800, headers = listOf(MqttHeader("correlation-id", "x")) + List(49) { MqttHeader("h$it", "v$it") })

    @Test
    fun `at font scale 1_3 the buttons are inside the window at 1280x800 and the notice is drawn`() = runDesktopComposeUiTest(1280, 800) {
        val h = host().also { show(it, 1.3f) }
        try {
            waitForIdle()
            assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), 1280, 800)
            assertInside("publishMqttCancelButton", bounds("publishMqttCancelButton"), 1280, 800)
            assertTrue(drawn("publishMqttHeaderNotice"))
        } finally { h.scope.cancel() }
    }

    @Test
    fun `at font scale 1_5 the buttons are inside the window at 1000x600`() = buttonsInside(1000, 600, fontScale = 1.5f)

    @Test
    fun `the notice switched on and off 30 times settles, with the buttons where they were`() = runDesktopComposeUiTest(1000, 600) {
        val h = host().also { show(it) }
        try {
            waitForIdle()
            val publish = bounds("publishMqttPublishButton")
            repeat(30) {
                onNodeWithTag("publishMqttHeaderKey:0").performTextReplacement("trace-id")
                waitForIdle()
                assertTrue(!drawn("publishMqttHeaderNotice"), "round $it: gone")
                onNodeWithTag("publishMqttHeaderKey:0").performTextReplacement("correlation-id")
                waitForIdle()
                assertTrue(drawn("publishMqttHeaderNotice"), "round $it: back")
            }
            assertEquals(publish, bounds("publishMqttPublishButton"))
            assertInside("publishMqttHeaderNotice", bounds("publishMqttHeaderNotice"), 1000, 600)
        } finally { h.scope.cancel() }
    }

    @Test
    fun `an error banner and the notice together at 1280x800 keep the buttons inside and the notice reachable`() = runDesktopComposeUiTest(1280, 800) {
        val h = host().also { show(it) }
        try {
            h.publisher.refuseNextWith = "The broker refused this topic: ".padEnd(400, 'x')
            onNodeWithTag("publishMqttPublishButton").performClick()
            waitForIdle()
            assertTrue(drawn("publishMqttError") && drawn("publishMqttHeaderNotice"))
            assertInside("publishMqttPublishButton", bounds("publishMqttPublishButton"), 1280, 800)
            assertInside("publishMqttCancelButton", bounds("publishMqttCancelButton"), 1280, 800)
        } finally { h.scope.cancel() }
    }

    @Test
    @Disabled("Finding F1 of M9-10b step 4 round 2: the notice's auto-scroll hides the title, the broker-name warning and (at 1000x600) the topic at first sight. Enable when the architect decides.")
    fun `at opening the title, the broker warning and the topic field are on screen`() {
        for ((w, h) in listOf(1280 to 800, 1000 to 600)) runDesktopComposeUiTest(w, h) {
            val host = host().also { show(it) }
            try {
                waitForIdle()
                for (tag in listOf("publishMqttDialogTitle", "publishMqttWarningBanner", "publishMqttTopicInput")) {
                    val b = bounds(tag)
                    assertTrue(b.top.value >= 0f && b.bottom.value <= h, "${w}x$h: $tag is scrolled out of view at opening: top=${b.top} bottom=${b.bottom}")
                }
            } finally { host.scope.cancel() }
        }
    }
}
