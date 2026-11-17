package dev.caerbannog.ui.publish

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpRect
import dev.caerbannog.core.publish.PublishMessageState
import dev.caerbannog.core.publish.PublishTargetKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10e: the AMQP "Publish test message" dialog keeps Publish and Cancel on screen in a small window (as the Publish MQTT dialog does since M9-10b).
 * The state is the worst realistic case: 8 application properties and a 400-character error. The body scrolls in its own region with a scrollbar
 * only on overflow (`publishDialogBody`, `publishDialogBodyScrollbar`); the banners and the buttons stay below it. Bounds are asserted through
 * `getUnclippedBoundsInRoot` (density 1, so dp equals px).
 */
@OptIn(ExperimentalTestApi::class)
class PublishDialogLayoutUiTest {

    private class NoEvents : PublishEvents {
        override fun setTargetKind(kind: PublishTargetKind) = Unit
        override fun setTargetName(name: String) = Unit
        override fun setRoutingKey(key: String) = Unit
        override fun setMessageId(id: String) = Unit
        override fun setContentType(type: String) = Unit
        override fun setBody(body: String) = Unit
        override fun addApplicationProperty(key: String, value: String) = Unit
        override fun removeApplicationProperty(key: String) = Unit
        override fun publish() = Unit
        override fun cancel() = Unit
        override fun clearFeedback() = Unit
    }

    private fun state(properties: Int = 8, error: String? = "The broker refused this message: ".padEnd(400, 'x'), confirmation: String? = null) = PublishMessageState(
        connectionName = "prod-eu", vhost = "/", targetKind = PublishTargetKind.STREAM, targetName = "orders-events", body = "{}",
        applicationProperties = (1..properties).map { "key-$it" to "value-$it" },
        confirmation = confirmation, error = error,
    )

    private fun ComposeUiTest.show(s: PublishMessageState) = setContent {
        androidx.compose.material3.MaterialTheme { PublishTestMessageDialog(state = s, events = NoEvents()) }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertInside(name: String, r: DpRect, width: Int, height: Int) {
        assertTrue(
            r.left.value >= 0f && r.top.value >= 0f && r.right.value <= width && r.bottom.value <= height,
            "$name is not fully inside the ${width}x$height window: left=${r.left} top=${r.top} right=${r.right} bottom=${r.bottom}",
        )
    }

    private fun buttonsInside(width: Int, height: Int) = runDesktopComposeUiTest(width, height) {
        show(state())
        waitForIdle()
        assertTrue(drawn("publishError"), "the error banner is shown")
        for (tag in listOf("publishButton", "cancelButton")) {
            assertInside(tag, bounds(tag), width, height)
            onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun `at 1280x800 Publish and Cancel are inside the window with 8 properties and a 400-character error`() = buttonsInside(1280, 800)

    @Test
    fun `at 1000x600 Publish and Cancel are inside the window with 8 properties and a 400-character error`() = buttonsInside(1000, 600)

    @Test
    fun `at 1000x600 the body scrolls in its own region and shows a scrollbar on overflow`() = runDesktopComposeUiTest(1000, 600) {
        show(state())
        waitForIdle()
        assertTrue(drawn("publishDialogBody"), "the scrolling body is tagged publishDialogBody")
        assertTrue(drawn("publishDialogBodyScrollbar"), "an overflowing body shows publishDialogBodyScrollbar")
    }

    @Test
    fun `a tall window with few properties and no banner has no scrollbar`() = runDesktopComposeUiTest(1280, 1600) {
        show(state(properties = 0, error = null))
        waitForIdle()
        assertTrue(drawn("publishDialogBody"), "the scrolling body is tagged publishDialogBody")
        assertTrue(!drawn("publishDialogBodyScrollbar"), "no overflow, so no scrollbar")
    }

    @Test
    fun `scrolling the body does not move the buttons or the error banner`() = runDesktopComposeUiTest(1000, 600) {
        show(state())
        waitForIdle()
        val publishBefore = bounds("publishButton")
        val cancelBefore = bounds("cancelButton")
        val errorBefore = bounds("publishError")
        onNodeWithTag("publishDialogBody").performTouchInput { swipeUp() }
        waitForIdle()
        assertEquals(publishBefore, bounds("publishButton"), "Publish moved with the body")
        assertEquals(cancelBefore, bounds("cancelButton"), "Cancel moved with the body")
        assertEquals(errorBefore, bounds("publishError"), "the error banner moved with the body")
        assertInside("publishButton", publishBefore, 1000, 600)
    }

    @Test
    fun `a confirmation keeps the buttons inside the window at 1000x600`() = runDesktopComposeUiTest(1000, 600) {
        show(state(error = null, confirmation = "Message published to stream orders-events."))
        waitForIdle()
        assertTrue(drawn("publishConfirmation"), "the confirmation banner is shown")
        assertInside("publishButton", bounds("publishButton"), 1000, 600)
        assertInside("cancelButton", bounds("cancelButton"), 1000, 600)
    }
}
