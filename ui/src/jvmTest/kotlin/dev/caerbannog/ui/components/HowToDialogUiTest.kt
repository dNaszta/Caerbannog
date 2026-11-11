package dev.caerbannog.ui.components

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.help.HowTo
import dev.caerbannog.ui.migrate3x.allText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-14: the "How to" link opens a modal dialog with the title, "You need", the numbered steps and "If it goes wrong"; the close button closes it. Red against the stub. */
@OptIn(ExperimentalTestApi::class)
class HowToDialogUiTest {
    private val howTo = HowTo(
        "demo", "Demo how-to", listOf("1. Demo"), listOf("A thing", "Another thing"),
        listOf("Do the first thing.", "Do the second thing.", "Do the third thing."), listOf("If it fails, read the reason."), listOf("first"),
    )

    private fun DesktopComposeUiTest.nodes(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun `the link is drawn and the dialog is closed until it is clicked`() = runDesktopComposeUiTest {
        setContent { HowToLink(howTo) }
        assertEquals(1, nodes("howto-demo").size, "the link has the tag howto-demo")
        assertTrue(onNodeWithTag("howto-demo", useUnmergedTree = true).fetchSemanticsNode().allText().contains("How to"), "the link says How to")
        assertEquals(0, nodes("howto-dialog-demo").size, "the dialog is not open yet")
    }

    @Test
    fun `clicking the link opens the dialog with the title, the needs, the steps in order and the failure advice`() = runDesktopComposeUiTest {
        setContent { HowToLink(howTo) }
        onNodeWithTag("howto-demo", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(1, nodes("howto-dialog-demo").size, "the dialog is open")
        val dialog = onNodeWithTag("howto-dialog-demo", useUnmergedTree = true).fetchSemanticsNode().allText()
        listOf("Demo how-to", "You need", "A thing", "Another thing", "If it goes wrong", "If it fails, read the reason.").forEach { assertTrue(dialog.contains(it), "the dialog should say \"$it\" but says \"$dialog\"") }
        howTo.steps.forEachIndexed { i, step ->
            assertTrue(onNodeWithTag("howto-step-${i + 1}", useUnmergedTree = true).fetchSemanticsNode().allText().contains(step), "step ${i + 1} is \"$step\"")
        }
        val positions = howTo.steps.map { dialog.indexOf(it) }
        assertEquals(positions.sorted(), positions, "steps are drawn in order")
    }

    @Test
    fun `the close button closes the dialog`() = runDesktopComposeUiTest {
        setContent { HowToLink(howTo) }
        onNodeWithTag("howto-demo", useUnmergedTree = true).performClick(); waitForIdle()
        onNodeWithTag("howto-close", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(0, nodes("howto-dialog-demo").size, "the dialog is closed")
        assertEquals(1, nodes("howto-demo").size, "the link is still there")
    }
}
