package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** M9-10 step 4: opt-in screenshots (tag `screenshot`) of the route form and of the labelled edges, `ui/build/screenshots/planner-mqtt-*.png`. Asserts nothing about the picture. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MqttRouteScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.capture(name: String) {
        waitForIdle()
        outDir.mkdirs()
        check(ImageIO.write(onAllNodes(androidx.compose.ui.test.isRoot()).onLast().captureToImage().toAwtImage(), "png", File(outDir, "planner-mqtt-$name.png")))
    }

    private fun graph(name: String, routes: Int, pair: Boolean, width: Int = 1280) = runDesktopComposeUiTest(width, 900) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
            stream("telemetry", withResponse = pair)
            (1..routes).forEach { mqttRoute("sensors/room$it/+", if (pair && it % 2 == 0) "telemetry-dln" else "telemetry", MqttDestinationKind.Stream) }
        }
        showPlanner(r); capture(name); r.close()
    }

    @Test fun `01 one route`() = graph("01-one", 1, false)
    @Test fun `02 two routes`() = graph("02-two", 2, false)
    @Test fun `03 five routes`() = graph("03-five", 5, false)
    @Test fun `04 five routes into a pair`() = graph("04-pair", 5, true)
    @Test fun `10 ten routes`() = graph("10-ten", 10, false)
    @Test fun `11 twenty routes`() = graph("11-twenty", 20, false)
    @Test fun `12 twenty routes at 1024 x 768`() = graph("12-twenty-1024", 20, false, 1024)
    @Test fun `05 two routes at 1024`() = graph("05-two-1024", 2, false, 1024)

    @Test fun `00 baseline two plain bindings`() = runDesktopComposeUiTest(1280, 900) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
            exchange("events", "topic"); stream("telemetry"); binding("events", "telemetry", "sensors.room1.*"); binding("events", "telemetry", "sensors.room2.*")
        }
        showPlanner(r); capture("00-baseline-two-plain"); r.close()
    }

    @Test fun `06 the form with a filter`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(); showPlanner(r)
        node("planner:addStepBtn").performClick(); node("planWizard:kind:MQTT route").performClick(); node("planWizard:next").performClick()
        capture("06-form-empty")
        node("planForm:destinationKind:stream").performClick()
        node("planForm:filter").performTextInput("sensors/+/temp"); node("planForm:destination").performTextInput("telemetry")
        capture("07-form-filled")
        r.close()
    }
}
