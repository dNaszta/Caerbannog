package dev.caerbannog.ui.streams

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import dev.caerbannog.core.streams.ConsumerStatus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M2-06b step 2: opt-in screenshot helper (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye check against
 * `docs/ux/Clients.dc.html`: the tab with the mockup's rows, the notice banner, plugin missing, a settling row, lost connection,
 * stream gone. Files `clients-01..06` in `ui/build/screenshots/`. No pixel comparison; the test asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class StreamClientsScreenshotTest {

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.shot(state: StreamClientsState, fileName: String) {
        clientsScreen(state, nowMillis = 6_000)
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test
    fun `screenshot - tab with rows`() = runDesktopComposeUiTest(1280, 880) { shot(content(updatedAtMillis = 1_000), "clients-01-rows.png") }

    @Test
    fun `screenshot - own connections notice`() = runDesktopComposeUiTest(1280, 880) {
        shot(content(consumers = mockupConsumers.take(1), notice = NOTICE_TEXT, updatedAtMillis = 1_000), "clients-02-notice.png")
    }

    @Test
    fun `screenshot - plugin missing`() = runDesktopComposeUiTest(1280, 880) {
        shot(messageOnly(StreamClientsScreen.PluginMissing, PLUGIN_TEXT, lastOffset = "48299"), "clients-03-plugin-missing.png")
    }

    @Test
    fun `screenshot - settling rows`() = runDesktopComposeUiTest(1280, 880) {
        shot(content(publishers = listOf(publisher(), settlingPublisher), consumers = listOf(consumer(), settlingConsumer), updatedAtMillis = 1_000), "clients-04-settling.png")
    }

    @Test
    fun `screenshot - lost connection`() = runDesktopComposeUiTest(1280, 880) {
        shot(content(stale = true, banner = "Connection lost", updatedAtMillis = 1_000), "clients-05-lost-connection.png")
    }

    @Test
    fun `screenshot - stream gone`() = runDesktopComposeUiTest(1280, 880) {
        shot(messageOnly(StreamClientsScreen.StreamGone, GONE_TEXT), "clients-06-stream-gone.png")
    }

    /** M2-06 F-G: the keyboard hint popup open on the first, a middle and the last Behind row, so a human can look at it. */
    private fun hintShot(key: String, fileName: String) = runDesktopComposeUiTest(1280, 900) {
        val rows = (0..7).map { i -> consumer("c$i", "c$i", behind = "${100 + i}", status = ConsumerStatus.Behind(100L + i)) }
        clientsScreen(content(consumers = rows, updatedAtMillis = 1_000), nowMillis = 6_000)
        waitForIdle()
        onNodeWithTag("statusHint:$key", useUnmergedTree = true).requestFocus()
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test
    fun `screenshot - hint popup on the first Behind row`() = hintShot("c0", "clients-02a-hint-first.png")

    @Test
    fun `screenshot - hint popup on a middle Behind row`() = hintShot("c4", "clients-02b-hint-middle.png")

    @Test
    fun `screenshot - hint popup on the last Behind row`() = hintShot("c7", "clients-02c-hint-last.png")
}
