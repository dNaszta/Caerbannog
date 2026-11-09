package dev.caerbannog.ui.streams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.StreamMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/** M7-02: the Time start mode and its presets read an injected clock, so what they ask the browser for is exact. */
@OptIn(ExperimentalTestApi::class)
class MessageBrowserTimePresetUiTest {

    private val now = 1_700_000_000_000L

    private class Recorder : MessageBrowserEvents by EmptyMessageBrowserEvents {
        val timestamps = mutableListOf<Long>()
        override fun browseTimestamp(epochMs: Long) { timestamps += epochMs }
    }

    private fun loaded(startMode: BrowseStart) = MessageBrowserState.Loaded(
        vhost = "/", stream = "events",
        messages = listOf(StreamMessage(offset = 1, timestamp = now, payload = "x".toByteArray())),
        streamFirstOffset = 0L, streamLastOffset = 10L, approximateTotal = 11L, pageSize = 100,
        startMode = startMode, canGoNext = false, canGoPrevious = false, selectedMessage = null,
        retentionShiftNotice = null, filterNotice = null, isFilterActive = false,
    )

    @Test
    fun `switching to Time starts one hour before the injected now`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        setContent { MessageBrowserView(state = loaded(BrowseStart.First), events = events, nowMillis = { now }) }

        onNodeWithTag("startMode:Time").performClick()

        assertEquals(listOf(now - 3_600_000L), events.timestamps)
    }

    @Test
    fun `the -5m, -1h and -24h presets seek relative to the injected now`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        setContent { MessageBrowserView(state = loaded(BrowseStart.Timestamp(now - 10_000L)), events = events, nowMillis = { now }) }

        onNodeWithTag("timePreset:5m").performClick()
        onNodeWithTag("timePreset:1h").performClick()
        onNodeWithTag("timePreset:24h").performClick()

        assertEquals(listOf(now - 300_000L, now - 3_600_000L, now - 86_400_000L), events.timestamps)
    }

    @Test
    fun `every click reads the clock again, so a preset is relative to the moment of the click`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        var clock = now
        setContent { MessageBrowserView(state = loaded(BrowseStart.Timestamp(now)), events = events, nowMillis = { clock }) }

        onNodeWithTag("timePreset:1h").performClick()
        clock += 60_000L
        onNodeWithTag("timePreset:1h").performClick()

        assertEquals(listOf(now - 3_600_000L, now + 60_000L - 3_600_000L), events.timestamps)
    }
}
