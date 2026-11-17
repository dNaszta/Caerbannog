package dev.caerbannog.ui.streams

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.StreamMessage
import org.junit.jupiter.api.Test

/** M9-00b-c step 4: the Messages detail shows `x-opt-deaths` as the rendered text, not as the array identity. */
@OptIn(ExperimentalTestApi::class)
class DeadLetterPropertiesUiTest {

    private val deadLetter = StreamMessage(
        offset = 7L,
        timestamp = 1_700_000_000_000L,
        payload = "to-the-stream".toByteArray(),
        properties = linkedMapOf(
            "x-first-death-reason" to "rejected",
            "x-opt-deaths" to arrayOf<Any?>(linkedMapOf<String, Any?>("queue" to "orders", "reason" to "rejected", "count" to 1L)),
        ),
        applicationProperties = linkedMapOf("tags" to listOf("a", "b")),
    )

    @Test
    fun `the detail shows x-opt-deaths and a list application property as text`() = runDesktopComposeUiTest(1280, 800) {
        setContent {
            MaterialTheme {
                MessageBrowserView(
                    state = MessageBrowserState.Loaded(
                        vhost = "/", stream = "dead", messages = listOf(deadLetter),
                        streamFirstOffset = 0L, streamLastOffset = 7L, approximateTotal = 8L, pageSize = 100,
                        startMode = BrowseStart.Offset(7L), canGoNext = false, canGoPrevious = false, selectedMessage = deadLetter,
                    ),
                    events = EmptyMessageBrowserEvents,
                )
            }
        }
        onNodeWithText("[{queue: orders, reason: rejected, count: 1}]").assertExists()
        onNodeWithText("[a, b]").assertExists()
    }
}
