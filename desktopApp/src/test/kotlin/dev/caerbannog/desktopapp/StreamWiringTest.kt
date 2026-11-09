package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions of the screens' wiring in Main.kt, tested without a window: what is offered, what is refreshed, which routes feed a stream. */
class StreamWiringTest {

    private fun source(hasMqtt: Boolean) = StreamSource(
        catalog = FakeStreamCatalog(), offsets = FakeStreamOffsets(), vhost = "/", topology = FakeTopologyCatalog(),
        messages = FakeStreamMessageReader(), hasMqtt = hasMqtt,
    )

    private fun binding(source: String, destination: String, routingKey: String = "", args: Map<String, String> = emptyMap()) =
        BindingRow(source = source, vhost = "/", destination = destination, destinationType = BindingDestinationType.QUEUE, routingKey = routingKey, arguments = args)

    // --- inbound routes of a stream ---

    @Test
    fun `inbound routes are the bindings into the stream, without the default exchange`() {
        val routes = inboundRoutesFor(
            listOf(
                binding("amq.topic", "telemetry", "sensors.#"),
                binding("orders", "telemetry", "created", mapOf("x-match" to "all")),
                binding("", "telemetry"),
                binding("amq.topic", "other-stream", "x.#"),
            ),
            stream = "telemetry",
        )

        assertEquals(
            listOf(
                StreamInboundRoute("amq.topic", "sensors.#"),
                StreamInboundRoute("orders", "created", mapOf("x-match" to "all")),
            ),
            routes,
        )
        assertTrue(routes.first().isMqttTopicExchange)
    }

    @Test
    fun `a stream nothing is bound to has no routes`() {
        assertEquals(emptyList(), inboundRoutesFor(listOf(binding("amq.topic", "other", "a")), "telemetry"))
        assertEquals(emptyList(), inboundRoutesFor(emptyList(), "telemetry"))
    }

    // --- is MQTT publishing offered ---

    @Test
    fun `MQTT publishing is offered only for a source whose profile has an MQTT port`() {
        assertEquals(true, offersMqttPublishing(source(hasMqtt = true)))
        assertEquals(false, offersMqttPublishing(source(hasMqtt = false)))
        assertEquals(false, offersMqttPublishing(null), "not connected")
    }

    @Test
    fun `the stream screen action is absent without MQTT or a connection`() {
        assertNull(streamMqttOpener(source(hasMqtt = false), setTopic = { error("no") }, openDialog = { error("no") }))
        assertNull(streamMqttOpener(null, setTopic = { error("no") }, openDialog = { error("no") }))
    }

    @Test
    fun `the stream screen action pre-fills the suggested topic and opens the dialog`() {
        val calls = mutableListOf<String>()
        val open = assertNotNull(streamMqttOpener(source(hasMqtt = true), setTopic = { calls += "topic:$it" }, openDialog = { calls += "open" }))

        open("sensors/#")
        open(null)

        assertEquals(listOf("topic:sensors/#", "open", "open"), calls, "a missing suggestion keeps the topic and still opens")
    }

    @Test
    fun `the topology screen action is absent without MQTT and opens the dialog with it`() {
        assertNull(topologyMqttOpener(source(hasMqtt = false)) { error("no") })
        assertNull(topologyMqttOpener(null) { error("no") })

        var opened = 0
        assertNotNull(topologyMqttOpener(source(hasMqtt = true)) { opened++ }).invoke()
        assertEquals(1, opened)
    }

    // --- refresh after a publish ---

    @Test
    fun `after a stream publish the newest page is shown first, then clients and the stream list refresh`() {
        val calls = mutableListOf<String>()
        afterStreamPublish({ calls += "browse" }, { calls += "clients" }, { calls += "list" })()
        assertEquals(listOf("browse", "clients", "list"), calls)
    }

    @Test
    fun `after a stream publish without a message browser the two refreshes still happen`() {
        val calls = mutableListOf<String>()
        afterStreamPublish(null, { calls += "clients" }, { calls += "list" })()
        assertEquals(listOf("clients", "list"), calls)
    }

    @Test
    fun `after an MQTT publish on the topology screen the topology and the stream list refresh`() {
        val calls = mutableListOf<String>()
        afterTopologyPublish({ calls += "topology" }, { calls += "list" })()
        assertEquals(listOf("topology", "list"), calls)
    }
}
