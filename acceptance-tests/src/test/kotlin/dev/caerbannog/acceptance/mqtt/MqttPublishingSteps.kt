package dev.caerbannog.acceptance.mqtt

import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class MqttPublishingSteps {

    private val fakePublisher = FakeMqttMessagePublisher()
    private var lastOutcome: PublishOutcome? = null
    private var connectionName: String = ""
    private var mqttPort: Int = 1883

    @Before
    fun setUp() {
        fakePublisher.clear()
        lastOutcome = null
    }

    @Given("I am connected to {string} with MQTT port {int}")
    fun iAmConnectedToWithMqttPort(name: String, port: Int) {
        connectionName = name
        mqttPort = port
    }

    @Given("the MQTT broker will refuse publication with {string}")
    fun theMqttBrokerWillRefusePublicationWith(reason: String) {
        fakePublisher.refuseNextWith = reason
    }

    @When("I publish an MQTT message to {string} with QoS {int} and payload {string}")
    fun iPublishAnMqttMessage(topic: String, qos: Int, payload: String) = runBlocking {
        val request = PublishMqttRequest(
            topic = topic,
            payload = payload.encodeToByteArray(),
            qos = MqttQos.fromInt(qos),
            retain = false,
        )
        lastOutcome = fakePublisher.publish(request)
    }

    @When("I publish an MQTT message to {string} with QoS {int}, retain {word} and payload {string}")
    fun iPublishAnMqttMessageWithRetain(topic: String, qos: Int, retainWord: String, payload: String) = runBlocking {
        val request = PublishMqttRequest(
            topic = topic,
            payload = payload.encodeToByteArray(),
            qos = MqttQos.fromInt(qos),
            retain = retainWord.toBoolean(),
        )
        lastOutcome = fakePublisher.publish(request)
    }

    @Then("the MQTT broker receives the message on {string} with QoS {int}")
    fun theMqttBrokerReceivesTheMessageOnWithQos(topic: String, qos: Int) {
        val matching = fakePublisher.findPublished(topic)
        assertTrue(matching.isNotEmpty(), "Expected message on topic '$topic'")
        assertEquals(qos, matching.first().qos.value)
    }

    @Then("the message is not retained")
    fun theMessageIsNotRetained() {
        assertFalse(fakePublisher.publishedMessages.last().retain)
    }

    @Then("the message is retained")
    fun theMessageIsRetained() {
        assertTrue(fakePublisher.publishedMessages.last().retain)
    }

    @Then("I see an MQTT publish confirmation {string}")
    fun iSeeAnMqttPublishConfirmation(expected: String) {
        assertTrue(lastOutcome is PublishOutcome.Success, "Expected Success but got $lastOutcome")
        assertEquals(expected, (lastOutcome as PublishOutcome.Success).message)
    }

    @Then("the MQTT publish is refused with reason {string}")
    fun theMqttPublishIsRefusedWithReason(expectedReason: String) {
        assertTrue(lastOutcome is PublishOutcome.Refused, "Expected Refused but got $lastOutcome")
        assertEquals(expectedReason, (lastOutcome as PublishOutcome.Refused).reason)
    }
}
