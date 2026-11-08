package dev.caerbannog.core.mqtt

import dev.caerbannog.core.connections.GuardedMqttPublisher
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttMessagePublisherTest {

    @Test
    fun `MqttQos fromInt maps valid values and falls back to AT_MOST_ONCE`() {
        assertEquals(MqttQos.AT_MOST_ONCE, MqttQos.fromInt(0))
        assertEquals(MqttQos.AT_LEAST_ONCE, MqttQos.fromInt(1))
        assertEquals(MqttQos.EXACTLY_ONCE, MqttQos.fromInt(2))
        assertEquals(MqttQos.AT_MOST_ONCE, MqttQos.fromInt(99))
    }

    @Test
    fun `PublishMqttRequest equality and hashCode consider byte array content`() {
        val payload1 = "hello".encodeToByteArray()
        val payload2 = "hello".encodeToByteArray()
        val req1 = PublishMqttRequest("sensors/temp", payload1, MqttQos.AT_LEAST_ONCE, true)
        val req2 = PublishMqttRequest("sensors/temp", payload2, MqttQos.AT_LEAST_ONCE, true)
        val req3 = PublishMqttRequest("sensors/temp", payload1, MqttQos.AT_MOST_ONCE, true)

        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())
        assertNotEquals(req1, req3)
    }

    @Test
    fun `UnavailableMqttMessagePublisher returns Failed outcome`() = runBlocking {
        val outcome = UnavailableMqttMessagePublisher.publish(
            PublishMqttRequest("sensors/temp", "test".encodeToByteArray()),
        )
        assertTrue(outcome is PublishOutcome.Failed)
        assertEquals("MQTT publishing is not available", (outcome as PublishOutcome.Failed).error)
    }

    @Test
    fun `FakeMqttMessagePublisher validates blank topics and wildcards`() = runBlocking {
        val fake = FakeMqttMessagePublisher()

        val blankOutcome = fake.publish(PublishMqttRequest("   ", "payload".encodeToByteArray()))
        assertTrue(blankOutcome is PublishOutcome.Refused)
        assertEquals("Topic cannot be blank", (blankOutcome as PublishOutcome.Refused).reason)

        val plusOutcome = fake.publish(PublishMqttRequest("sensors/+/temp", "payload".encodeToByteArray()))
        assertTrue(plusOutcome is PublishOutcome.Refused)
        assertEquals("Topic cannot contain wildcards '+' or '#'", (plusOutcome as PublishOutcome.Refused).reason)

        val hashOutcome = fake.publish(PublishMqttRequest("sensors/#", "payload".encodeToByteArray()))
        assertTrue(hashOutcome is PublishOutcome.Refused)
        assertEquals("Topic cannot contain wildcards '+' or '#'", (hashOutcome as PublishOutcome.Refused).reason)
    }

    @Test
    fun `FakeMqttMessagePublisher records published messages and supports failures`() = runBlocking {
        val fake = FakeMqttMessagePublisher()
        val req = PublishMqttRequest("sensors/temp", "21.5".encodeToByteArray(), MqttQos.AT_LEAST_ONCE, false)

        val success = fake.publish(req)
        assertTrue(success is PublishOutcome.Success)
        assertEquals("Message published to 'sensors/temp' (QoS 1)", (success as PublishOutcome.Success).message)
        assertEquals(1, fake.findPublished("sensors/temp").size)

        fake.refuseNextWith = "Quota exceeded"
        val refused = fake.publish(req)
        assertTrue(refused is PublishOutcome.Refused)
        assertEquals("Quota exceeded", (refused as PublishOutcome.Refused).reason)

        fake.failNextWith = "Network drop"
        val failed = fake.publish(req)
        assertTrue(failed is PublishOutcome.Failed)
        assertEquals("Network drop", (failed as PublishOutcome.Failed).error)
    }

    @Test
    fun `GuardedMqttPublisher rejects publish when superseded`() = runBlocking {
        val fake = FakeMqttMessagePublisher()
        var current = true
        val guarded = GuardedMqttPublisher(fake) { current }

        val req = PublishMqttRequest("telemetry/data", "ok".encodeToByteArray())
        val ok = guarded.publish(req)
        assertTrue(ok is PublishOutcome.Success)

        current = false
        val disconnected = guarded.publish(req)
        assertTrue(disconnected is PublishOutcome.Failed)
        assertEquals("Connection closed", (disconnected as PublishOutcome.Failed).error)
    }
}
