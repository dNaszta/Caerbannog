package dev.caerbannog.core.planner

import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.HeaderNotice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M9-10b step 2: the two warning texts, word for word (owner decisions O16 and O17), and no guess for an unknown kind. */
class CorrelationWarningTest {
    private val route = MqttRoute("devices/+/answer", "devices.*.answer", "x")
    private val ids = CorrelationIdSource { "id" }

    @Test
    fun `a stream destination gets the O16 text on the key correlation-id`() {
        val notice = MqttTestMessage.opening(route, MqttDestinationKind.Stream, listOf("d"), ids).notice
        assertEquals(
            HeaderNotice(
                "correlation-id",
                "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property.",
            ),
            notice,
        )
    }

    @Test
    fun `a queue destination gets the O17 text on the key correlation-id`() {
        val notice = MqttTestMessage.opening(route, MqttDestinationKind.Queue, listOf("d"), ids).notice
        assertEquals(
            HeaderNotice(
                "correlation-id",
                "In the queue message this arrives as an entry of the headers table named correlation-id, not as the AMQP correlation_id property. Your service must read and write that header.",
            ),
            notice,
        )
    }

    @Test
    fun `an unknown kind gives no notice and no guess`() {
        assertNull(MqttTestMessage.opening(route, null, listOf("d"), ids).notice)
    }
}
