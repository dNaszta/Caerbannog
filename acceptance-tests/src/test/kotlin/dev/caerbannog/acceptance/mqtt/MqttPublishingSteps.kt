package dev.caerbannog.acceptance.mqtt

import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.mqtt.PublishMqttState
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.publish.PublishOutcome
import io.cucumber.datatable.DataTable
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // --- M7-10: the dialog's headers and default payload (the real view model, a fake publisher) ---

    private var dialog: PublishMqttViewModel? = null

    private fun dialog(): PublishMqttViewModel = checkNotNull(dialog) { "the dialog is not open" }

    @Given("I open the MQTT publish dialog for {string}")
    fun iOpenTheMqttPublishDialog(topic: String) {
        // Unconfined: the fake publisher never suspends, so publish() has finished when the call returns.
        dialog = PublishMqttViewModel(fakePublisher, connectionName, CoroutineScope(Dispatchers.Unconfined), initialTopic = topic)
    }

    @Given("I add these MQTT headers")
    fun iAddTheseMqttHeaders(table: DataTable) {
        for ((index, row) in table.asLists().withIndex()) {
            dialog().addHeader()
            // Cucumber reads an empty table cell as null.
            dialog().setHeader(index, row[0].orEmpty(), row[1].orEmpty())
        }
    }

    @When("I publish the dialog message")
    fun iPublishTheDialogMessage() {
        dialog().publish()
    }

    @When("I change the dialog payload to {string}")
    fun iChangeTheDialogPayload(payload: String) {
        dialog().setPayload(payload)
    }

    @Then("the dialog payload is the sample message")
    fun theDialogPayloadIsTheSampleMessage() {
        assertEquals(PublishMqttState.DEFAULT_PAYLOAD, dialog().state.value.payload)
        assertTrue(dialog().state.value.payload.isNotBlank())
    }

    @Then("the MQTT broker receives the message on {string} with these headers")
    fun theMqttBrokerReceivesWithTheseHeaders(topic: String, table: DataTable) {
        val sent = fakePublisher.findPublished(topic).single()
        assertEquals(table.asLists().map { MqttHeader(it[0], it[1]) }, sent.headers)
    }

    @Then("the MQTT broker receives the message on {string} without headers")
    fun theMqttBrokerReceivesWithoutHeaders(topic: String) {
        assertEquals(emptyList<MqttHeader>(), fakePublisher.findPublished(topic).single().headers)
    }

    @Then("the MQTT broker receives the message on {string} with payload {string}")
    fun theMqttBrokerReceivesWithPayload(topic: String, payload: String) {
        assertEquals(payload, fakePublisher.findPublished(topic).single().payload.decodeToString())
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
