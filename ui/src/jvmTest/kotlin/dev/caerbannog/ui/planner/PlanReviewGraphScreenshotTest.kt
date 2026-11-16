package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.planner.planPreview
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * M9-04 step 2: opt-in screenshots (tag `screenshot`, `task test:screenshots`) of the review graph, for the owner's by-eye review against
 * `docs/ux/Planner.dc.html` (the review card of the main block and "8. Review graph: other cases"). 1280 x 800, written to
 * `ui/build/screenshots/planner-graph-*.png`. Asserts nothing about the picture; it fails only when a state cannot be drawn or written.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val broker = KnownTopology(exchanges = mapOf("events" to "topic", "amq.topic" to "topic"), queues = setOf("audit"), streams = setOf("orders-stream"))

    private fun DesktopComposeUiTest.capture(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        outDir.mkdirs()
        check(ImageIO.write(image, "png", File(outDir, "planner-graph-$name.png"))) { "could not write planner-graph-$name.png" }
    }

    private fun shot(name: String, rig: UiRig, width: Int = 1280, height: Int = 800, act: DesktopComposeUiTest.() -> Unit = {}) =
        runDesktopComposeUiTest(width, height) {
            showPlanner(rig)
            act()
            capture(name)
            rig.close()
        }

    private fun connected(prepare: UiRig.() -> Unit) = UiRig(reader = FakeKnownTopologyReader(broker), prepare = prepare)

    @Test fun `01 graph with new and existing parts`() = shot("01-graph", connected {
        queue("orders"); exchange("orders-events", "topic"); binding("orders-events", "orders", "order.#"); binding("events", "audit", "#")
    })

    @Test fun `02 graph with an end that is not in this plan`() = shot("02-not-in-plan", connected { queue("orders"); binding("missing-exchange", "orders", "k") })

    @Test fun `03 declared and already on the broker`() = shot("03-declared-existing", connected { queue("audit"); binding("events", "audit", "#") })

    @Test fun `04 json view`() = shot("04-json", connected { threeSteps() }) { showJson() }

    @Test fun `05 only the plan, not connected`() = shot("05-only-plan-not-connected", UiRig(context = PlannerContext(connectionName = null), reader = null) { threeSteps() })

    @Test fun `06 only the plan, broker not read`() = shot("06-only-plan-not-read", UiRig(reader = FakeKnownTopologyReader(null)) { threeSteps() })

    @Test fun `07 too large to draw`() = runDesktopComposeUiTest(1280, 800) {
        val plan = TopologyPlan().append((1..61).map { MigrationAction.DeclareQueue(name = "queue-$it") })
        showState(PlannerState(plan = plan, graph = planPreview(plan, KnownTopology.None), isLoaded = true))
        capture("07-too-large")
    }

    @Test fun `08 sixty nodes`() = runDesktopComposeUiTest(1280, 800) {
        val plan = TopologyPlan().append((1..30).map { MigrationAction.DeclareQueue(name = "queue-$it") } + (1..30).map { MigrationAction.DeclareExchange(name = "exchange-$it") })
        showState(PlannerState(plan = plan, graph = planPreview(plan, KnownTopology.None), isLoaded = true))
        capture("08-sixty-nodes")
    }

    @Test fun `09 narrow window`() = shot("09-narrow-1024", connected { threeSteps() }, 1024, 768)
}
