package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.ForwardingForm
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ForwardingWarning
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.forwarding.RemoveButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object NoForwardingEvents : ForwardingEvents {
    override fun setQuery(query: String) = Unit
    override fun pick(row: QueueRow) = Unit
    override fun setAmqpHost(host: String) = Unit
    override fun setAmqpPort(port: Int) = Unit
    override fun setAnotherUser(on: Boolean, user: String, password: String) = Unit
    override fun setKeepRunning(on: Boolean) = Unit
    override fun setTyped(text: String) = Unit
    override fun confirm() = Unit
    override fun cancel() = Unit
    override fun remove() = Unit
    override fun close() = Unit
}

/**
 * M8-14b (owner decisions D2, D4): the forwarding view draws its tips and its always visible advice lines (a Shovel MOVES messages, leaving the screen does not stop it,
 * what to do after a failure, the destination must exist) as plain text. Tags `help-tip-fwd-<row>`, `fwd-advice-top|pick|move|leave|failure`, `howto-move-messages`.
 * Red against the stubs (the view draws none of them).
 */
@OptIn(ExperimentalTestApi::class)
class ForwardingHelpUiTest {
    private val facts = DryRunResult(QueueCounts(120, 0), DestinationQueue.Exists(DestinationQueueType.QUORUM), true, setOf("policymaker", "monitoring"), null)
    // Like the real view model: a plain source puts NOT_ENCRYPTED into the lines and the NotEncrypted warning into the warnings.
    private fun confirm(warnings: List<ForwardingWarning> = emptyList()) =
        ForwardingScreenState.Confirm("orders", "new-eu", listOf("From: legacy-eu, virtual host /, queue orders (amqp://svc-reader@legacy.example:5672/%2F)", ForwardingTexts.MOVED) + (if (warnings.any { it is ForwardingWarning.NotEncrypted }) listOf(ForwardingTexts.NOT_ENCRYPTED) else emptyList()), warnings, facts, ForwardingForm("legacy.example", 5672), canConfirm = false)

    private fun DesktopComposeUiTest.show(state: ForwardingScreenState) = setContent { ForwardingView(state, NoForwardingEvents) }

    private fun DesktopComposeUiTest.assertTip(id: String, expected: String) {
        val tag = "help-tip-$id"
        assertDrawn(tag)
        assertEquals(1, nodes(tag).size, "$tag is drawn once")
        assertTrue(expected.isNotBlank() && textOf(tag).contains(expected), "the tip $tag should describe itself as \"$expected\" but says \"${textOf(tag)}\"")
    }

    private fun DesktopComposeUiTest.assertAdvice(tag: String, text: String) {
        assertTrue(text.isNotBlank(), "the advice text for $tag is empty")
        assertTextHas(tag, text)
        assertTrue(onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "$text must be visible text without hovering")
    }

    @Test
    fun `the queue list has the top line, the destination advice and the How to link`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Pick("", listOf(QueueRow("/", "orders"), QueueRow("/", "jobs")), null))
        assertAdvice("fwd-advice-top", ForwardingTexts.ADVICE_TOP)
        assertAdvice("fwd-advice-pick", ForwardingTexts.ADVICE_PICK)
        assertDrawn("howto-move-messages")
        assertDrawn("forward-find")
    }

    @Test
    fun `the confirmation says first that messages are moved and cannot be undone, and tips the host, the user and the typed name`() = runDesktopComposeUiTest(1280, 1000) {
        show(confirm())
        assertAdvice("fwd-advice-move", ForwardingTexts.ADVICE_MOVE)
        assertTextHas("forward-plan", ForwardingTexts.MOVED)
        assertTip("fwd-33", ForwardingTexts.tip("fwd-33")); assertTip("fwd-34", ForwardingTexts.tip("fwd-34")); assertTip("fwd-37", ForwardingTexts.tip("fwd-37"))
        val advice = onNodeWithTag("fwd-advice-move", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lines = onNodeWithTag("forward-plan", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(advice.top <= lines.top, "the advice sits above the confirmation lines: advice ${advice.top}, lines ${lines.top}")
    }

    @Test
    fun `the not encrypted line has its tip only for a plain source`() = runDesktopComposeUiTest(1280, 1000) {
        show(confirm(listOf(ForwardingWarning.NotEncrypted("legacy-eu"))))
        assertTip("fwd-36", ForwardingTexts.tip("fwd-36"))
        assertEquals(1, nodes("help-tip-fwd-36").size, "one tip, the sentence is drawn once")
        assertTrue(textOf("forward-plan").contains(ForwardingTexts.NOT_ENCRYPTED), "the existing Not encrypted line stays")
    }

    @Test
    fun `a source with TLS has no not encrypted tip`() = runDesktopComposeUiTest(1280, 1000) {
        show(confirm())
        assertNotDrawn("help-tip-fwd-36")
    }

    @Test
    fun `progress says that leaving does not stop the shovel, as visible text`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Running(40, 80), listOf(ForwardingTexts.moved(40, 120)), RemoveButton(true, null)))
        assertAdvice("fwd-advice-leave", ForwardingTexts.ADVICE_LEAVE)
        assertNotDrawn("fwd-advice-failure")
    }

    @Test
    fun `progress of a plain source has the not encrypted tip beside its line`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Running(40, 80), listOf(ForwardingTexts.NOT_ENCRYPTED, ForwardingTexts.moved(40, 120)), RemoveButton(true, null)))
        assertTip("fwd-36", ForwardingTexts.tip("fwd-36"))
    }

    @Test
    fun `a failure that was not cleaned up says to use Full control and Remove shovel`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Failed("r", false), listOf(ForwardingTexts.failed("r")), RemoveButton(false, ForwardingTexts.NEEDS_FULL_CONTROL)))
        assertAdvice("fwd-advice-failure", ForwardingTexts.failureAdvice(cleanedUp = false))
        assertEquals(false, textOf("fwd-advice-failure").contains(ForwardingTexts.failureAdvice(true)))
    }

    @Test
    fun `a failure that was cleaned up says to check the AMQP host, port and password`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Failed("r", true), listOf(ForwardingTexts.failed("r"), ForwardingTexts.CLEANED_UP), null))
        assertAdvice("fwd-advice-failure", ForwardingTexts.failureAdvice(cleanedUp = true))
    }

    @Test
    fun `a finished shovel has no failure advice`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Finished, listOf(ForwardingTexts.finishedStayed(20)), null))
        assertNotDrawn("fwd-advice-failure")
    }

    @Test
    fun `a disabled Remove shovel keeps its reason unedited and has the tip that it deletes only the shovel`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Failed("r", false), listOf(ForwardingTexts.failed("r")), RemoveButton(false, ForwardingTexts.NEEDS_FULL_CONTROL)))
        assertEquals(ForwardingTexts.NEEDS_FULL_CONTROL, textOf("forward-remove-reason").trim(), "the existing reason text is not edited")
        assertTip("fwd-41", ForwardingTexts.tip("fwd-41"))
    }

    @Test
    fun `an enabled Remove shovel needs no tip about the disabled reason`() = runDesktopComposeUiTest(1280, 900) {
        show(ForwardingScreenState.Progress("orders", ForwardingState.Running(1, 2), listOf("x"), RemoveButton(true, null)))
        assertDrawn("forward-remove")
    }
}
