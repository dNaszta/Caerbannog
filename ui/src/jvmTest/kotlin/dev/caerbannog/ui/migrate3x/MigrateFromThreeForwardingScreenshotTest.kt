package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingForm
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ForwardingWarning
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.RemoveButton
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** M8-09: opt-in screenshots (tag `screenshot`) of the forwarding view for a by-eye comparison with section 14 of `docs/ux/Migrate3x.dc.html`: `migrate3x-forward-01-blocked` to `-06-failed-kept`. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeForwardingScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val facts = DryRunResult(QueueCounts(120, 0), DestinationQueue.Exists(DestinationQueueType.QUORUM), true, setOf("policymaker", "monitoring"), null)

    private object Nothing : ForwardingEvents {
        override fun setQuery(query: String) = Unit
        override fun pick(row: dev.caerbannog.core.forwarding.QueueRow) = Unit
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

    private fun DesktopComposeUiTest.snap(state: ForwardingScreenState, name: String) {
        setContent { ForwardingView(state, Nothing) }
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, name)
        check(ImageIO.write(image, "png", file)) { "no PNG writer for $name" }
        assertTrue(file.length() > 0)
    }

    @Test fun `blocked`() = runDesktopComposeUiTest(1280, 800) {
        snap(ForwardingScreenState.Blocked("orders", "new-eu", listOf(ForwardingBlock.DestinationMissing, ForwardingBlock.ShovelPluginsOff), listOf(ForwardingWarning.NotEncrypted("legacy-eu")), null, null), "migrate3x-forward-01-blocked.png")
    }

    @Test fun `confirmation`() = runDesktopComposeUiTest(1280, 900) {
        val lines = listOf(
            ForwardingTexts.source("legacy-eu", "/", "orders", "amqps://svc-reader@legacy.example:5671/%2F"), ForwardingTexts.destination("new-eu", "/", "orders"),
            ForwardingTexts.MOVED, ForwardingTexts.SOURCE_KEPT, ForwardingTexts.KEEPS_CREDENTIALS, ForwardingTexts.CLEAR_TEXT,
        )
        snap(ForwardingScreenState.Confirm("orders", "new-eu", lines, emptyList(), facts, ForwardingForm("legacy.example", 5671, typed = "ord"), false), "migrate3x-forward-02-confirm.png")
    }

    @Test fun `progress`() = runDesktopComposeUiTest(1280, 800) {
        snap(ForwardingScreenState.Progress("orders", ForwardingState.Running(40, 80), listOf(ForwardingTexts.moved(40, 120)), RemoveButton(false, ForwardingTexts.NEEDS_FULL_CONTROL)), "migrate3x-forward-03-progress.png")
    }

    @Test fun `finished`() = runDesktopComposeUiTest(1280, 800) {
        snap(ForwardingScreenState.Progress("orders", ForwardingState.Finished, listOf(ForwardingTexts.finishedStayed(20)), null), "migrate3x-forward-04-finished.png")
    }

    @Test fun `failed and cleaned up`() = runDesktopComposeUiTest(1280, 800) {
        snap(ForwardingScreenState.Progress("orders", ForwardingState.Failed("failed to connect to source", true), listOf(ForwardingTexts.failed("failed to connect to source"), ForwardingTexts.CLEANED_UP), null), "migrate3x-forward-05-failed-cleaned.png")
    }

    @Test fun `failed and kept`() = runDesktopComposeUiTest(1280, 800) {
        snap(ForwardingScreenState.Progress("orders", ForwardingState.Failed("boom", false), listOf(ForwardingTexts.failed("boom"), ForwardingTexts.notCleanedUp("new-eu")), RemoveButton(true, null)), "migrate3x-forward-06-failed-kept.png")
    }
}
