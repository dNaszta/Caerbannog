package dev.caerbannog.ui.migrate3x

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionFeature
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.ui.appframe.AppFrame
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * M8-06c: opt-in screenshots (tag `screenshot`, `./gradlew :ui:screenshotTest`) for a by-eye comparison with `docs/ux/Migrate3x.dc.html` (notes of 2026-11-10)
 * and `docs/ux/Main.dc.html`, files in `ui/build/screenshots/`: `migrate3x-live-01-picker-disabled`, `-02-picker-enabled`, `-03-picker-live-selected`, `-04-loading-live`,
 * `-05-report-locked`, `-06-report-disconnected`, `-07-cannot-export`, `-08-unreachable`, `-09-failed`, `-10-drawer-note`, `-11-popup-row`. No pixel comparison; each test
 * asserts only that the file exists.
 */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeLiveScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")
    private val liveOffered = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live("legacy-eu")))
    private val liveDisabled = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE)))

    private fun DesktopComposeUiTest.snap(fileName: String) {
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        assertTrue(file.exists() && file.length() > 0, "expected ${file.path}")
    }

    private fun report(connected: String?): MigrateFromThreeState.Report {
        val file = MigrateScreenFiles.held()
        val held = HeldAnalysis("legacy-eu", file.document, file.report, SourceKind.Live)
        return MigrateFromThreeState.Report(ReportViewBuilder.build(held, liveConnectionName = connected), null, ReviewState.NONE)
    }

    @Test fun `screenshot - picker with the live option disabled`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.ChooseSource, picker = liveDisabled); snap("migrate3x-live-01-picker-disabled.png") }
    @Test fun `screenshot - picker with the live option enabled`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.ChooseSource, picker = liveOffered); snap("migrate3x-live-02-picker-enabled.png") }
    @Test fun `screenshot - picker with live selected`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.ChooseSource, picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-03-picker-live-selected.png") }
    @Test fun `screenshot - loading live`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.LoadingLive("legacy-eu"), picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-04-loading-live.png") }
    @Test fun `screenshot - locked report`() = runDesktopComposeUiTest(1280, 880) { show(report("legacy-eu"), picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-05-report-locked.png") }
    @Test fun `screenshot - locked report after disconnect`() = runDesktopComposeUiTest(1280, 880) { show(report(null), picker = liveDisabled); snap("migrate3x-live-06-report-disconnected.png") }
    @Test fun `screenshot - cannot export`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.CannotExport("legacy-eu", LiveSourceTexts.CANNOT_EXPORT), picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-07-cannot-export.png") }
    @Test fun `screenshot - unreachable`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.LiveFailed("legacy-eu", LiveSourceTexts.unreachable("legacy-eu")), picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-08-unreachable.png") }
    @Test fun `screenshot - failed`() = runDesktopComposeUiTest(1280, 800) { show(MigrateFromThreeState.LiveFailed("legacy-eu", LiveSourceTexts.READ_FAILED), picker = liveOffered.copy(selected = SourceKind.Live)); snap("migrate3x-live-09-failed.png") }

    private val chip = ConnectionChipState(
        ChipState.Connected, ChipIcon.Link, "legacy-eu", null, PermissionPill(ConnectionNotes.MIGRATION_SOURCE_PILL, PillTone.WARNING), null,
        "Connection legacy-eu, Connected, migration source. Open connection menu", false,
    )
    private val control = ConnectionControlState(
        chip,
        listOf(
            ConnectionRowState("legacy-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true, offersMigration = true),
            ConnectionRowState("new-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false),
        ),
        null, RefreshSectionState(RefreshInterval.Every30, enabled = true, note = "Updated 12 s ago"),
    )
    private val events = object : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    @Test
    fun `screenshot - drawer with the one note while a 3x broker is connected`() = runDesktopComposeUiTest(1280, 880) {
        setContent {
            AppFrame(
                "Migrate from 3.x", control, events,
                features = setOf(ConnectionFeature.MigrateFromThreeX), featuresDisabledReason = ConnectionNotes.MIGRATION_SOURCE_PILL, activeItem = "MigrateFromThree",
            ) { Text("the screen") }
        }
        snap("migrate3x-live-10-drawer-note.png")
    }

    @Test
    fun `screenshot - popup row with Migrate from 3x`() = runDesktopComposeUiTest(1280, 880) {
        setContent { AppFrame("Streams", control, events) { Text("the screen") } }
        onNodeWithTag("connectionChip").performClick()
        snap("migrate3x-live-11-popup-row.png")
    }
}
