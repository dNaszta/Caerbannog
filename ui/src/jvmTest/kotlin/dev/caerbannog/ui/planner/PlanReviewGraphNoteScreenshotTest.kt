package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-07b step 4: opt-in screenshots (tag `screenshot`) of the review graph with the note, to `ui/build/screenshots/planner-note-*.png`, for a by-eye look
 * at the placement (one pair, both routes, five and twenty routes, the same-column case, 1024 x 768). Asserts nothing about the picture.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphNoteScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun shot(name: String, width: Int = 1280, height: Int = 900, prepare: UiRig.() -> Unit) = runDesktopComposeUiTest(width, height) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None), prepare = prepare)
        showPlanner(r)
        waitForIdle()
        outDir.mkdirs()
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "planner-note-$name.png")))
        r.close()
    }

    private fun UiRig.pair(routes: Int) {
        stream("device-commands", withResponse = true)
        (1..routes).forEach { mqttRoute("devices/d$it/answer", "device-commands-dln", MqttDestinationKind.Stream) }
    }

    @Test fun `01 one pair with its answers route`() = shot("01-one") { pair(1) }
    @Test fun `02 both routes`() = shot("02-both") { pair(1); mqttRoute("commands/#", "device-commands", MqttDestinationKind.Stream) }
    @Test fun `03 five routes into the response stream`() = shot("03-five") { pair(5) }
    @Test fun `04 twenty routes into the response stream`() = shot("04-twenty") { pair(20) }
    @Test fun `05 one pair at 1024`() = shot("05-one-1024", 1024, 768) { pair(1) }
    @Test fun `06 same column, an exchange bound to amq topic`() = shot("06-same-column") {
        exchange("events"); add(BindingForm("events", "amq.topic", BindingDestinationType.EXCHANGE, "k")); pair(1)
    }
    @Test fun `07 two pairs`() = shot("07-two-pairs") {
        stream("a", withResponse = true); stream("b", withResponse = true)
        mqttRoute("a/+/answer", "a-dln", MqttDestinationKind.Stream); mqttRoute("b/+/answer", "b-dln", MqttDestinationKind.Stream)
    }
}
