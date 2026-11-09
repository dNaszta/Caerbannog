package dev.caerbannog.ui.streams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.PublisherRowState
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.buildStreamPipelineGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7-06: the three layers of the pipeline graph, which nodes can be clicked, and the hide and show toggle on the stream screen. */
@OptIn(ExperimentalTestApi::class)
class PipelineGraphUiTest {

    private val mqttRoute = StreamInboundRoute(exchange = "amq.topic", routingKey = "sensors.#")
    private val exchangeRoute = StreamInboundRoute(exchange = "orders-ex", routingKey = "order.created")

    private val publisher = PublisherRowState(
        key = "p1", connection = "10.0.0.1:5000", user = "svc", reference = "ingest-1",
        published = "1,000", confirmed = "1,000", errors = "0", settling = false,
    )

    private fun consumer(key: String, offset: Long?, status: ConsumerStatus = ConsumerStatus.Behind(10)) = ConsumerRowState(
        key = key, name = "worker-$key", connection = "10.0.5.14:38812", user = "svc",
        offset = offset?.toString() ?: "-", behind = "10", status = status, browseFromOffset = offset,
    )

    private val graph = buildStreamPipelineGraph(
        stream = "orders",
        lastOffset = "999",
        inboundRoutes = listOf(mqttRoute, exchangeRoute),
        publishers = listOf(publisher),
        consumers = listOf(consumer("c1", 900), consumer("c2", null, ConsumerStatus.Updating)),
    )

    @Test
    fun `ingress, stream hub and consumers are laid out in three layers from left to right`() = runDesktopComposeUiTest(1280, 800) {
        setContent { androidx.compose.material3.MaterialTheme { StreamPipelineGraphCard(graph) } }

        fun left(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
        val ingress = listOf("pipelineNode:route:amq.topic:sensors.#", "pipelineNode:route:orders-ex:order.created", "pipelineNode:pub:p1")
        val hub = "pipelineNode:stream:orders"
        val egress = listOf("pipelineNode:cons:c1", "pipelineNode:cons:c2")

        val hubLeft = left(hub)
        assertTrue(ingress.all { left(it) < hubLeft }, "ingress nodes sit left of the stream")
        assertTrue(egress.all { left(it) > hubLeft }, "consumers sit right of the stream")
        onNodeWithText("head @999").assertExists()
        onNodeWithText("MQTT: sensors/#").assertExists()
        onNodeWithText("key: order.created").assertExists()
        onNodeWithText("1,000 published").assertExists()
    }

    @Test
    fun `a stream without routes, publishers or consumers shows only the hub`() = runDesktopComposeUiTest(1280, 800) {
        val bare = buildStreamPipelineGraph(stream = "lonely", lastOffset = "")
        setContent { androidx.compose.material3.MaterialTheme { StreamPipelineGraphCard(bare) } }

        onNodeWithTag("pipelineNode:stream:lonely").assertExists()
        onNodeWithText("stream").assertExists()
        assertEquals(false, bare.hasSourcesOrSinks)
        assertTrue(bare.edges.isEmpty() && bare.nodes.size == 1, "nodes ${bare.nodes.map { it.id }}, edges ${bare.edges}")
    }

    @Test
    fun `the MQTT route opens the publish dialog with its suggested topic, other ingress nodes and the hub do nothing`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String?>()
        setContent { androidx.compose.material3.MaterialTheme { StreamPipelineGraphCard(graph, onOpenPublishMqttDialog = { opened += it }) } }

        onNodeWithTag("pipelineNode:route:amq.topic:sensors.#").assertHasClickAction().performClick()
        assertEquals(listOf<String?>(mqttRoute.suggestedPublishTopic), opened)
        assertEquals("sensors/test", opened.single())

        onNodeWithTag("pipelineNode:route:orders-ex:order.created").assertHasNoClickAction()
        onNodeWithTag("pipelineNode:pub:p1").assertHasNoClickAction()
        onNodeWithTag("pipelineNode:stream:orders").assertHasNoClickAction()
    }

    @Test
    fun `without a publish callback the MQTT route is not clickable`() = runDesktopComposeUiTest(1280, 800) {
        setContent { androidx.compose.material3.MaterialTheme { StreamPipelineGraphCard(graph) } }
        onNodeWithTag("pipelineNode:route:amq.topic:sensors.#").assertHasNoClickAction()
    }

    @Test
    fun `a consumer node seeks to its offset, a consumer without an offset cannot be clicked`() = runDesktopComposeUiTest(1280, 800) {
        val sought = mutableListOf<Long>()
        setContent { androidx.compose.material3.MaterialTheme { StreamPipelineGraphCard(graph, onSeekToOffset = { sought += it }) } }

        onNodeWithTag("pipelineNode:cons:c1").assertHasClickAction().performClick()
        assertEquals(listOf(900L), sought)
        onNodeWithTag("pipelineNode:cons:c2").assertHasNoClickAction()
    }

    // ---- On the stream screen -------------------------------------------------------------------------------------------------------

    private class SeekRecorder : MessageBrowserEvents by EmptyMessageBrowserEvents {
        val offsets = mutableListOf<Long>()
        override fun browseOffset(offset: Long) { offsets += offset }
    }

    private val browserState = MessageBrowserState.Loaded(
        vhost = "/", stream = "orders",
        messages = listOf(StreamMessage(offset = 0L, timestamp = 1L, payload = "x".toByteArray())),
        streamFirstOffset = 0L, streamLastOffset = 999L, approximateTotal = 1000L, pageSize = 50,
        startMode = BrowseStart.First, canGoNext = true, canGoPrevious = false,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.showStreamScreen(browserEvents: MessageBrowserEvents = EmptyMessageBrowserEvents, withBrowser: Boolean = true) =
        setContent {
            androidx.compose.material3.MaterialTheme {
                StreamScreen(
                    stream = "orders",
                    state = StreamClientsState(
                        screen = StreamClientsScreen.Content, stream = "orders", lastOffset = "999",
                        publishers = listOf(publisher), consumers = listOf(consumer("c1", 900)),
                    ),
                    nowMillis = 10_000L,
                    events = RecordedClientsEvents(),
                    control = connected,
                    controlEvents = noControlEvents,
                    browserState = if (withBrowser) browserState else null,
                    browserEvents = browserEvents,
                    inboundRoutes = listOf(mqttRoute),
                )
            }
        }

    @Test
    fun `the pipeline graph can be hidden and shown again, and starts shown`() = runDesktopComposeUiTest(1280, 800) {
        showStreamScreen()

        onNodeWithTag("streamPipelineGraph").assertExists()
        onNodeWithText("Hide pipeline").assertExists()

        onNodeWithTag("togglePipelineGraphButton").performClick()
        onAllNodesWithTag("streamPipelineGraph").assertCountEquals(0)
        onNodeWithText("Show pipeline").assertExists()

        onNodeWithTag("togglePipelineGraphButton").performClick()
        onNodeWithTag("streamPipelineGraph").assertExists()
        onNodeWithText("Hide pipeline").assertExists()
    }

    @Test
    fun `clicking a consumer node on the stream screen browses to the consumer's offset`() = runDesktopComposeUiTest(1280, 800) {
        val events = SeekRecorder()
        showStreamScreen(events)

        onNodeWithTag("pipelineNode:cons:c1").performClick()
        assertEquals(listOf(900L), events.offsets)
    }

    @Test
    fun `without a message browser the consumer nodes do not seek anywhere`() = runDesktopComposeUiTest(1280, 800) {
        showStreamScreen(withBrowser = false)
        onNodeWithTag("pipelineNode:cons:c1").assertHasNoClickAction()
    }
}
