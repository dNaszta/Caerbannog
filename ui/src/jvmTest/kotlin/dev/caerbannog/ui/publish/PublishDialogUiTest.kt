package dev.caerbannog.ui.publish

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.CurrentConnection
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.publish.PublishGate
import dev.caerbannog.core.publish.PublishMessageState
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.ui.streams.StreamClientsEvents
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PublishDialogUiTest {

    private class RecordingPublishEvents : PublishEvents {
        val calls = mutableListOf<String>()
        var lastKind: PublishTargetKind? = null
        var lastName: String? = null
        var lastRoutingKey: String? = null
        var lastMessageId: String? = null
        var lastContentType: String? = null
        var lastBody: String? = null
        var addedProperty: Pair<String, String>? = null
        var removedProperty: String? = null

        override fun setTargetKind(kind: PublishTargetKind) {
            calls += "setTargetKind:$kind"
            lastKind = kind
        }

        override fun setTargetName(name: String) {
            calls += "setTargetName:$name"
            lastName = name
        }

        override fun setRoutingKey(key: String) {
            calls += "setRoutingKey:$key"
            lastRoutingKey = key
        }

        override fun setMessageId(id: String) {
            calls += "setMessageId:$id"
            lastMessageId = id
        }

        override fun setContentType(type: String) {
            calls += "setContentType:$type"
            lastContentType = type
        }

        override fun setBody(body: String) {
            calls += "setBody:$body"
            lastBody = body
        }

        override fun addApplicationProperty(key: String, value: String) {
            calls += "addApplicationProperty:$key=$value"
            addedProperty = key to value
        }

        override fun removeApplicationProperty(key: String) {
            calls += "removeApplicationProperty:$key"
            removedProperty = key
        }

        override fun publish() {
            calls += "publish"
        }

        override fun cancel() {
            calls += "cancel"
        }

        override fun clearFeedback() {
            calls += "clearFeedback"
        }
    }

    private object NoOpStreamClientsEvents : StreamClientsEvents {
        override fun show(stream: String) = Unit
        override fun hide() = Unit
        override fun refreshNow() = Unit
        override fun retry() = Unit
        override fun back() = Unit
    }

    private fun sampleState(
        connectionName: String = "prod-eu",
        vhost: String = "/",
        gate: PublishGate = PublishGate.Enabled,
        targetKind: PublishTargetKind = PublishTargetKind.STREAM,
        targetName: String = "orders-events",
        routingKey: String = "",
        messageId: String = "",
        contentType: String = "application/json",
        body: String = "",
        applicationProperties: List<Pair<String, String>> = emptyList(),
        isPublishing: Boolean = false,
        confirmation: String? = null,
        error: String? = null,
    ): PublishMessageState = PublishMessageState(
        connectionName = connectionName,
        vhost = vhost,
        gate = gate,
        targetKind = targetKind,
        targetName = targetName,
        routingKey = routingKey,
        messageId = messageId,
        contentType = contentType,
        body = body,
        applicationProperties = applicationProperties,
        isPublishing = isPublishing,
        confirmation = confirmation,
        error = error,
    )

    @Test
    fun `publish dialog renders title, segmented choices, inputs, warning banner and actions`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState()
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("publishDialogTitle").assertTextEquals("Publish test message")
        onNodeWithTag("targetKind:stream").assertExists()
        onNodeWithTag("targetKind:exchange").assertExists()
        onNodeWithTag("targetKind:queue").assertExists()
        onNodeWithTag("targetNameInput").assertExists()
        onNodeWithTag("messageIdInput").assertExists()
        onNodeWithTag("contentTypeInput").assertExists()
        onNodeWithTag("bodyInput").assertExists()
        onNodeWithTag("realBrokerWarning").assertExists()
        onNodeWithTag("connectionPill").assertExists()
        onNodeWithTag("cancelButton").assertExists()
        onNodeWithTag("publishButton").assertExists().assertIsEnabled()
    }

    @Test
    fun `switching target kind to exchange reveals routing key input`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val streamState = sampleState(targetKind = PublishTargetKind.STREAM)
        setContent {
            PublishTestMessageDialog(state = streamState, events = events)
        }
        onAllNodesWithTag("routingKeyInput").assertCountEquals(0)

        onNodeWithTag("targetKind:exchange").performClick()
        assertEquals(PublishTargetKind.EXCHANGE, events.lastKind)
    }

    @Test
    fun `routing key is visible when target kind is exchange`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val exchangeState = sampleState(targetKind = PublishTargetKind.EXCHANGE, routingKey = "order.created")
        setContent {
            PublishTestMessageDialog(state = exchangeState, events = events)
        }
        onNodeWithTag("routingKeyInput").assertExists()
    }

    @Test
    fun `editing target fields invokes respective event callbacks`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState(targetKind = PublishTargetKind.EXCHANGE)
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("targetNameInput").performTextInput("updated-exchange")
        assertTrue(events.calls.any { it.startsWith("setTargetName:") })

        onNodeWithTag("routingKeyInput").performTextInput("orders.v1")
        assertTrue(events.calls.any { it.startsWith("setRoutingKey:") })

        onNodeWithTag("messageIdInput").performTextInput("msg-123")
        assertTrue(events.calls.any { it.startsWith("setMessageId:") })

        onNodeWithTag("contentTypeInput").performTextInput("text/plain")
        assertTrue(events.calls.any { it.startsWith("setContentType:") })

        onNodeWithTag("bodyInput").performTextInput("{\"hello\": true}")
        assertTrue(events.calls.any { it.startsWith("setBody:") })
    }

    @Test
    fun `application properties list renders chips and allows removing`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState(applicationProperties = listOf("tenant" to "eu", "env" to "prod"))
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("appProperty:tenant").assertExists()
        onNodeWithTag("appProperty:env").assertExists()

        onNodeWithTag("removeAppProperty:tenant").performClick()
        assertEquals("tenant", events.removedProperty)
    }

    @Test
    fun `add application property reveals input fields and adds property`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState()
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("addAppPropertyButton").performClick()
        onNodeWithTag("newAppPropertyKey").performTextInput("correlationId")
        onNodeWithTag("newAppPropertyValue").performTextInput("corr-999")
        onNodeWithTag("confirmAddAppProperty").performClick()

        assertEquals("correlationId" to "corr-999", events.addedProperty)
    }

    @Test
    fun `publish button triggers publish and cancel button triggers cancel`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState(targetName = "orders-events")
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("publishButton").performClick()
        assertTrue(events.calls.contains("publish"))

        onNodeWithTag("cancelButton").performClick()
        assertTrue(events.calls.contains("cancel"))
    }

    @Test
    fun `disabled publish gate renders reason banner and disables publish button`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val reason = "Test publishing is disabled: no write permission on virtual host /."
        val state = sampleState(gate = PublishGate.Disabled(reason))
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("noWritePermissionBanner").assertExists()
        onNodeWithTag("publishButton").assertIsNotEnabled()
    }

    @Test
    fun `confirmation banner and error banner are displayed when present in state`() = runDesktopComposeUiTest {
        val events = RecordingPublishEvents()
        val state = sampleState(
            confirmation = "Message published to stream 'orders-events'",
            error = "Broker refused message",
        )
        setContent {
            PublishTestMessageDialog(state = state, events = events)
        }

        onNodeWithTag("publishConfirmation").assertExists()
        onNodeWithTag("publishError").assertExists()
    }

    @Test
    fun `StreamScreen renders publish action button in appbar`() = runDesktopComposeUiTest(1280, 800) {
        val streamClientsState = StreamClientsState(screen = StreamClientsScreen.Content)
        val publishState = sampleState()
        val publishEvents = RecordingPublishEvents()
        var opened = false

        setContent {
            StreamScreen(
                stream = "orders-events",
                state = streamClientsState,
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                publishState = publishState,
                publishEvents = publishEvents,
                showPublishDialog = false,
                onOpenPublishDialog = { opened = true },
            )
        }

        val button = onNodeWithTag("publishTestMessageButton")
        button.assertExists().assertIsEnabled()
        button.performClick()
        assertTrue(opened, "Clicking publish action should open publish dialog")
    }

    @Test
    fun `StreamScreen disables publish button when user has no write permission`() = runDesktopComposeUiTest(1280, 800) {
        val streamClientsState = StreamClientsState(screen = StreamClientsScreen.Content)
        val reason = "Test publishing is disabled: no write permission on virtual host /."
        val publishState = sampleState(gate = PublishGate.Disabled(reason))
        val publishEvents = RecordingPublishEvents()

        setContent {
            StreamScreen(
                stream = "orders-events",
                state = streamClientsState,
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                publishState = publishState,
                publishEvents = publishEvents,
                showPublishDialog = false,
            )
        }

        val button = onNodeWithTag("publishTestMessageButton")
        button.assertExists().assertIsNotEnabled()
        button.assertContentDescriptionEquals(reason)
    }

    @Test
    fun `StreamScreen renders PublishTestMessageDialog when showPublishDialog is true`() = runDesktopComposeUiTest(1280, 800) {
        val streamClientsState = StreamClientsState(screen = StreamClientsScreen.Content)
        val publishState = sampleState()
        val publishEvents = RecordingPublishEvents()

        setContent {
            StreamScreen(
                stream = "orders-events",
                state = streamClientsState,
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                publishState = publishState,
                publishEvents = publishEvents,
                showPublishDialog = true,
            )
        }

        onNodeWithTag("publishDialog").assertExists()
        onNodeWithTag("publishDialogTitle").assertTextEquals("Publish test message")
    }
}
