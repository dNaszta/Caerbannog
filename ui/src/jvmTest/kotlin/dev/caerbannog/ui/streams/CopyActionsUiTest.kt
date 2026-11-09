package dev.caerbannog.ui.streams

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.ui.components.rememberCopyToClipboard
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7-09 / M7-11: what the copy actions really put on the clipboard (text equals the core formatter's output). */
@OptIn(ExperimentalTestApi::class)
class CopyActionsUiTest {

    /** Records what was copied; the app never reads the clipboard. */
    private class FakeClipboard : Clipboard {
        val copied = mutableListOf<String?>()
        override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("fake")
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            copied += clipEntry?.let { (it.nativeClipEntry as Transferable).getTransferData(DataFlavor.stringFlavor) as String }
        }
    }

    private val message = StreamMessage(
        offset = 1202L,
        timestamp = 1_700_000_000_000L,
        payload = """{"orderId":"A-1202"}""".toByteArray(),
        properties = linkedMapOf("contentType" to "application/json", "userId" to "svc"),
        applicationProperties = linkedMapOf("tenant" to "eu"),
        exchange = "amq.topic",
        routingKey = "orders.created",
    )

    private fun loaded(only: StreamMessage) = MessageBrowserState.Loaded(
        vhost = "/", stream = "orders", messages = listOf(only),
        streamFirstOffset = 0L, streamLastOffset = 2000L, approximateTotal = 2001L, pageSize = 100,
        startMode = BrowseStart.Offset(only.offset), canGoNext = true, canGoPrevious = true, selectedMessage = only,
    )

    @Test
    fun `Copy body, Copy offset and Copy metadata put exactly the formatter text on the clipboard`() = runDesktopComposeUiTest(1280, 800) {
        val clipboard = FakeClipboard()
        setContent {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    MessageBrowserView(state = loaded(message), events = EmptyMessageBrowserEvents)
                }
            }
        }
        val formatter = PayloadFormatter()

        onNodeWithTag("copyBodyButton").performClick()
        waitForIdle()
        onNodeWithTag("copyOffsetButton").performClick()
        waitForIdle()
        onNodeWithTag("copyHeadersAndPropertiesButton").performClick()
        waitForIdle()

        assertEquals(
            listOf<String?>(formatter.copyBody(message), formatter.copyOffset(message), formatter.copyHeadersAndProperties(message)),
            clipboard.copied,
        )
        val metadata = clipboard.copied[2]!!
        assertTrue(metadata.contains("Exchange: amq.topic") && metadata.contains("tenant: eu"), metadata)
    }

    @Test
    fun `Copy metadata is offered only when the message has properties or application properties`() = runDesktopComposeUiTest(1280, 800) {
        val bare = StreamMessage(offset = 5L, timestamp = 1L, payload = "x".toByteArray())
        setContent {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides FakeClipboard()) {
                    MessageBrowserView(state = loaded(bare), events = EmptyMessageBrowserEvents)
                }
            }
        }
        onNodeWithTag("copyBodyButton").assertExists()
        onNodeWithTag("copyOffsetButton").assertExists()
        onNodeWithTag("copyHeadersAndPropertiesButton").assertDoesNotExist()
    }

    @Test
    fun `rememberCopyToClipboard writes the given text, also an empty one, and nothing before it is called`() = runDesktopComposeUiTest(400, 300) {
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                val copy = rememberCopyToClipboard()
                Column {
                    Button(onClick = { copy("hello clipboard") }) { Text("copy text") }
                    Button(onClick = { copy("") }) { Text("copy empty") }
                }
            }
        }
        waitForIdle()
        assertTrue(clipboard.copied.isEmpty())

        onNodeWithText("copy text").performClick()
        waitForIdle()
        onNodeWithText("copy empty").performClick()
        waitForIdle()

        assertEquals(listOf<String?>("hello clipboard", ""), clipboard.copied)
    }
}
