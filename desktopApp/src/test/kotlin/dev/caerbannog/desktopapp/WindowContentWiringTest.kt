package dev.caerbannog.desktopapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.binding
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose half of the composition root: the stream and topology window contents of `Main.kt`, rendered for real with the real
 * [AppComposition] over the fakes of core-testing (temp folder, in-memory secrets, fake gateway, `Dispatchers.Unconfined`; no broker, no
 * keychain, no window). The pure decisions (is MQTT offered, what refreshes after a publish) are `StreamWiringTest`; this proves the
 * screens are actually fed by them: the right controls appear, and a click ends at the right fake.
 */
@OptIn(ExperimentalTestApi::class)
class WindowContentWiringTest {

    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private object Permissive : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    }

    private val gateway = FakeConnectionGateway()

    /** A composition with one profile, connected. [mqttPort] null means a profile without MQTT. */
    private fun connected(mqttPort: Int?): AppComposition {
        val composition = createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = Dispatchers.Unconfined,
        )
        composition.viewModel.addConnection(
            ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", mqttPort = mqttPort),
        )
        gateway.catalogOf("uat-eu").addStream(stream("orders"))
        gateway.topologyOf("uat-eu")
            .addQueue(quorum("orders-queue", ready = 2))
            .addBinding(binding("amq.topic", "orders", routingKey = "sensors.#"))
        gateway.inspectorSeed()
        runBlocking { composition.viewModel.connect("uat-eu") }
        assertEquals(LinkState.Connected("uat-eu"), composition.active.state.value, "precondition: connected")
        return composition
    }

    private fun FakeConnectionGateway.inspectorSeed() = queueInspectorOf("uat-eu").addQueue("orders-queue", count = 2)

    private val suggestedTopic = StreamInboundRoute(exchange = "amq.topic", routingKey = "sensors.#").suggestedPublishTopic

    // --- the stream window ---

    @Test
    fun `a profile without an MQTT port shows the inbound route but no way to publish over MQTT on the stream screen`() = runDesktopComposeUiTest(1280, 800) {
        val c = connected(mqttPort = null)
        setContent { MaterialTheme { StreamWindowContent(c, c.control.state.value, "orders") } }

        onNodeWithTag("inboundRoute:amq.topic:sensors.#").assertExists()
        onNodeWithTag("publishMqttStreamButton").assertDoesNotExist()
        onNodeWithTag("publishMqttRouteButton:sensors.#", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a profile with an MQTT port offers it on the stream screen and the dialog opens with the suggested topic`() = runDesktopComposeUiTest(1280, 800) {
        val c = connected(mqttPort = 1883)
        setContent { MaterialTheme { StreamWindowContent(c, c.control.state.value, "orders") } }

        onNodeWithTag("publishMqttStreamButton").assertExists()
        onNodeWithTag("publishMqttRouteButton:sensors.#", useUnmergedTree = true).performClick()

        onNodeWithTag("publishMqttDialog").assertExists()
        assertTrue(suggestedTopic != null)
        onNodeWithTag("publishMqttTopicInput").assert(hasText(suggestedTopic!!))
    }

    @Test
    fun `publishing a test message through the wired dialog reaches the fake publisher and refreshes the browser, the clients and the list`() = runDesktopComposeUiTest(1280, 800) {
        val c = connected(mqttPort = null)
        val reader = gateway.messagesOf("uat-eu")
        val catalog = gateway.catalogOf("uat-eu")
        // Both view models only call the broker while their screen is shown, so show them the way the screens do.
        c.streamList.setShown(true)
        c.streamClients.show("orders")
        setContent { MaterialTheme { StreamWindowContent(c, c.control.state.value, "orders") } }
        waitForIdle()
        val readsBefore = reader.calls.size
        fun count(call: String) = synchronized(catalog.calls) { catalog.calls.count { it == call } }
        val listBefore = count("listStreams")
        val clientsBefore = count("publishers") + count("consumers")

        onNodeWithTag("publishTestMessageButton").performClick()
        onNodeWithTag("publishDialog").assertExists()
        onNodeWithTag("publishButton").performClick()
        waitForIdle()

        val sent = gateway.publisherOf("uat-eu").publishedMessages.single()
        assertEquals("orders", sent.target.name)
        assertEquals(PublishTargetKind.STREAM, sent.target.kind)
        assertTrue(reader.calls.size > readsBefore, "the message browser read its last page again: ${reader.calls}")
        assertTrue(count("listStreams") > listBefore, "the stream list was refreshed")
        assertTrue(count("publishers") + count("consumers") > clientsBefore, "the publishers and consumers were refreshed")
    }

    // --- the topology window ---

    @Test
    fun `the topology screen offers MQTT publishing only for a profile with an MQTT port`() = runDesktopComposeUiTest(1280, 800) {
        val plain = connected(mqttPort = null)
        setContent { MaterialTheme { TopologyWindowContent(plain, plain.control.state.value) } }
        plain.topologyEvents.setShown(true)
        plain.topologyEvents.setTab(TopologyTab.Mqtt)
        waitForIdle()

        onNodeWithTag("publishMqttButton").assertDoesNotExist()
        assertEquals(false, plain.topology.state.value.hasMqtt, "the screen state agrees: no MQTT for this profile")
    }

    @Test
    fun `with an MQTT port the topology screen shows the button and it opens the dialog`() = runDesktopComposeUiTest(1280, 800) {
        val c = connected(mqttPort = 1883)
        setContent { MaterialTheme { TopologyWindowContent(c, c.control.state.value) } }
        c.topologyEvents.setShown(true)
        c.topologyEvents.setTab(TopologyTab.Mqtt)
        waitForIdle()

        onNodeWithTag("publishMqttButton").performClick()
        onNodeWithTag("publishMqttDialog").assertExists()
    }

    @Test
    fun `inspecting the selected queue asks the fake inspector for a requeue peek and labels the messages as not acknowledged`() = runDesktopComposeUiTest(1280, 800) {
        val c = connected(mqttPort = null)
        setContent { MaterialTheme { TopologyWindowContent(c, c.control.state.value) } }
        c.topologyEvents.setShown(true)
        c.topologyEvents.selectQueue("orders-queue")
        waitForIdle()

        onNodeWithTag("inspectQueueMessagesButton").performClick()
        waitForIdle()

        val request = gateway.queueInspectorOf("uat-eu").requests.single()
        assertEquals("orders-queue", request.queue)
        assertEquals("/", request.vhost)
        assertEquals("ack_requeue_true", request.ackMode)
        onNodeWithTag("queueInspectorAckModeBadge:label", useUnmergedTree = true).assert(hasText("Not acknowledged (requeued)"))
    }
}
