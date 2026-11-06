package dev.caerbannog.acceptance.publish

import dev.caerbannog.core.connections.fakes.FakeMessagePublisher
import dev.caerbannog.core.publish.PublishGate
import dev.caerbannog.core.publish.PublishMessageViewModel
import dev.caerbannog.core.publish.PublishTargetKind
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PublishTestMessageSteps {

    private val fakePublisher = FakeMessagePublisher()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionName: String = "prod-eu"
    private var vhost: String = "/"
    private var writePermission: Boolean = true
    private var viewModel: PublishMessageViewModel? = null
    private var openedStream: String? = null

    @Before
    fun setUp() {
        fakePublisher.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        connectionName = "prod-eu"
        vhost = "/"
        writePermission = true
        viewModel = null
        openedStream = null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun ensureViewModel(): PublishMessageViewModel {
        return viewModel ?: PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = connectionName,
            vhost = vhost,
            writePermission = writePermission,
            scope = scope,
        ).also { viewModel = it }
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        connectionName = name
    }

    @Given("the user has write permission on {string}")
    fun theUserHasWritePermissionOn(vhostPath: String) {
        vhost = vhostPath
        writePermission = true
    }

    @Given("the user has no write permission on {string}")
    fun theUserHasNoWritePermissionOn(vhostPath: String) {
        vhost = vhostPath
        writePermission = false
    }

    @Given("a stream {string}")
    fun aStream(name: String) {
        // Stream exists on broker
    }

    @Given("an exchange {string}")
    fun anExchange(name: String) {
        // Exchange exists on broker
    }

    @Given("a quorum queue {string}")
    fun aQuorumQueue(name: String) {
        // Queue exists on broker
    }

    @When("I open the publish dialog and choose the target kind {string}")
    fun iOpenThePublishDialogAndChooseTargetKind(kindName: String) {
        val vm = ensureViewModel()
        val kind = PublishTargetKind.valueOf(kindName.uppercase())
        vm.setTargetKind(kind)
    }

    @When("I open the publish dialog")
    fun iOpenThePublishDialog() {
        ensureViewModel()
    }

    @When("^I pick \"([^\"]+)\" and enter the body (.+)$")
    fun iPickAndEnterTheBody(targetName: String, body: String) {
        val vm = ensureViewModel()
        vm.setTargetName(targetName)
        val cleanedBody = body.removeSurrounding("\"")
        vm.setBody(cleanedBody)
    }

    @When("^I pick \"([^\"]+)\", set the routing key \"([^\"]+)\" and enter the body (.+)$")
    fun iPickSetRoutingKeyAndEnterBody(targetName: String, routingKey: String, body: String) {
        val vm = ensureViewModel()
        vm.setTargetName(targetName)
        vm.setRoutingKey(routingKey)
        val cleanedBody = body.removeSurrounding("\"")
        vm.setBody(cleanedBody)
    }

    @When("I set message-id {string} and content-type {string}")
    fun iSetMessageIdAndContentType(messageId: String, contentType: String) {
        val vm = ensureViewModel()
        vm.setMessageId(messageId)
        vm.setContentType(contentType)
    }

    @When("I add the application property {string} with value {string}")
    fun iAddTheApplicationPropertyWithValue(key: String, value: String) {
        val vm = ensureViewModel()
        vm.addApplicationProperty(key, value)
    }

    @When("I publish to the stream {string}")
    fun iPublishToTheStream(streamName: String) = runBlocking {
        val vm = ensureViewModel()
        vm.setTargetKind(PublishTargetKind.STREAM)
        vm.setTargetName(streamName)
        if (vm.state.value.body.isEmpty()) {
            vm.setBody("test-message")
        }
        vm.publish()
        // Wait for coroutine
        Thread.sleep(50)
    }

    @When("I publish")
    fun iPublish() = runBlocking {
        val vm = ensureViewModel()
        vm.publish()
        Thread.sleep(50)
    }

    @When("I cancel")
    fun iCancel() {
        val vm = ensureViewModel()
        vm.cancel()
    }

    @When("I open the stream {string}")
    fun iOpenTheStream(streamName: String) {
        openedStream = streamName
        ensureViewModel()
    }

    @When("I open the publish dialog and fill in a message")
    fun iOpenThePublishDialogAndFillInAMessage() {
        val vm = ensureViewModel()
        vm.setTargetKind(PublishTargetKind.STREAM)
        vm.setTargetName("orders-events")
        vm.setBody("will be cancelled")
    }

    @Given("the broker refuses publishes to {string} with an access error")
    fun theBrokerRefusesPublishesToWithAccessError(targetName: String) {
        fakePublisher.refuseNextWith = "Broker access error: publish refused on '$targetName'"
    }

    @When("I publish a test message to {string}")
    fun iPublishATestMessageTo(targetName: String) = runBlocking {
        val vm = ensureViewModel()
        vm.setTargetKind(PublishTargetKind.STREAM)
        vm.setTargetName(targetName)
        vm.setBody("message")
        vm.publish()
        Thread.sleep(50)
    }

    @Then("the broker receives one message on {string} with that body")
    fun theBrokerReceivesOneMessageOnWithThatBody(streamName: String) {
        val matches = fakePublisher.findPublished(PublishTargetKind.STREAM, streamName)
        assertEquals(1, matches.size, "Expected 1 message published to stream '$streamName'")
        val expectedBody = viewModel?.state?.value?.body.orEmpty()
        assertEquals(expectedBody, matches.single().payload.decodeToString())
    }

    @Then("I see a confirmation that the message was published")
    fun iSeeAConfirmationThatTheMessageWasPublished() {
        val confirmation = viewModel?.state?.value?.confirmation
        assertNotNull(confirmation, "Expected confirmation message")
        assertTrue(confirmation!!.contains("published", ignoreCase = true))
    }

    @Then("the broker receives one message for exchange {string} with routing key {string}")
    fun theBrokerReceivesOneMessageForExchangeWithRoutingKey(exchangeName: String, routingKey: String) {
        val matches = fakePublisher.findPublished(PublishTargetKind.EXCHANGE, exchangeName)
        assertEquals(1, matches.size, "Expected 1 message published to exchange '$exchangeName'")
        val published = matches.single()
        assertEquals(routingKey, published.target.routingKey)
        val expectedBody = viewModel?.state?.value?.body.orEmpty()
        assertEquals(expectedBody, published.payload.decodeToString())
    }

    @Then("the broker receives one message for the queue {string}")
    fun theBrokerReceivesOneMessageForTheQueue(queueName: String) {
        val matches = fakePublisher.findPublished(PublishTargetKind.QUEUE, queueName)
        assertEquals(1, matches.size, "Expected 1 message published to queue '$queueName'")
        val expectedBody = viewModel?.state?.value?.body.orEmpty()
        assertEquals(expectedBody, matches.single().payload.decodeToString())
    }

    @Then("the published message carries those properties and application properties")
    fun thePublishedMessageCarriesThosePropertiesAndApplicationProperties() {
        val published = fakePublisher.publishedMessages.lastOrNull()
        assertNotNull(published, "No message published")
        assertEquals("test-0001", published!!.properties["messageId"])
        assertEquals("application/json", published.properties["contentType"])
        assertEquals("eu", published.applicationProperties["tenant"])
    }

    @Then("I see that the message goes to the real broker on {string}")
    fun iSeeThatTheMessageGoesToTheRealBrokerOn(expectedConnectionName: String) {
        val banner = viewModel?.state?.value?.warningBanner.orEmpty()
        assertTrue(banner.contains(expectedConnectionName), "Banner should mention $expectedConnectionName")
        assertEquals(expectedConnectionName, viewModel?.state?.value?.connectionName)
    }

    @Then("the {string} action is disabled")
    fun theActionIsDisabled(actionName: String) {
        val gate = viewModel?.state?.value?.gate
        assertTrue(gate is PublishGate.Disabled, "Action '$actionName' should be disabled")
        assertFalse(viewModel?.state?.value?.canPublish == true)
    }

    @Then("it explains that the user has no write permission")
    fun itExplainsThatTheUserHasNoWritePermission() {
        val gate = viewModel?.state?.value?.gate as? PublishGate.Disabled
        assertNotNull(gate, "Gate must be disabled")
        assertTrue(gate!!.reason.contains("no write permission", ignoreCase = true))
    }

    @Then("I see the broker's refusal")
    fun iSeeTheBrokersRefusal() {
        val error = viewModel?.state?.value?.error
        assertNotNull(error, "Expected broker error / refusal")
        assertTrue(error!!.contains("refused", ignoreCase = true) || error.contains("error", ignoreCase = true))
    }

    @Then("I am not told that the message was published")
    fun iAmNotToldThatTheMessageWasPublished() {
        assertNull(viewModel?.state?.value?.confirmation, "Confirmation should not be shown on refusal")
    }

    @Then("nothing is sent to the broker")
    fun nothingIsSentToTheBroker() {
        assertEquals(0, fakePublisher.publishedMessages.size, "No message should have been sent")
    }
}
