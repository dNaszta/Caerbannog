package dev.caerbannog.ui.streams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageBrowserUiTest {

    private class RecordingBrowserEvents : MessageBrowserEvents {
        val calls = mutableListOf<String>()
        var lastPageSize: Int? = null
        var lastOffset: Long? = null
        var lastSelected: Long? = null

        override fun browseFirst() { calls += "browseFirst" }
        override fun browseLastPage() { calls += "browseLastPage" }
        override fun browseOffset(offset: Long) { calls += "browseOffset:$offset"; lastOffset = offset }
        override fun browseTimestamp(epochMs: Long) { calls += "browseTimestamp:$epochMs" }
        override fun nextPage() { calls += "nextPage" }
        override fun previousPage() { calls += "previousPage" }
        override fun jumpToOffset(offset: Long) { calls += "jumpToOffset:$offset" }
        override fun jumpToNearestValidOffset() { calls += "jumpToNearestValidOffset" }
        override fun setPageSize(size: Int) { calls += "setPageSize:$size"; lastPageSize = size }
        override fun selectMessage(offset: Long?) { calls += "selectMessage:$offset"; lastSelected = offset }
        override fun filter(query: String) { calls += "filter:$query" }
        override fun cancelScan() { calls += "cancelScan" }
        override fun clearFilter() { calls += "clearFilter" }
        override fun retry() { calls += "retry" }
    }

    private fun sampleMessage(offset: Long, body: String = "test payload $offset"): StreamMessage =
        StreamMessage(
            offset = offset,
            timestamp = 1700000000000L + offset * 1000L,
            payload = body.toByteArray(),
            properties = mapOf("content-type" to "application/json"),
            applicationProperties = mapOf("tenant" to "eu"),
        )

    private fun sampleLoadedState(
        messages: List<StreamMessage> = listOf(sampleMessage(100), sampleMessage(101)),
        canGoNext: Boolean = true,
        canGoPrevious: Boolean = true,
        selectedMessage: StreamMessage? = null,
        retentionNotice: String? = null,
        filterNotice: String? = null,
        isFilterActive: Boolean = false,
    ): MessageBrowserState.Loaded = MessageBrowserState.Loaded(
        vhost = "/",
        stream = "events",
        messages = messages,
        streamFirstOffset = 0L,
        streamLastOffset = 200L,
        approximateTotal = 201L,
        pageSize = 100,
        startMode = BrowseStart.First,
        canGoNext = canGoNext,
        canGoPrevious = canGoPrevious,
        selectedMessage = selectedMessage,
        retentionShiftNotice = retentionNotice,
        filterNotice = filterNotice,
        isFilterActive = isFilterActive,
    )

    @Test
    fun `controls render start modes and trigger browse operations`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(),
                events = events,
            )
        }

        onNodeWithTag("startMode:First").assertIsSelected()
        onNodeWithTag("startMode:Last").performClick()
        assertEquals(listOf("browseLastPage"), events.calls)

        onNodeWithTag("startMode:First").performClick()
        assertEquals(listOf("browseLastPage", "browseFirst"), events.calls)
    }

    @Test
    fun `next and previous buttons respect enabled states and invoke paging`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(canGoNext = true, canGoPrevious = false),
                events = events,
            )
        }

        onNodeWithTag("previousPageButton").assertIsNotEnabled()
        onNodeWithTag("nextPageButton").assertIsEnabled()

        onNodeWithTag("nextPageButton").performClick()
        assertEquals(listOf("nextPage"), events.calls)
    }

    @Test
    fun `table renders message rows and selects on click`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val msg1 = sampleMessage(1200, "hello world")
        val msg2 = sampleMessage(1201, "second message")
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(messages = listOf(msg1, msg2)),
                events = events,
            )
        }

        onNodeWithTag("messageRow:1200").assertExists()
        onNodeWithTag("messageCell:1200:offset", useUnmergedTree = true).assertTextEquals("1200")
        onNodeWithTag("messageCell:1200:body", useUnmergedTree = true).assertTextEquals("hello world")

        onNodeWithTag("messageRow:1201").performClick()
        assertEquals(listOf("selectMessage:1201"), events.calls)
    }

    @Test
    fun `empty state displays empty stream message`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = MessageBrowserState.Empty(vhost = "/", stream = "empty-stream", streamFirstOffset = 0, streamLastOffset = 0),
                events = events,
            )
        }

        onNodeWithTag("browserEmpty").assertExists()
        onNodeWithText("This stream has no messages yet").assertExists()
    }

    @Test
    fun `outside range state offers jump to nearest valid offset`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = MessageBrowserState.OutsideRange(
                    vhost = "/",
                    stream = "events",
                    requestedOffset = 50L,
                    validRange = 100L..500L,
                    nearestOffset = 100L,
                ),
                events = events,
            )
        }

        onNodeWithTag("browserOutsideRange").assertExists()
        onNodeWithTag("jumpToNearestButton").performClick()
        assertEquals(listOf("jumpToNearestValidOffset"), events.calls)
    }

    @Test
    fun `retention notice renders banner when present`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(retentionNotice = "Stream start moved to offset 150"),
                events = events,
            )
        }

        onNodeWithTag("retentionNotice").assertExists()
        onNodeWithText("Stream start moved to offset 150").assertExists()
    }

    @Test
    fun `stream screen renders Messages and Publishers tabs and switches between them`() = runDesktopComposeUiTest(1280, 800) {
        val browserEvents = RecordingBrowserEvents()
        val clientsEvents = RecordedClientsEvents()
        val browserState = sampleLoadedState()
        val clientsState = StreamClientsState(
            stream = "events",
            screen = StreamClientsScreen.Content,
        )

        setContent {
            StreamScreen(
                stream = "events",
                state = clientsState,
                nowMillis = 0L,
                events = clientsEvents,
                control = connected,
                controlEvents = noControlEvents,
                browserState = browserState,
                browserEvents = browserEvents,
            )
        }

        onNodeWithTag("tab:Messages").assertIsSelected()
        onNodeWithTag("tab:Publishers and consumers").assertExists()

        // Switch to Publishers and consumers tab
        onNodeWithTag("tab:Publishers and consumers").performClick()
        onNodeWithTag("tab:Publishers and consumers").assertIsSelected()

        // Switch back to Messages
        onNodeWithTag("tab:Messages").performClick()
        onNodeWithTag("tab:Messages").assertIsSelected()
    }

    @Test
    fun `filter bar renders input, scan limit chip, and triggers filter and cancel`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(),
                events = events,
            )
        }

        onNodeWithTag("browserFilterBar").assertExists()
        onNodeWithTag("scanLimitChip").assertExists()
        onNodeWithText("Scan limit: 10,000").assertExists()

        onNodeWithTag("browserFilterInput").performTextInput("tenant=eu")
        onNodeWithTag("applyFilterButton").performClick()
        assertTrue(events.calls.contains("filter:tenant=eu"))

        onNodeWithTag("cancelScanButton").performClick()
        assertTrue(events.calls.contains("cancelScan"))
    }

    @Test
    fun `selecting a message displays detail sheet with tabs and close button`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val msg = sampleMessage(1202, """{"orderId":"A-1202","status":"created"}""")
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(selectedMessage = msg),
                events = events,
            )
        }

        onNodeWithTag("messageDetailsSheet").assertExists()
        onNodeWithText("Message at offset 1202").assertExists()

        onNodeWithTag("viewerTab:JSON").assertExists()
        onNodeWithTag("viewerTab:Text").assertExists()
        onNodeWithTag("viewerTab:Hex").assertExists()

        onNodeWithTag("copyBodyButton").assertExists()
        onNodeWithTag("copyOffsetButton").assertExists()

        onNodeWithTag("closeDetailsButton").performClick()
        assertEquals(listOf("selectMessage:null"), events.calls)
    }

    @Test
    fun `details sheet switches view tabs between Text, JSON, and Hex`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val msg = sampleMessage(1204, "heartbeat")
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(selectedMessage = msg),
                events = events,
            )
        }

        onNodeWithTag("viewerTab:Text").performClick()
        onNodeWithTag("viewerTab:Text").assertIsSelected()

        onNodeWithTag("viewerTab:Hex").performClick()
        onNodeWithTag("viewerTab:Hex").assertIsSelected()
    }

    @Test
    fun `details sheet displays truncated large body and expands on Show all`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val largeMsg = sampleMessage(1300, "A".repeat(80_000))
        setContent {
            MessageBrowserView(
                state = sampleLoadedState(selectedMessage = largeMsg),
                events = events,
            )
        }

        onNodeWithTag("showAllButton").assertExists()
        onNodeWithTag("showAllButton").performClick()
    }

    @Test
    fun `stream log tape renders offset window, truncated cliff badges, and consumer pins that seek on click`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val state = MessageBrowserState.Loaded(
            vhost = "/",
            stream = "telemetry",
            messages = listOf(sampleMessage(500), sampleMessage(550)),
            streamFirstOffset = 100L,
            streamLastOffset = 1100L,
            approximateTotal = 1001L,
            pageSize = 50,
            startMode = BrowseStart.Offset(500L),
            canGoNext = true,
            canGoPrevious = true,
            selectedMessage = null,
        )
        val consumers = listOf(
            ConsumerRowState(
                key = "c-risk",
                name = "slow-worker",
                connection = "127.0.0.1:50001",
                user = "admin",
                offset = "150",
                behind = "950",
                status = ConsumerStatus.Behind(950L),
                browseFromOffset = 150L,
            ),
            ConsumerRowState(
                key = "c-head",
                name = "realtime-sink",
                connection = "127.0.0.1:50002",
                user = "admin",
                offset = "1100",
                behind = "0",
                status = ConsumerStatus.CaughtUp,
                browseFromOffset = 1100L,
            ),
        )

        setContent {
            MessageBrowserView(
                state = state,
                events = events,
                consumers = consumers,
            )
        }

        onNodeWithTag("streamLogTapeCard").assertExists()
        onNodeWithTag("streamLogTapeCanvas").assertExists()
        onNodeWithTag("logTapeTruncatedBadge").assertExists()
        onNodeWithTag("logTapeRetentionCliffBadge").assertExists()
        onNodeWithTag("logTapeConsumerChip:c-risk").assertExists()
        onNodeWithTag("logTapeConsumerChip:c-head").assertExists()

        // Clicking a consumer pin chip jumps the Message Browser to that consumer's offset
        onNodeWithTag("logTapeConsumerChip:c-risk").performClick()
        assertEquals(150L, events.lastOffset)
    }

    @Test
    fun `clicking stream log tape canvas seeks to proportional stream offset`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        val state = sampleLoadedState() // streamFirstOffset = 0L, streamLastOffset = 200L

        setContent {
            MessageBrowserView(
                state = state,
                events = events,
            )
        }

        onNodeWithTag("streamLogTapeCanvas").performClick()
        // Center click (0.5f) on 0..200 seeks near offset 100
        val sought = events.lastOffset
        assertTrue(sought != null && sought in 95L..105L, "Expected center click on 0..200 tape to seek near 100, got $sought")
    }

    @Test
    fun `no messages at or after time state keeps navigation controls and recovery button visible`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordingBrowserEvents()
        setContent {
            MessageBrowserView(
                state = MessageBrowserState.NoMessagesAtOrAfterTime(
                    vhost = "/",
                    stream = "events",
                    epochMs = 1700000000000L,
                    streamFirstOffset = 0L,
                    streamLastOffset = 100L,
                ),
                events = events,
            )
        }

        onNodeWithTag("browserControls").assertExists()
        onNodeWithTag("timestampInput").assertExists()
        onNodeWithTag("applyTimestampButton").assertExists()
        onNodeWithTag("timePreset:1h").assertExists()
        onNodeWithTag("browseFirstFromEmptyTimeButton").performClick()
        assertEquals(listOf("browseFirst"), events.calls)
    }
}
