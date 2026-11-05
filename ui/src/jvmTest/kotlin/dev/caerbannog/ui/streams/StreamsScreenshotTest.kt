package dev.caerbannog.ui.streams

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.streams.StreamListState
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M2-05c step 2: opt-in screenshot helper (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye check against
 * `docs/ux/Streams.dc.html`: the list with rows (the mockup's streams), the lost-connection banner over dimmed rows, an empty broker,
 * denied, loading. Files `streams-01..05` in `ui/build/screenshots/`. No pixel comparison; the test asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class StreamsScreenshotTest {

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private val mockupRows = listOf(
        row("orders-events", messages = "48,300", first = "0", last = "48299", retention = "7D", publishers = "2", consumers = "3"),
        row("audit-log", messages = "1,204,000", first = "880000", last = "2083999", retention = "30D", publishers = "1", consumers = "1"),
        row("payments-stream", messages = "7,850", first = "0", last = "7849", retention = "12h", publishers = "1", consumers = "0"),
        row("slow-first-offset", messages = "100", first = "loading...", last = "loading...", retention = "7D", publishers = "unavailable", consumers = "0"),
    )

    private fun DesktopComposeUiTest.shot(state: StreamListState, fileName: String) {
        screen(state, nowMillis = 13_000)
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test
    fun `screenshot - list with rows`() = runDesktopComposeUiTest(1280, 880) {
        shot(rowsState(mockupRows, totalStreams = 12, hasNextPage = true, updatedAtMillis = 1_000), "streams-01-rows.png")
    }

    @Test
    fun `screenshot - lost connection`() = runDesktopComposeUiTest(1280, 880) {
        shot(rowsState(mockupRows, stale = true, banner = "Connection lost", action = "Retry", updatedAtMillis = 1_000), "streams-02-lost-connection.png")
    }

    @Test
    fun `screenshot - empty broker`() = runDesktopComposeUiTest(1280, 880) { shot(empty, "streams-03-empty.png") }

    @Test
    fun `screenshot - denied`() = runDesktopComposeUiTest(1280, 880) { shot(denied, "streams-04-denied.png") }

    @Test
    fun `screenshot - loading`() = runDesktopComposeUiTest(1280, 880) { shot(loading, "streams-05-loading.png") }
}
