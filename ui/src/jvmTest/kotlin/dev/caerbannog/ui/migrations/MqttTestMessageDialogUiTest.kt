package dev.caerbannog.ui.migrations

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10b step 2: the small sample-value dialog (no broker access). One field per wildcard of the filter, in order; labels `Value for the + in level N`
 * and `Value for the rest of the topic`; start values `item` and `test`; refusals live under the field; Open, Enter and Esc. Tags: `testMessageDialog`,
 * `testMessageValue:<n>`, `testMessageError:<n>` (0-based), `testMessageOpenBtn`, `testMessageCancelBtn`.
 */
@OptIn(ExperimentalTestApi::class)
class MqttTestMessageDialogUiTest {
    private class Calls {
        val opened = mutableListOf<List<String>>()
        var cancelled = 0
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(filter: String, calls: Calls) = setContent {
        MqttTestMessageDialog(filter = filter, onOpen = { calls.opened += it }, onCancel = { calls.cancelled++ })
    }

    private fun SemanticsNode.allText(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.allText() }).joinToString(" ")

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String) =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().allText()

    private fun androidx.compose.ui.test.ComposeUiTest.valueOf(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.count(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `one plus gives one field with the label of its level and the start value item`() = runDesktopComposeUiTest(1280, 800) {
        show("devices/+/answer", Calls())
        assertEquals(1, count("testMessageValue:0"))
        assertEquals(0, count("testMessageValue:1"))
        assertTrue(textOf("testMessageValue:0").contains("Value for the + in level 2"), textOf("testMessageValue:0"))
        assertEquals("item", valueOf("testMessageValue:0"))
        assertEquals(0, count("testMessageError:0"), "a start value is valid, so no error is drawn")
        assertTrue(textOf("testMessageDialog").contains("Send a test message"), textOf("testMessageDialog"))
        assertTrue(textOf("testMessageOpenBtn").contains("Open the dialog"))
        assertTrue(textOf("testMessageCancelBtn").contains("Cancel"))
    }

    @Test
    fun `a hash gives a field for the rest of the topic that starts as test`() = runDesktopComposeUiTest(1280, 800) {
        show("sensors/#", Calls())
        assertTrue(textOf("testMessageValue:0").contains("Value for the rest of the topic"), textOf("testMessageValue:0"))
        assertEquals("test", valueOf("testMessageValue:0"))
    }

    @Test
    fun `two pluses and a hash give three fields in the order of the filter`() = runDesktopComposeUiTest(1280, 900) {
        show("a/+/b/+/#", Calls())
        assertEquals(3, (0..3).count { count("testMessageValue:$it") == 1 })
        assertTrue(textOf("testMessageValue:0").contains("Value for the + in level 2"))
        assertTrue(textOf("testMessageValue:1").contains("Value for the + in level 4"))
        assertTrue(textOf("testMessageValue:2").contains("Value for the rest of the topic"))
        assertEquals(listOf("item", "item", "test"), (0..2).map { valueOf("testMessageValue:$it") })
    }

    @Test
    fun `Open hands the values over in order`() = runDesktopComposeUiTest(1280, 900) {
        val calls = Calls().also { show("a/+/b/#", it) }
        onNodeWithTag("testMessageValue:0").performTextReplacement("d42")
        onNodeWithTag("testMessageValue:1").performTextReplacement("room1/temp")
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        assertEquals(listOf(listOf("d42", "room1/temp")), calls.opened)
    }

    @Test
    fun `every refusal shows its text under its field and Open hands nothing over`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls().also { show("devices/+/answer", it) }
        val refusals = listOf(
            "" to "Enter a value",
            "d/42" to "One level only: no /",
            "d+42" to "No + or # in a value",
            "d#42" to "No + or # in a value",
            "d.42" to "A level cannot contain a dot: RabbitMQ would read it as another level",
            "d\u0000" to "No control characters in a value",
        )
        for ((value, message) in refusals) {
            onNodeWithTag("testMessageValue:0").performTextReplacement(value)
            waitForIdle()
            assertEquals(message, textOf("testMessageError:0"), "value \"${value.replace("\u0000", "NUL")}\"")
            onNodeWithTag("testMessageOpenBtn").performClick()
            waitForIdle()
            assertTrue(calls.opened.isEmpty(), "Open handed over a refused value \"$value\"")
        }
        onNodeWithTag("testMessageValue:0").performTextReplacement("d42")
        waitForIdle()
        assertEquals(0, count("testMessageError:0"), "the error goes away with a good value")
    }

    @Test
    fun `an error is under the field it belongs to`() = runDesktopComposeUiTest(1280, 900) {
        show("a/+/b/+", Calls())
        onNodeWithTag("testMessageValue:1").performTextReplacement("x/y")
        waitForIdle()
        assertEquals(0, count("testMessageError:0"))
        assertEquals("One level only: no /", textOf("testMessageError:1"))
    }

    @Test
    fun `a value of 10000 characters is accepted by the dialog`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls().also { show("sensors/#", it) }
        val long = "x".repeat(10_000)
        onNodeWithTag("testMessageValue:0").performTextReplacement(long)
        waitForIdle()
        assertEquals(0, count("testMessageError:0"))
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        assertEquals(listOf(listOf(long)), calls.opened)
    }

    @Test
    fun `Enter in a field opens and Escape cancels`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls().also { show("devices/+/answer", it) }
        onNodeWithTag("testMessageValue:0").performTextReplacement("d42")
        onNodeWithTag("testMessageValue:0").requestFocus()
        onNodeWithTag("testMessageValue:0").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(listOf("d42")), calls.opened, "Enter")
        onNodeWithTag("testMessageValue:0").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(1, calls.cancelled, "Escape")
    }

    @Test
    fun `Enter on a refused value opens nothing`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls().also { show("devices/+/answer", it) }
        onNodeWithTag("testMessageValue:0").performTextReplacement("d.42")
        onNodeWithTag("testMessageValue:0").requestFocus()
        onNodeWithTag("testMessageValue:0").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(calls.opened.isEmpty())
    }

    @Test
    fun `Cancel cancels and opens nothing`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls().also { show("devices/+/answer", it) }
        onNodeWithTag("testMessageOpenBtn").assertIsEnabled()
        onNodeWithTag("testMessageCancelBtn").performClick()
        waitForIdle()
        assertEquals(1, calls.cancelled)
        assertTrue(calls.opened.isEmpty())
    }
}
