package dev.caerbannog.core.mqtt

import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishMqttViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fakePublisher = FakeMqttMessagePublisher()

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `payload starts with an editable default message that setPayload replaces`() {
        val vm = PublishMqttViewModel(publisher = fakePublisher, connectionName = "b", scope = scope)

        assertEquals(PublishMqttState.DEFAULT_PAYLOAD, vm.state.value.payload)
        vm.setPayload("custom")
        assertEquals("custom", vm.state.value.payload)
        vm.setPayload("")
        assertEquals("", vm.state.value.payload)
    }

    @Test
    fun `headers can be added, edited and removed, and publish sends only rows with a key`() {
        val vm = PublishMqttViewModel(publisher = fakePublisher, connectionName = "b", scope = scope, initialTopic = "a/b")

        vm.addHeader(); vm.addHeader(); vm.addHeader()
        vm.setHeader(0, " trace-id ", "abc")
        vm.setHeader(1, "", "orphan value")
        vm.setHeader(2, "trace-id", "second")
        vm.setHeader(9, "ignored", "out of range")
        vm.removeHeader(9)
        assertEquals(3, vm.state.value.headers.size)

        vm.publish()
        assertEquals(
            listOf(MqttHeader("trace-id", "abc"), MqttHeader("trace-id", "second")),
            fakePublisher.publishedMessages.single().headers,
        )

        vm.removeHeader(1)
        assertEquals(listOf(MqttHeader(" trace-id ", "abc"), MqttHeader("trace-id", "second")), vm.state.value.headers)
    }

    @Test
    fun `initial state reflects connection and validates empty topic`() {
        val vm = PublishMqttViewModel(
            publisher = fakePublisher,
            connectionName = "prod-broker",
            scope = scope,
        )

        val state = vm.state.value
        assertEquals("prod-broker", state.connectionName)
        assertEquals("", state.topic)
        assertEquals(MqttQos.AT_MOST_ONCE, state.qos)
        assertFalse(state.retain)
        assertFalse(state.canPublish)
        assertNull(state.topicError)
    }

    @Test
    fun `setTopic flags wildcards and blank inputs`() {
        val vm = PublishMqttViewModel(
            publisher = fakePublisher,
            connectionName = "prod-broker",
            scope = scope,
        )

        vm.setTopic("sensors/temp")
        assertNull(vm.state.value.topicError)
        assertTrue(vm.state.value.canPublish)

        vm.setTopic("sensors/+/temp")
        assertEquals("Topic cannot contain wildcards '+' or '#'", vm.state.value.topicError)
        assertFalse(vm.state.value.canPublish)

        vm.setTopic("sensors/#")
        assertEquals("Topic cannot contain wildcards '+' or '#'", vm.state.value.topicError)
        assertFalse(vm.state.value.canPublish)

        vm.setTopic("   ")
        assertEquals("Topic cannot be blank", vm.state.value.topicError)
        assertFalse(vm.state.value.canPublish)
    }

    @Test
    fun `publish dispatches request and sets confirmation on success`(): Unit = runBlocking {
        val vm = PublishMqttViewModel(
            publisher = fakePublisher,
            connectionName = "prod-broker",
            scope = scope,
        )

        vm.setTopic("sensors/room1/temp")
        vm.setQos(MqttQos.EXACTLY_ONCE)
        vm.setRetain(true)
        vm.setPayload("24.8")

        assertTrue(vm.state.value.canPublish)

        var outcome: PublishOutcome? = null
        vm.publish { outcome = it }

        assertTrue(outcome is PublishOutcome.Success)
        assertFalse(vm.state.value.isPublishing)
        assertEquals("Message published to 'sensors/room1/temp' (QoS 2, retained)", vm.state.value.confirmation)
        assertNull(vm.state.value.error)

        assertEquals(1, fakePublisher.publishedMessages.size)
        val sent = fakePublisher.publishedMessages.first()
        assertEquals("sensors/room1/temp", sent.topic)
        assertEquals(MqttQos.EXACTLY_ONCE, sent.qos)
        assertTrue(sent.retain)
        assertEquals("24.8", sent.payload.decodeToString())
    }

    @Test
    fun `publish sets error on broker refusal or failure`(): Unit = runBlocking {
        val vm = PublishMqttViewModel(
            publisher = fakePublisher,
            connectionName = "prod-broker",
            scope = scope,
        )

        vm.setTopic("restricted/topic")
        fakePublisher.refuseNextWith = "MQTT authentication refused: invalid credentials"

        vm.publish()
        assertNull(vm.state.value.confirmation)
        assertEquals("MQTT authentication refused: invalid credentials", vm.state.value.error)

        fakePublisher.failNextWith = "Connection lost"
        vm.publish()
        assertNull(vm.state.value.confirmation)
        assertEquals("Connection lost", vm.state.value.error)
    }

    @Test
    fun `cancel and clearFeedback reset feedback state`() {
        val vm = PublishMqttViewModel(
            publisher = fakePublisher,
            connectionName = "prod-broker",
            scope = scope,
        )

        vm.setTopic("telemetry/speed")
        fakePublisher.failNextWith = "Error"
        vm.publish()
        assertNotNull(vm.state.value.error)

        vm.clearFeedback()
        assertNull(vm.state.value.error)

        vm.cancel()
        assertFalse(vm.state.value.isPublishing)
    }
}
