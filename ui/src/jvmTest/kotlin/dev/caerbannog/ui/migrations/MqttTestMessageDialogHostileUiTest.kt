package dev.caerbannog.ui.migrations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-10b step 4: the sample-value dialog with 20 wildcards, a larger font, unusual text and a very long value. */
@OptIn(ExperimentalTestApi::class)
class MqttTestMessageDialogHostileUiTest {
    private val opened = mutableListOf<List<String>>()

    private fun ComposeUiTest.show(filter: String, fontScale: Float = 1f) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            MqttTestMessageDialog(filter = filter, onOpen = { opened += it }, onCancel = {})
        }
    }

    private fun ComposeUiTest.count(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private val twenty = List(20) { "+" }.joinToString("/")

    @Test
    fun `20 wildcards give 20 fields and Open and Cancel stay inside a 1000x600 window`() = runDesktopComposeUiTest(1000, 600) {
        show(twenty)
        waitForIdle()
        assertEquals(20, (0 until 25).count { count("testMessageValue:$it") == 1 })
        for (tag in listOf("testMessageOpenBtn", "testMessageCancelBtn")) {
            val b = onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(b.bottom.value <= 600 && b.top.value >= 0, "$tag is outside the window: top=${b.top} bottom=${b.bottom}")
        }
    }

    @Test
    fun `with 20 wildcards Enter on a refused value in the last field opens nothing and Enter on good values opens with 20 values`() = runDesktopComposeUiTest(1000, 600) {
        show(twenty)
        waitForIdle()
        onNodeWithTag("testMessageValue:19").performTextReplacement("a.b")
        onNodeWithTag("testMessageValue:19").requestFocus()
        onNodeWithTag("testMessageValue:19").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(opened.isEmpty(), "a refused value opened the dialog")
        onNodeWithTag("testMessageValue:19").performTextReplacement("ok")
        onNodeWithTag("testMessageValue:19").requestFocus()
        onNodeWithTag("testMessageValue:19").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, opened.size)
        assertEquals(20, opened.single().size)
        assertEquals("ok", opened.single().last())
    }

    @Test
    fun `at font scale 1_5 Open and Cancel stay inside a 1000x600 window`() = runDesktopComposeUiTest(1000, 600) {
        show("a/+/b/+/#", fontScale = 1.5f)
        waitForIdle()
        for (tag in listOf("testMessageOpenBtn", "testMessageCancelBtn")) {
            val b = onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(b.bottom.value <= 600 && b.right.value <= 1000, "$tag is outside the window: bottom=${b.bottom} right=${b.right}")
        }
    }

    @Test
    fun `unicode, right-to-left text and spaces are handed over as typed`() = runDesktopComposeUiTest(1000, 600) {
        show("x y/+")
        waitForIdle()
        val value = "مرحبا 日本語"
        onNodeWithTag("testMessageValue:0").performTextReplacement(value)
        waitForIdle()
        assertEquals(0, count("testMessageError:0"), "the dialog refuses nothing here")
        onNodeWithTag("testMessageOpenBtn").performKeyInput { }
        onNodeWithTag("testMessageValue:0").requestFocus()
        onNodeWithTag("testMessageValue:0").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(listOf(value)), opened)
    }
}
