package dev.caerbannog.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14: `HelpTip` is an info icon with the text as tooltip, as accessible description, reachable by keyboard, and a visible line on click. Red against the stub (nothing is drawn). */
@OptIn(ExperimentalTestApi::class)
class HelpTipUiTest {
    private val text = "Reads the connected 3.x broker with GET requests only."

    private fun DesktopComposeUiTest.show() = setContent { Column { Text("anchor"); HelpTip("t1", text) } }
    private fun DesktopComposeUiTest.visibleLines() = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the tip draws an icon with the stable tag and the text as its accessible description`() = runDesktopComposeUiTest {
        show()
        val nodes = onAllNodesWithTag("help-tip-t1", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, nodes.size, "one node with the tag help-tip-t1")
        val description = nodes.single().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: nodes.single().children.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }.firstOrNull()
        assertEquals(text, description, "the icon's content description is the tip text")
    }

    @Test
    fun `the text is not drawn until the tip is clicked, a click shows it as a visible line and a second click hides it`() = runDesktopComposeUiTest {
        show()
        assertEquals(0, visibleLines(), "the text must not be visible before the click")
        onNodeWithTag("help-tip-t1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(1, onAllNodesWithTag("help-tip-t1-line", useUnmergedTree = true).fetchSemanticsNodes().size, "the visible line has the tag help-tip-t1-line")
        assertTrue(visibleLines() >= 1, "the text is displayed as a line")
        onNodeWithTag("help-tip-t1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(0, visibleLines(), "a second click hides the line")
    }

    @Test
    fun `the tip can be reached and opened with the keyboard`() = runDesktopComposeUiTest {
        show()
        onNodeWithTag("help-tip-t1", useUnmergedTree = true).requestFocus()
        onNodeWithTag("help-tip-t1", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }; waitForIdle()
        assertTrue(visibleLines() >= 1, "Enter on the focused tip shows the text")
    }

    @Test
    fun `hovering the icon shows the text as a tooltip`() = runDesktopComposeUiTest {
        show()
        assertEquals(0, visibleLines())
        onNodeWithTag("help-tip-t1", useUnmergedTree = true).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_500); waitForIdle()
        assertTrue(visibleLines() >= 1, "the tooltip text is shown after the hover delay")
    }
}
