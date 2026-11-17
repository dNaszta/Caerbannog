package dev.caerbannog.desktopapp

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.mqtt.PublishMqttDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M9-10b step 4, round 2: the hosted flow of "Send a test message". `MigrationsWindowContent` is `private` in Main.kt, so it cannot be called from a test;
 * this hosts the same pieces with the same wiring lines (the real [AppComposition] over the fakes of core-testing, `migrationsInputs`,
 * `withTestMessageGates`, `openTestMessage`, `UuidCorrelationIds`, the overlay as a sibling of the screen, `onClose` clearing it). Proves: a fresh view
 * model per opening (the first one's edits do not leak, a new UUID), Cancel clears the overlay, opening publishes nothing, and Publish reaches only the
 * connection's fake MQTT publisher and leaves the dialog with its confirmation until Cancel.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsTestMessageHostedFlowTest {
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

    private val actions = listOf(
        MigrationAction.DeclareStream("device-commands-dln"),
        MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.answer"),
    )

    private fun composition(): AppComposition {
        val composition = createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = Dispatchers.Unconfined,
        )
        composition.viewModel.addConnection(
            ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", mqttPort = 1883),
        )
        runBlocking { composition.viewModel.connect("uat-eu") }
        return composition
    }

    private fun androidx.compose.ui.test.ComposeUiTest.host(composition: AppComposition) = setContent {
        val connections by composition.viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val inputs = composition.migrationsInputs(connections)
        val file = MigrationFile("0005", "Routes", actions, """{ "version": "0005" }""", "abc")
        val state = MigrationsScreenState(
            entries = listOf(MigrationEntry(file, MigrationStatus.Applied("abc"))), selectedVersion = "0005", selectedFile = file,
            profileName = "uat-eu", dryRun = MigrationDryRun("0005", actions.map { ActionDryRun(it, allowed = true) }, canApply = false),
        )
        // The lines below are the ones of MigrationsWindowContent in Main.kt.
        var testMessage by remember(inputs.connectionName) { mutableStateOf<TestMessageSession?>(null) }
        MigrationsScreen(
            state = state.withTestMessageGates(inputs),
            onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
            onSendTestMessage = { opening -> testMessage = composition.openTestMessage(opening, inputs.connectionName, scope, onClose = { testMessage = null }) },
            correlationIds = UuidCorrelationIds,
        )
        testMessage?.let { session ->
            val publishState by session.vm.state.collectAsState()
            PublishMqttDialog(state = publishState, events = session.events)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.typed(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.openTestMessage(value: String) {
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        onNodeWithTag("testMessageValue:0").performTextReplacement(value)
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
    }

    @Test
    fun `each opening gets a fresh view model, Cancel clears the overlay and opening publishes nothing`() = runDesktopComposeUiTest(1280, 800) {
        val composition = composition()
        try {
            host(composition)
            waitForIdle()
            val publisher = gateway.mqttPublisherOf("uat-eu")

            openTestMessage("d42")
            assertTrue(drawn("publishMqttDialog"), "the Publish MQTT dialog is the overlay")
            assertEquals("devices/d42/answer", typed("publishMqttTopicInput"))
            val first = typed("publishMqttHeaderValue:0")
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(first), "a UUID, got $first")
            assertTrue(publisher.publishedMessages.isEmpty(), "opening publishes nothing")

            // edits of the first opening: topic, header value, a second header, retain
            onNodeWithTag("publishMqttTopicInput").performTextReplacement("changed/topic")
            onNodeWithTag("publishMqttHeaderValue:0").performTextReplacement("mine")
            onNodeWithTag("publishMqttAddHeaderButton").performClick()
            waitForIdle()
            assertTrue(drawn("publishMqttHeaderKey:1"))

            onNodeWithTag("publishMqttCancelButton").performClick()
            waitForIdle()
            assertTrue(!drawn("publishMqttDialog"), "Cancel clears the overlay")
            assertTrue(publisher.publishedMessages.isEmpty())

            openTestMessage("d43")
            assertEquals("devices/d43/answer", typed("publishMqttTopicInput"), "the topic is the new one, not the edited one")
            assertEquals("correlation-id", typed("publishMqttHeaderKey:0"))
            val second = typed("publishMqttHeaderValue:0")
            assertNotEquals(first, second, "a new correlation id")
            assertNotEquals("mine", second)
            assertTrue(!drawn("publishMqttHeaderKey:1"), "the added header did not leak")
            assertTrue(drawn("publishMqttHeaderNotice"), "the notice is there again")
            assertTrue(publisher.publishedMessages.isEmpty())
        } finally { composition.close() }
    }

    @Test
    fun `Publish reaches only the connection's MQTT publisher and the dialog stays with its confirmation until Cancel`() = runDesktopComposeUiTest(1280, 800) {
        val composition = composition()
        try {
            host(composition)
            waitForIdle()
            openTestMessage("d42")
            val id = typed("publishMqttHeaderValue:0")
            onNodeWithTag("publishMqttPublishButton").performClick()
            waitForIdle()

            val sent = gateway.mqttPublisherOf("uat-eu").publishedMessages.single()
            assertEquals("devices/d42/answer", sent.topic)
            assertEquals(listOf("correlation-id" to id), sent.headers.map { it.key to it.value })
            assertTrue(drawn("publishMqttDialog"), "the dialog stays after a successful publish")
            assertTrue(drawn("publishMqttConfirmation"), "with its confirmation")
            onNodeWithTag("publishMqttCancelButton").performClick()
            waitForIdle()
            assertTrue(!drawn("publishMqttDialog"))
            assertEquals(1, gateway.mqttPublisherOf("uat-eu").publishedMessages.size, "Cancel publishes nothing more")
        } finally { composition.close() }
    }

    @Test
    fun `a sample value of 10000 characters goes through the whole flow and the broker's refusal shows in the dialog`() = runDesktopComposeUiTest(1280, 800) {
        val composition = composition()
        try {
            host(composition)
            waitForIdle()
            val long = "x".repeat(10_000)
            val publisher = gateway.mqttPublisherOf("uat-eu")
            openTestMessage(long)
            assertEquals("devices/$long/answer", typed("publishMqttTopicInput"))
            publisher.refuseNextWith = "topic too long for this broker"
            onNodeWithTag("publishMqttPublishButton").performClick()
            waitForIdle()
            assertTrue(drawn("publishMqttError"), "the broker's refusal is shown in the dialog")
            assertTrue(publisher.publishedMessages.isEmpty(), "nothing was published")
            assertTrue(drawn("publishMqttDialog"), "the dialog stays so the user can change the topic")
        } finally { composition.close() }
    }
}
