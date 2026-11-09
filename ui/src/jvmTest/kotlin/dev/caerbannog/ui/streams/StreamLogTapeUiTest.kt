package dev.caerbannog.ui.streams

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.StreamLogTape
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7-01: what the log tape shows and does in the message browser, beyond the two cases in `MessageBrowserUiTest`. */
@OptIn(ExperimentalTestApi::class)
class StreamLogTapeUiTest {

    private class SeekRecorder : MessageBrowserEvents by EmptyMessageBrowserEvents {
        val offsets = mutableListOf<Long>()
        override fun browseOffset(offset: Long) { offsets += offset }
    }

    private fun message(offset: Long) = StreamMessage(offset = offset, timestamp = 1_700_000_000_000L + offset, payload = "m$offset".toByteArray())

    private fun loaded(first: Long, last: Long, page: List<Long> = listOf(first)) = MessageBrowserState.Loaded(
        vhost = "/", stream = "telemetry", messages = page.map(::message),
        streamFirstOffset = first, streamLastOffset = last, approximateTotal = last - first + 1, pageSize = 50,
        startMode = BrowseStart.First, canGoNext = true, canGoPrevious = false,
    )

    private fun consumer(key: String, offset: Long?, status: ConsumerStatus) = ConsumerRowState(
        key = key, name = key, connection = "127.0.0.1:50000", user = "svc",
        offset = offset?.toString() ?: "-", behind = "?", status = status, browseFromOffset = offset,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: MessageBrowserState, events: MessageBrowserEvents = SeekRecorder(), consumers: List<ConsumerRowState> = emptyList()) =
        setContent { androidx.compose.material3.MaterialTheme { MessageBrowserView(state = state, events = events, consumers = consumers) } }

    @Test
    fun `there is no tape when the stream has no usable offsets or the browser is not showing messages`() {
        val states = listOf(
            MessageBrowserState.Empty(vhost = "/", stream = "telemetry"),
            MessageBrowserState.Error(vhost = "/", stream = "telemetry", message = "boom"),
            MessageBrowserState.Loading(vhost = "/", stream = "telemetry", start = BrowseStart.First, pageSize = 50),
            loaded(first = 500, last = 100), // inconsistent offsets: last before first
        )
        for (state in states) {
            runDesktopComposeUiTest(1280, 800) {
                show(state)
                onNodeWithTag("streamLogTapeCard").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `a stream that starts at offset 0 shows its range and head but no truncated or retention cliff badge`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded(first = 0, last = 200))

        val tape = StreamLogTape.from(0, 200)!!
        onNodeWithTag("streamLogTapeCard").assertExists()
        onNodeWithTag("logTapeRangeText").assertTextEquals(tape.rangeSummaryText)
        onNodeWithTag("logTapeTruncatedBadge").assertDoesNotExist()
        onNodeWithTag("logTapeRetentionCliffBadge").assertDoesNotExist()
    }

    @Test
    fun `a truncated stream shows the expired range and the retention cliff, with the texts of the core model`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded(first = 2_000, last = 10_000, page = listOf(5_000, 5_001)))

        val tape = StreamLogTape.from(2_000, 10_000, 5_000, 5_001)!!
        onNodeWithTag("logTapeRangeText").assertTextEquals(tape.rangeSummaryText)
        onNodeWithTag("logTapeTruncatedBadge").onChild().assertTextEquals(tape.truncatedBadgeText!!)
        onNodeWithTag("logTapeRetentionCliffBadge").onChild().assertTextEquals(tape.cliffBadgeText!!)
        onNodeWithTag("logTapeViewingSlice").assertTextEquals(tape.viewingSliceText!!)
        assertEquals("Retention cliff <= 2,800", tape.cliffBadgeText)
    }

    @Test
    fun `clicking the tape seeks - the truncated part to the first offset, the right end to the head, the middle in proportion`() = runDesktopComposeUiTest(1280, 800) {
        val events = SeekRecorder()
        show(loaded(first = 100, last = 1_100), events)

        onNodeWithTag("streamLogTapeCanvas").performTouchInput { click(Offset(width * 0.05f, centerY)) } // inside the 14 percent truncated part
        onNodeWithTag("streamLogTapeCanvas").performTouchInput { click(Offset(width * 0.999f, centerY)) }
        onNodeWithTag("streamLogTapeCanvas").performTouchInput { click(Offset(width * 0.57f, centerY)) } // 0.14 + 0.86 * 0.5

        assertEquals(3, events.offsets.size)
        assertEquals(100L, events.offsets[0], "truncated part seeks to the first offset")
        assertTrue(events.offsets[1] in 1_095L..1_100L, "right end seeks to the head, got ${events.offsets[1]}")
        assertTrue(events.offsets[2] in 590L..610L, "middle seeks to about 600, got ${events.offsets[2]}")
    }

    @Test
    fun `consumer pins carry their health, standby consumers and consumers without an offset are handled`() = runDesktopComposeUiTest(1280, 800) {
        val events = SeekRecorder()
        val consumers = listOf(
            consumer("risk", 150, ConsumerStatus.Behind(950)), // inside the cliff zone 100..200
            consumer("lag", 600, ConsumerStatus.Behind(500)),
            consumer("head", 1_100, ConsumerStatus.CaughtUp),
            consumer("standby", 300, ConsumerStatus.NotActive),
            consumer("settling", null, ConsumerStatus.Updating), // no browsable offset: no pin
        )
        show(loaded(first = 100, last = 1_100), events, consumers)

        onNodeWithTag("logTapeConsumerChip:risk").assertTextContains("At risk (near cliff)", substring = true)
        onNodeWithTag("logTapeConsumerChip:head").assertTextContains("Caught up", substring = true)
        onNodeWithTag("logTapeConsumerChip:standby").assertTextContains("Standby", substring = true)
        onNodeWithTag("logTapeConsumerChip:lag").assertTextContains(ConsumerStatus.Behind(500).text, substring = true)
        onNodeWithTag("logTapeConsumerChip:settling").assertDoesNotExist()

        onNodeWithTag("logTapeConsumerChip:standby").performClick()
        assertEquals(listOf(300L), events.offsets)
    }

    @Test
    fun `without a truncated prefix no consumer is at risk, however low its offset`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded(first = 0, last = 1_000), consumers = listOf(consumer("low", 5, ConsumerStatus.Behind(995))))

        onNodeWithTag("logTapeConsumerChip:low").assertTextContains(ConsumerStatus.Behind(995).text, substring = true)
        onNodeWithTag("logTapeRetentionCliffBadge").assertDoesNotExist()
    }
}
