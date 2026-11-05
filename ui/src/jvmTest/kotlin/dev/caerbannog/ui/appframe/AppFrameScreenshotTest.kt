package dev.caerbannog.ui.appframe

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.material3.Text
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M2-05b step 2: opt-in screenshot helper (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye check against
 * `docs/ux/Streams.dc.html`: the closed chip (connected, topology locked) and the open popup with its four row states, in the frame.
 * "List with rows" belongs to M2-05c. No pixel comparison; the test asserts only that the file exists. Fakes only.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class AppFrameScreenshotTest {

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private val state = ConnectionControlState(
        chip = ConnectionChipState(
            ChipState.Connected, ChipIcon.Link, "prod-eu", null, PermissionPill("Topology locked", PillTone.LOCKED), null,
            "Connection prod-eu, Connected, topology locked. Open connection menu", false,
        ),
        rows = listOf(
            ConnectionRowState("prod-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true),
            ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false),
            ConnectionRowState("staging-eu", ChipIcon.Error, "Failed: Credentials were refused", RowAction.Retry, current = false),
            ConnectionRowState("local-test", ChipIcon.Sync, "Connecting", RowAction.None, current = false),
        ),
        emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every30, enabled = true, note = "Updated 12 s ago"),
    )

    private val events = object : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    private fun DesktopComposeUiTest.snapshot(fileName: String): File {
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        return file
    }

    @Test
    fun `screenshot - frame with the chip closed`() = runDesktopComposeUiTest(1280, 880) {
        setContent { AppFrame("Streams", state, events) { Text("the screen") } }
        val file = snapshot("appframe-01-chip-closed.png")
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test
    fun `screenshot - popup open`() = runDesktopComposeUiTest(1280, 880) {
        setContent { AppFrame("Streams", state, events) { Text("the screen") } }
        onNodeWithTag("connectionChip").performClick()
        val file = snapshot("appframe-02-popup-open.png")
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    // M2-14a step 4: the "Not encrypted" warning on the chip and in the popup row, for a by-eye check against docs/ux/Streams.dc.html.
    private val plainState = state.copy(
        chip = state.chip.copy(
            accessibleLabel = "Connection prod-eu, Connected, topology locked, Not encrypted. Open connection menu", notEncrypted = true,
        ),
        rows = state.rows.mapIndexed { i, r -> if (i < 2) r.copy(notEncrypted = true) else r },
    )

    @Test
    fun `screenshot - chip closed with Not encrypted`() = runDesktopComposeUiTest(1280, 880) {
        setContent { AppFrame("Streams", plainState, events) { Text("the screen") } }
        val file = snapshot("appframe-03-chip-not-encrypted.png")
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    @Test
    fun `screenshot - popup open with Not encrypted rows`() = runDesktopComposeUiTest(1280, 880) {
        setContent { AppFrame("Streams", plainState, events) { Text("the screen") } }
        onNodeWithTag("connectionChip").performClick()
        val file = snapshot("appframe-04-popup-not-encrypted.png")
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }
}
