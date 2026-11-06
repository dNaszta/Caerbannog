package dev.caerbannog.ui.topology

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.core.topology.buildBindingGraph
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M2-08c step 2: opt-in screenshot helper (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye check against `docs/ux/Topology.dc.html`, one
 * file per mockup anchor in `ui/build/screenshots/`: `topology-01-queues` (top block with the banner, the switch, chips, table and detail), `-02-exchanges`
 * (`tab-exchanges`), `-03-bindings` (`tab-bindings-table`), `-04-vhost-menu` (`vhost-menu`), `-05-empty` (`state-empty`), `-06-unavailable`
 * (`state-vhost-unavailable`), `-07-stale` (`state-stale`), `-08-no-tag` (`state-no-tag`), `-09-banner-read-only` and `-10-banner-other-vhost`
 * (`banner-reasons`), `-11-loading`. No pixel comparison; each test asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class TopologyScreenshotTest {

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.shot(state: TopologyState, fileName: String, before: DesktopComposeUiTest.() -> Unit = {}) {
        screen(state, nowMillis = 13_000)
        waitForIdle()
        before()
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    private val readOnly = banner(TopologyBannerKind.ReadOnlyLevel, TopologyTexts.READ_ONLY_HEADLINE, TopologyTexts.READ_ONLY_DETAIL)

    @Test
    fun `screenshot - queues with the detail and a banner`() = runDesktopComposeUiTest(1280, 880) {
        shot(queuesState(selected = mockupDetail, banners = listOf(banner(TopologyBannerKind.NoConfigure, TopologyTexts.noConfigure("svc-explorer", "/"))), updatedAtMillis = 5_000), "topology-01-queues.png")
    }

    @Test
    fun `screenshot - exchanges`() = runDesktopComposeUiTest(1280, 880) {
        shot(
            exchangesState(listOf(exchange(""), exchange("dev.events", "topic"), exchange("dev.fanout", "fanout"), exchange("orders", "direct", superStream = true))),
            "topology-02-exchanges.png",
        )
    }

    @Test
    fun `screenshot - bindings table`() = runDesktopComposeUiTest(1280, 880) {
        shot(
            bindingsState(
                listOf(
                    binding("dev.events", "dev-jobs", key = "job.#"), binding("dev.events", "dev-mail", key = "mail.*"),
                    binding("dev.events", "dev.fanout", type = "exchange", key = "#"), binding("orders", "orders-0", key = "0", arguments = """{"x-stream-partition-order": 0}"""),
                ),
            ),
            "topology-03-bindings.png",
        )
    }

    @Test
    fun `screenshot - vhost menu open`() = runDesktopComposeUiTest(1280, 880) {
        shot(queuesState(), "topology-04-vhost-menu.png") { onNodeWithTag("vhostSwitch", useUnmergedTree = true).performClick() }
    }

    @Test
    fun `screenshot - empty vhost`() = runDesktopComposeUiTest(1280, 880) { shot(emptyQueues, "topology-05-empty.png") }

    @Test
    fun `screenshot - virtual host not available`() = runDesktopComposeUiTest(1280, 880) { shot(unavailable, "topology-06-unavailable.png") }

    @Test
    fun `screenshot - stale rows after a lost connection`() = runDesktopComposeUiTest(1280, 880) {
        shot(queuesState(stale = true, connectionBanner = "Connection lost", action = "Retry", updatedAtMillis = 1_000), "topology-07-stale.png")
    }

    @Test
    fun `screenshot - user without the management tag`() = runDesktopComposeUiTest(1280, 880) { shot(noTag, "topology-08-no-tag.png") }

    @Test
    fun `screenshot - banner connection is set to read-only`() = runDesktopComposeUiTest(1280, 880) { shot(queuesState(banners = listOf(readOnly)), "topology-09-banner-read-only.png") }

    @Test
    fun `screenshot - banner other virtual host`() = runDesktopComposeUiTest(1280, 880) {
        shot(queuesState(vhost = "billing", banners = listOf(banner(TopologyBannerKind.OtherVhost, TopologyTexts.otherVhost("/")))), "topology-10-banner-other-vhost.png")
    }

    @Test
    fun `screenshot - loading`() = runDesktopComposeUiTest(1280, 880) { shot(loading, "topology-11-loading.png") }

    @Test
    fun `screenshot - bindings graph`() = runDesktopComposeUiTest(1280, 880) {
        val exchanges = listOf(
            ExchangeRow("dev.events", "/", "topic", durable = true, autoDelete = false, internal = false),
            ExchangeRow("dev.fanout", "/", "fanout", durable = true, autoDelete = false, internal = false),
            ExchangeRow("orders", "/", "direct", durable = true, autoDelete = false, internal = false),
        )
        val queues = listOf(
            QueueRow("dev-jobs", "/", "quorum", readyMessages = 0, unackedMessages = 0, consumers = 0, leader = null, members = emptyList(), online = emptyList(), policy = null, state = "running"),
            QueueRow("dev-mail", "/", "quorum", readyMessages = 0, unackedMessages = 0, consumers = 0, leader = null, members = emptyList(), online = emptyList(), policy = null, state = "running"),
            QueueRow("orders-0", "/", "quorum", readyMessages = 0, unackedMessages = 0, consumers = 0, leader = null, members = emptyList(), online = emptyList(), policy = null, state = "running"),
        )
        val bindings = listOf(
            BindingRow("dev.events", "/", "dev-jobs", BindingDestinationType.QUEUE, "job.#"),
            BindingRow("dev.events", "/", "dev-mail", BindingDestinationType.QUEUE, "mail.*"),
            BindingRow("dev.events", "/", "dev.fanout", BindingDestinationType.EXCHANGE, "#"),
            BindingRow("orders", "/", "orders-0", BindingDestinationType.QUEUE, "0"),
        )
        val graph = buildBindingGraph(exchanges, queues, bindings, showDefaultExchange = false)
        val graphState = TopologyState(
            screen = dev.caerbannog.core.topology.TopologyScreen.Ready,
            message = null,
            tab = dev.caerbannog.core.topology.TopologyTab.Bindings,
            vhost = "/",
            vhosts = listOf("/", "billing"),
            showVhostSwitch = true,
            bindingsView = BindingsViewMode.Graph,
            graph = graph,
            bindings = listOf(
                binding("dev.events", "dev-jobs", key = "job.#"),
                binding("dev.events", "dev-mail", key = "mail.*"),
                binding("dev.events", "dev.fanout", type = "exchange", key = "#"),
                binding("orders", "orders-0", key = "0"),
            ),
            total = 4,
            rangeText = "1 to 4 of 4",
        )
        shot(graphState, "topology-12-bindings-graph.png")
    }
}
