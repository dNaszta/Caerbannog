package dev.caerbannog.ui.streams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.connections.DeleteStreamGate
import dev.caerbannog.core.streams.CreateStreamState
import dev.caerbannog.core.streams.DeleteStreamState
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StreamManagementUiTest {

    private object NoOpStreamClientsEvents : StreamClientsEvents {
        override fun show(stream: String) = Unit
        override fun hide() = Unit
        override fun refreshNow() = Unit
        override fun retry() = Unit
        override fun back() = Unit
    }

    private object NoOpStreamListEvents : StreamListEvents {
        override fun refreshNow() = Unit
        override fun retry() = Unit
        override fun setFilter(text: String) = Unit
        override fun setSort(sort: dev.caerbannog.core.streams.StreamSort) = Unit
        override fun nextPage() = Unit
        override fun previousPage() = Unit
        override fun createStream() = Unit
        override fun setShown(shown: Boolean) = Unit
        override fun toggleExpanded(name: String) = Unit
        override fun setKindFilter(kind: dev.caerbannog.core.streams.StreamKindFilter) = Unit
    }

    @Test
    fun `CreateStreamDialog renders title, input, and actions`() = runDesktopComposeUiTest {
        var nameChanged: String? = null
        var confirmed = false
        var cancelled = false

        setContent {
            CreateStreamDialog(
                state = CreateStreamState(isOpen = true, name = ""),
                onNameChange = { nameChanged = it },
                onConfirm = { confirmed = true },
                onCancel = { cancelled = true },
            )
        }

        onNodeWithTag("createStreamDialogTitle").assertTextEquals("Create stream")
        onNodeWithTag("streamNameInput").assertExists()
        onNodeWithTag("confirmCreateStreamButton").assertExists().assertIsNotEnabled()
        onNodeWithTag("cancelCreateStreamButton").assertExists().assertIsEnabled()

        onNodeWithTag("streamNameInput").performTextInput("new-stream")
        assertEquals("new-stream", nameChanged)

        onNodeWithTag("cancelCreateStreamButton").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `CreateStreamDialog shows error when error is set`() = runDesktopComposeUiTest {
        setContent {
            CreateStreamDialog(
                state = CreateStreamState(isOpen = true, name = "duplicate", error = "A stream with this name exists"),
                onNameChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        onNodeWithTag("createStreamError").assertExists()
    }

    @Test
    fun `CreateStreamDialog enables confirm button when name is valid`() = runDesktopComposeUiTest {
        var confirmed = false

        setContent {
            CreateStreamDialog(
                state = CreateStreamState(isOpen = true, name = "valid-stream"),
                onNameChange = {},
                onConfirm = { confirmed = true },
                onCancel = {},
            )
        }

        val button = onNodeWithTag("confirmCreateStreamButton")
        button.assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `DeleteStreamDialog renders warning, confirmation input, and disabled confirm button`() = runDesktopComposeUiTest {
        var typedName: String? = null
        var cancelled = false

        setContent {
            DeleteStreamDialog(
                state = DeleteStreamState(isOpen = true, streamName = "orders-events", typedConfirmation = ""),
                onConfirmationChange = { typedName = it },
                onConfirm = {},
                onCancel = { cancelled = true },
            )
        }

        onNodeWithTag("deleteStreamDialogTitle").assertTextEquals("Delete stream")
        onNodeWithTag("confirmStreamNameInput").assertExists()
        onNodeWithTag("confirmDeleteStreamButton").assertExists().assertIsNotEnabled()

        onNodeWithTag("confirmStreamNameInput").performTextInput("orders")
        assertEquals("orders", typedName)

        onNodeWithTag("cancelDeleteStreamButton").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `DeleteStreamDialog renders consumers warning when consumers are present`() = runDesktopComposeUiTest {
        setContent {
            DeleteStreamDialog(
                state = DeleteStreamState(isOpen = true, streamName = "orders-events", consumers = 3),
                onConfirmationChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        onNodeWithTag("deleteStreamConsumersWarning").assertExists()
    }

    @Test
    fun `DeleteStreamDialog enables delete button only when name matches in full`() = runDesktopComposeUiTest {
        var confirmed = false

        setContent {
            DeleteStreamDialog(
                state = DeleteStreamState(
                    isOpen = true,
                    streamName = "orders-events",
                    typedConfirmation = "orders-events",
                ),
                onConfirmationChange = {},
                onConfirm = { confirmed = true },
                onCancel = {},
            )
        }

        val button = onNodeWithTag("confirmDeleteStreamButton")
        button.assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `DeleteStreamDialog renders error when broker refuses`() = runDesktopComposeUiTest {
        setContent {
            DeleteStreamDialog(
                state = DeleteStreamState(
                    isOpen = true,
                    streamName = "orders-events",
                    typedConfirmation = "orders-events",
                    error = "Broker refused deletion",
                ),
                onConfirmationChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        onNodeWithTag("deleteStreamError").assertExists()
    }

    @Test
    fun `StreamsScreen displays CreateStreamDialog when open`() = runDesktopComposeUiTest(1280, 800) {
        setContent {
            StreamsScreen(
                state = rowsState(),
                nowMillis = 1000L,
                createStream = CreateStreamGate.Enabled,
                events = NoOpStreamListEvents,
                control = connected,
                controlEvents = noControlEvents,
                createStreamState = CreateStreamState(isOpen = true, name = "my-stream"),
            )
        }

        onNodeWithTag("createStreamDialog").assertExists()
    }

    @Test
    fun `StreamScreen renders delete stream action button and opens dialog`() = runDesktopComposeUiTest(1280, 800) {
        var deleteOpened = false

        setContent {
            StreamScreen(
                stream = "orders-events",
                state = StreamClientsState(screen = StreamClientsScreen.Content),
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                deleteStreamState = DeleteStreamState(isOpen = false, streamName = "orders-events"),
                deleteStreamGate = DeleteStreamGate.Enabled,
                onOpenDeleteDialog = { deleteOpened = true },
            )
        }

        val button = onNodeWithTag("deleteStreamButton")
        button.assertExists().assertIsEnabled()
        button.performClick()
        assertTrue(deleteOpened)
    }

    @Test
    fun `StreamScreen disables delete stream button when configure permission is missing`() = runDesktopComposeUiTest(1280, 800) {
        val reason = "Topology is locked because the user has no configure permission."

        setContent {
            StreamScreen(
                stream = "orders-events",
                state = StreamClientsState(screen = StreamClientsScreen.Content),
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                deleteStreamState = DeleteStreamState(isOpen = false, streamName = "orders-events"),
                deleteStreamGate = DeleteStreamGate.Disabled(reason),
            )
        }

        val button = onNodeWithTag("deleteStreamButton")
        button.assertExists().assertIsNotEnabled()
        button.assertContentDescriptionEquals("Delete stream")
    }

    @Test
    fun `StreamScreen displays DeleteStreamDialog when open`() = runDesktopComposeUiTest(1280, 800) {
        setContent {
            StreamScreen(
                stream = "orders-events",
                state = StreamClientsState(screen = StreamClientsScreen.Content),
                nowMillis = 1000L,
                events = NoOpStreamClientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                deleteStreamState = DeleteStreamState(isOpen = true, streamName = "orders-events", consumers = 2),
            )
        }

        onNodeWithTag("deleteStreamDialog").assertExists()
        onNodeWithTag("deleteStreamConsumersWarning").assertExists()
    }
}
