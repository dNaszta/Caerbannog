package dev.caerbannog.core.planner

import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.MqttHeader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * M9-10b step 2: what the test message opens the Publish MQTT dialog with: the concrete topic, one generated `correlation-id` header (the key is a
 * constant, Q17; the value comes from the injected source) and the notice that matches the kind of the destination (O16 stream, O17 queue, none when unknown).
 */
class CorrelationIdHeaderTest {
    private val route = MqttRoute("devices/+/answer", "devices.*.answer", "device-commands-dln")
    private var n = 0
    private val ids = CorrelationIdSource { "id-${++n}" }

    private val streamText = "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property."
    private val queueText = "In the queue message this arrives as an entry of the headers table named correlation-id, not as the AMQP correlation_id property. Your service must read and write that header."

    @Test
    fun `the opening holds the concrete topic and one correlation-id header pre-filled from the source`() {
        val opening = MqttTestMessage.opening(route, MqttDestinationKind.Stream, listOf("d42"), ids)
        assertEquals("devices/d42/answer", opening.topic)
        assertEquals(listOf(MqttHeader("correlation-id", "id-1")), opening.headers)
    }

    @Test
    fun `two openings differ in the generated value and not in the key`() {
        val first = MqttTestMessage.opening(route, MqttDestinationKind.Stream, listOf("d42"), ids).headers.single()
        val second = MqttTestMessage.opening(route, MqttDestinationKind.Stream, listOf("d42"), ids).headers.single()
        assertEquals(first.key, second.key)
        assertEquals("correlation-id", second.key)
        assertNotEquals(first.value, second.value)
    }

    @Test
    fun `a route without wildcards opens with its own topic`() {
        val plain = MqttRoute("devices/d1/status", "devices.d1.status", "telemetry")
        assertEquals("devices/d1/status", MqttTestMessage.opening(plain, MqttDestinationKind.Stream, emptyList(), ids).topic)
    }
}
