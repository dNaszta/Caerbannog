package dev.caerbannog.ui.streams

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import dev.caerbannog.core.streams.ConsumerStatus
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertTrue

/**
 * M2-06 follow-up F-G: the open "i" popup must really be SEEN, not only exist in the semantics tree.
 * assertIsDisplayed and the node's text do not notice a parent that clips the popup away, so these tests also compare the
 * rendered pixels inside the popup bounds with the same region drawn without the popup (a visible popup is a dark filled
 * surface, so most pixels differ), and check the geometry: fully inside the window, inside the consumers table horizontally,
 * not covering the table header, above or beside the "i".
 */
@OptIn(ExperimentalTestApi::class)
class StreamClientsHintPopupUiTest {

    private val behindRows = (0..7).map { i -> consumer("c$i", "c$i", behind = "${100 + i}", status = ConsumerStatus.Behind(100L + i)) }
    private val cases = listOf("first" to "c0", "middle" to "c4", "last" to "c7")

    private fun DesktopComposeUiTest.image(): BufferedImage = onAllNodes(isRoot())[0].captureToImage().toAwtImage()

    private fun bounds(tag: String, ui: DesktopComposeUiTest): Rect = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the open hint popup is really visible, above or beside the i, inside the window and the table, off the header`() =
        runDesktopComposeUiTest(1280, 900) {
            clientsScreen(content(consumers = behindRows))
            waitForIdle()
            val without = image()
            val problems = mutableListOf<String>()
            val window = Rect(0f, 0f, 1280f, 900f)
            cases.forEach { (label, key) ->
                onNodeWithTag("statusHint:$key", useUnmergedTree = true).requestFocus()
                waitForIdle()
                val popup = onNodeWithTag("statusHintPopup:$key", useUnmergedTree = true)
                popup.assertExists("$label row ($key): popup node exists")
                val rect = bounds("statusHintPopup:$key", this)
                val iRect = bounds("statusHint:$key", this)
                val table = bounds("consumersTable", this)
                val shown = runCatching { popup.assertIsDisplayed() }.exceptionOrNull()
                if (shown != null) problems += "$label ($key): not displayed: ${shown.message}"
                if (rect.width < 40f || rect.height < 20f) problems += "$label ($key): popup bounds $rect are too small to hold the hint text"
                if (rect.left < window.left || rect.top < window.top || rect.right > window.right || rect.bottom > window.bottom) problems += "$label ($key): popup $rect leaves the window $window"
                if (rect.left < table.left || rect.right > table.right) problems += "$label ($key): popup $rect leaves the table horizontally $table"
                val above = rect.bottom <= iRect.top + 1f
                val beside = rect.right <= iRect.left + 1f || rect.left >= iRect.right - 1f
                if (!above && !beside) problems += "$label ($key): popup $rect is neither above nor beside the i $iRect"
                onAllNodesWithHeaders().forEach { header ->
                    if (rect.overlaps(header)) problems += "$label ($key): popup $rect covers the table header $header"
                }
                // Rendered pixels: with the popup open, most pixels inside its bounds must differ from the same region without it.
                val withPopup = image()
                var changed = 0
                var total = 0
                val x0 = rect.left.toInt().coerceIn(0, 1279); val x1 = rect.right.toInt().coerceIn(0, 1280)
                val y0 = rect.top.toInt().coerceIn(0, 899); val y1 = rect.bottom.toInt().coerceIn(0, 900)
                for (x in x0 until x1) for (y in y0 until y1) {
                    total++
                    if (withPopup.getRGB(x, y) != without.getRGB(x, y)) changed++
                }
                // Independent of the node bounds (which a clip can shrink to nothing): outside the "i" and its focus ring, the picture
                // must change by more than a ring's worth of pixels once the popup is open (a visible text surface is thousands).
                var changedOutsideI = 0
                for (x in 0 until 1280) for (y in 0 until 900) {
                    val nearI = x >= iRect.left - 6 && x <= iRect.right + 6 && y >= iRect.top - 6 && y <= iRect.bottom + 6
                    if (!nearI && withPopup.getRGB(x, y) != without.getRGB(x, y)) changedOutsideI++
                }
                if (changedOutsideI < 1500) problems += "$label ($key): only $changedOutsideI pixels outside the i changed when the popup opened; nothing visible was drawn"
                val share = if (total == 0) 0.0 else changed.toDouble() / total
                if (share < 0.5) problems += "$label ($key): only ${(share * 100).toInt()}% of the pixels inside the popup bounds $rect differ from the picture without the popup (clipped away or hidden?)"
            }
            assertTrue(problems.isEmpty(), "The hint popup is not usable:\n" + problems.joinToString("\n"))
        }

    private fun DesktopComposeUiTest.onAllNodesWithHeaders(): List<Rect> =
        listOf("Consumer", "Connection", "Offset", "Behind", "Status").mapNotNull { key ->
            runCatching { bounds("consumersHeader:$key", this) }.getOrNull()
        }
}
