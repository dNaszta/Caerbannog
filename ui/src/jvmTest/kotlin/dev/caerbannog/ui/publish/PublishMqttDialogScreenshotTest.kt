package dev.caerbannog.ui.publish

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-10b step 4 (opt-in, `:ui:screenshotTest`): the Publish MQTT dialog as the test message opens it, with the pre-filled header and the notice, at the two
 * window sizes of the layout test. Written to `build/screenshots/publish-mqtt-<size>.png` for a by-eye check of what the user sees first; the geometry (where
 * the title and the topic field sit after the dialog opened) goes to `publish-mqtt-<size>.txt`. No assertion on pixels.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("screenshot")
class PublishMqttDialogScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots").also { it.mkdirs() }
    private val noticeText = "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property."

    private fun ComposeUiTest.shoot(name: String, width: Int, height: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = PublishMqttViewModel(
            FakeMqttMessagePublisher(), "uat-eu", scope, initialTopic = "devices/d42/answer",
            initialHeaders = listOf(MqttHeader("correlation-id", "0b0f7f0e-8a53-4a61-9d3c-2a6f1c1b7e55")),
            headerNotice = HeaderNotice("correlation-id", noticeText),
        )
        setContent {
            val state by vm.state.collectAsState()
            PublishMqttDialog(state = state, events = object : PublishMqttEvents {
                override fun setTopic(topic: String) = vm.setTopic(topic)
                override fun setQos(qos: MqttQos) = vm.setQos(qos)
                override fun setRetain(retain: Boolean) = vm.setRetain(retain)
                override fun setPayload(payload: String) = vm.setPayload(payload)
                override fun addHeader() = vm.addHeader()
                override fun setHeader(index: Int, key: String, value: String) = vm.setHeader(index, key, value)
                override fun removeHeader(index: Int) = vm.removeHeader(index)
                override fun publish() = vm.publish()
                override fun cancel() = vm.cancel()
                override fun clearFeedback() = vm.clearFeedback()
            })
        }
        waitForIdle()
        val lines = listOf("publishMqttDialogTitle", "publishMqttTopicInput", "publishMqttHeaderNotice", "publishMqttPublishButton").map {
            "$it: ${onNodeWithTag(it).getUnclippedBoundsInRoot()}"
        }
        File(outDir, "publish-mqtt-$name.txt").writeText("window ${width}x$height\n" + lines.joinToString("\n") + "\n")
        check(ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(outDir, "publish-mqtt-$name.png")))
        scope.cancel()
    }

    @Test fun `at 1280x800`() = runDesktopComposeUiTest(1280, 800) { shoot("1280x800", 1280, 800) }
    @Test fun `at 1000x600`() = runDesktopComposeUiTest(1000, 600) { shoot("1000x600", 1000, 600) }
}
