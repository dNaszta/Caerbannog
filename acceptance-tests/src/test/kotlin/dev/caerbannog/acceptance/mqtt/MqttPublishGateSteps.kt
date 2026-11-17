package dev.caerbannog.acceptance.mqtt

import dev.caerbannog.core.mqtt.StreamInboundRoute
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M9-10e: the four controls that open the Publish MQTT dialog, on the real stream and Topology screens. The facts are in [MqttGateWorld], the
 * screens in [MqttGateScreens]. The port comes from the Background step in `MqttPublishingSteps`.
 */
class MqttPublishGateSteps {

    private companion object {
        const val STREAM_BUTTON = "publishMqttStreamButton"
        const val TOPOLOGY_BUTTON = "publishMqttButton"
        const val DIALOG = "publishMqttDialogScrim"
    }

    private var routeKey: String? = null

    @Before
    fun reset() {
        MqttGateWorld.reset()
        routeKey = null
    }

    @After
    fun closeScreens() = MqttGateScreens.endScenario()

    // --- the facts ---

    @Given("the user has no write permission on {string}")
    fun noWritePermission(vhost: String) {
        MqttGateWorld.vhost = vhost
        MqttGateWorld.grant(enabled = false)
    }

    @Given("the user may publish on {string}")
    fun mayPublish(vhost: String) {
        MqttGateWorld.vhost = vhost
        MqttGateWorld.grant(enabled = true)
    }

    @Given("the permission check of {string} has not finished")
    fun checkNotFinished(@Suppress("UNUSED_PARAMETER") connection: String) {
        MqttGateWorld.vhost = "/"
        MqttGateWorld.permissionStatus = dev.caerbannog.core.connections.PermissionCheckStatus.Checking
    }

    // --- the screens ---

    @When("I open the stream {string} that is fed by the MQTT topic {string}")
    fun openStream(stream: String, topic: String) {
        MqttGateWorld.subjects.clear()
        routeKey = topic.replace('/', '.')
        MqttGateScreens.openStream(stream, StreamInboundRoute(exchange = "amq.topic", routingKey = routeKey!!))
    }

    @When("I open the MQTT tab of the topology screen")
    fun openTopology() {
        MqttGateWorld.subjects.clear()
        MqttGateScreens.openTopology()
    }

    private fun stream() = checkNotNull(MqttGateScreens.stream) { "the stream screen is not open" }
    private fun topology() = checkNotNull(MqttGateScreens.topology) { "the topology screen is not open" }
    private fun routeTag() = "publishMqttRouteButton:${checkNotNull(routeKey) { "no route" }}"
    private fun nodeTag() = "pipelineNode:route:amq.topic:${checkNotNull(routeKey) { "no route" }}"

    private fun expectDisabled(driver: MqttGateDriver, tag: String) {
        assertTrue(driver.isThere(tag), "$tag is not drawn: a control without write permission is shown disabled, not hidden")
        assertTrue(driver.isDisabled(tag), "$tag is drawn but is not disabled")
        MqttGateWorld.subjects += tag
    }

    private fun expectEnabled(driver: MqttGateDriver, tag: String) {
        assertTrue(driver.isThere(tag), "$tag is not drawn")
        assertTrue(driver.isEnabledAndClickable(tag), "$tag is drawn but is not enabled with a click action")
    }

    // --- disabled ---

    @Then("the {string} action of the stream is disabled")
    fun streamActionDisabled(label: String) {
        assertEquals("Publish MQTT", label)
        expectDisabled(stream(), STREAM_BUTTON)
    }

    @Then("the {string} action of the route {string} is disabled")
    fun routeActionDisabled(label: String, topic: String) {
        assertEquals("Publish MQTT", label)
        assertEquals(topic.replace('/', '.'), routeKey, "this scenario opened another route")
        expectDisabled(stream(), routeTag())
    }

    @Then("the MQTT route node of the pipeline graph is disabled")
    fun nodeDisabled() = expectDisabled(stream(), nodeTag())

    @Then("the {string} action is disabled")
    fun topologyActionDisabled(label: String) {
        assertEquals("Publish message", label)
        expectDisabled(topology(), TOPOLOGY_BUTTON)
    }

    // --- says ---

    @Then("each of them says {string}")
    fun eachSays(text: String) = says(text)

    @Then("it says {string}")
    fun itSays(text: String) = says(text)

    private fun says(text: String) {
        val tags = MqttGateWorld.subjects.toList()
        assertTrue(tags.isNotEmpty(), "no control was looked at before this step")
        for (tag in tags) {
            val driver = if (tag == TOPOLOGY_BUTTON) topology() else stream()
            assertEquals(text, driver.contentDescription(tag), "what $tag says")
        }
    }

    // --- enabled ---

    @Then("the {string} action of the stream is enabled")
    fun streamActionEnabled(label: String) {
        assertEquals("Publish MQTT", label)
        expectEnabled(stream(), STREAM_BUTTON)
    }

    @Then("the {string} action of the route {string} is enabled")
    fun routeActionEnabled(label: String, topic: String) {
        assertEquals("Publish MQTT", label)
        assertEquals(topic.replace('/', '.'), routeKey, "this scenario opened another route")
        expectEnabled(stream(), routeTag())
    }

    @Then("the MQTT route node of the pipeline graph is enabled")
    fun nodeEnabled() = expectEnabled(stream(), nodeTag())

    @Then("the {string} action is enabled")
    fun topologyActionEnabled(label: String) {
        assertEquals("Publish message", label)
        expectEnabled(topology(), TOPOLOGY_BUTTON)
    }

    // --- absent ---

    @Then("there is no {string} action on the stream screen")
    fun noStreamAction(label: String) {
        assertEquals("Publish MQTT", label)
        for (tag in listOf(STREAM_BUTTON, routeTag())) assertFalse(stream().isThere(tag), "$tag is drawn but the profile has no MQTT port")
    }

    @Then("there is no {string} action")
    fun noTopologyAction(label: String) {
        assertEquals("Publish message", label)
        assertFalse(topology().isThere(TOPOLOGY_BUTTON), "$TOPOLOGY_BUTTON is drawn but the profile has no MQTT port")
    }

    // --- pressing a disabled control ---

    @When("I try to press {string}")
    fun tryToPress(label: String) {
        assertEquals("Publish message", label)
        topology().press(TOPOLOGY_BUTTON)
    }

    @Then("the Publish MQTT dialog is not open")
    fun dialogNotOpen() {
        val open = listOfNotNull(MqttGateScreens.stream, MqttGateScreens.topology).any { it.isThere(DIALOG) }
        assertFalse(open, "the Publish MQTT dialog is open")
    }

    @Then("nothing was published over MQTT")
    fun nothingPublished() = assertEquals(emptyList<Any>(), MqttGateWorld.publisher.publishedMessages.toList(), "messages published over MQTT")
}
