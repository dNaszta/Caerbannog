package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.publish.MqttPublishAccess
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions of the screens' wiring in Main.kt, tested without a window: what is offered, what is refreshed, which routes feed a stream. */
class StreamWiringTest {

    private fun source(hasMqtt: Boolean, vhost: String = "/") = StreamSource(
        catalog = FakeStreamCatalog(), offsets = FakeStreamOffsets(), vhost = vhost, topology = FakeTopologyCatalog(),
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

    // --- is MQTT publishing offered, and may it be used (M9-10e) ---

    private val noWriteText = "Test publishing is disabled: no write permission on virtual host /."

    @Test
    fun `MQTT publish access is NoMqttPort without a port or a connection, whatever the permission`() {
        for (publishing in listOf(true, false)) {
            assertEquals(MqttPublishAccess.NoMqttPort, mqttPublishAccess(source(hasMqtt = false), publishing))
            assertEquals(MqttPublishAccess.NoMqttPort, mqttPublishAccess(null, publishing), "not connected")
        }
    }

    @Test
    fun `MQTT publish access without write permission names the virtual host of the source`() {
        assertEquals(MqttPublishAccess.NoWritePermission(noWriteText), mqttPublishAccess(source(hasMqtt = true), publishingEnabled = false))
        assertEquals(
            MqttPublishAccess.NoWritePermission("Test publishing is disabled: no write permission on virtual host orders."),
            mqttPublishAccess(source(hasMqtt = true, vhost = "orders"), publishingEnabled = false),
        )
    }

    @Test
    fun `MQTT publish access is Allowed with a port and write permission`() {
        assertEquals(MqttPublishAccess.Allowed, mqttPublishAccess(source(hasMqtt = true), publishingEnabled = true))
    }

    @Test
    fun `the stream screen action is absent without MQTT or a connection, whatever the permission`() {
        for (publishing in listOf(true, false)) {
            for (src in listOf(source(hasMqtt = false), null)) {
                val entry = streamMqttOpener(src, publishing, setTopic = { error("no") }, openDialog = { error("no") })
                assertNull(entry.open)
                assertNull(entry.disabledReason)
            }
        }
    }

    @Test
    fun `the stream screen action is disabled with the reason and calls nothing without write permission`() {
        val entry = streamMqttOpener(source(hasMqtt = true), publishingEnabled = false, setTopic = { error("no topic") }, openDialog = { error("no dialog") })
        assertNull(entry.open, "a disabled action has no callback")
        assertEquals(noWriteText, entry.disabledReason)
    }

    @Test
    fun `the stream screen action pre-fills the suggested topic and opens the dialog`() {
        val calls = mutableListOf<String>()
        val entry = streamMqttOpener(source(hasMqtt = true), publishingEnabled = true, setTopic = { calls += "topic:$it" }, openDialog = { calls += "open" })
        assertNull(entry.disabledReason, "an enabled action carries no reason")
        val open = assertNotNull(entry.open)

        open("sensors/#")
        open(null)

        assertEquals(listOf("topic:sensors/#", "open", "open"), calls, "a missing suggestion keeps the topic and still opens")
    }

    @Test
    fun `the topology screen action is absent without MQTT, whatever the permission`() {
        for (publishing in listOf(true, false)) {
            for (src in listOf(source(hasMqtt = false), null)) {
                val entry = topologyMqttOpener(src, publishing) { error("no") }
                assertNull(entry.open)
                assertNull(entry.disabledReason)
            }
        }
    }

    @Test
    fun `the topology screen action is disabled with the reason and opens nothing without write permission`() {
        val entry = topologyMqttOpener(source(hasMqtt = true, vhost = "orders"), publishingEnabled = false) { error("no dialog") }
        assertNull(entry.open)
        assertEquals("Test publishing is disabled: no write permission on virtual host orders.", entry.disabledReason)
    }

    @Test
    fun `the topology screen action opens the dialog with MQTT and write permission`() {
        var opened = 0
        val entry = topologyMqttOpener(source(hasMqtt = true), publishingEnabled = true) { opened++ }
        assertNull(entry.disabledReason)
        assertNotNull(entry.open).invoke()
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
