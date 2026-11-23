package dev.caerbannog.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.ui.PinnedFontTheme
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.planner.node
import org.junit.jupiter.api.Timeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M2-21b (owner decision A): below a measured content width the Migrations page stacks, file list on top (capped height, scrolls inside), detail below at full width.
 * The screen draws its own 280 dp drawer (see DrawerStandIn.kt, a no-op wrapper), with the pinned font.
 */
@OptIn(ExperimentalTestApi::class)
@Timeout(60)
class MigrationsStackedLayoutUiTest {
    private fun file(v: String) = MigrationFile(v, "d", listOf(MigrationAction.DeclareQueue("q$v")), "{}", "abc$v")
    private val files = (1..30).map { file("%04d".format(it)) }
    private val selected = files[1]
    private val state = MigrationsScreenState(
        entries = files.map { MigrationEntry(it, MigrationStatus.Pending) }, selectedVersion = selected.version, selectedFile = selected, profileName = "uat-eu",
        dryRun = MigrationDryRun(selected.version, selected.up.map { ActionDryRun(it, allowed = true) }, canApply = true),
    )

    private fun DesktopComposeUiTest.show(drawer: Boolean = true) {
        setContent { PinnedFontTheme { if (drawer) WithDrawerStandIn { MigrationsScreen(state, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } else MigrationsScreen(state, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `at 1280 wide the file list is left of the detail panel as before`() = runDesktopComposeUiTest(1280, 800) {
        show()
        val card = bounds("migrationCard:0001"); val detail = bounds("migrationDetail:header")
        assertTrue(card.right <= detail.left + 0.5f, "side by side expected at 1280: list card $card must end left of the detail header $detail")
        assertTrue(card.top < 400f && detail.top < 400f, "both start at the top of the page at 1280: card $card, detail $detail")
    }

    /** The 1000 window: the screen gets 720 dp (1000 minus the 280 dp drawer of the app frame), over the 672 dp threshold, so side by side (architect decision 1). */
    @Test
    fun `at 1000 wide the file list is left of the detail panel as before`() = runDesktopComposeUiTest(1000, 800) {
        show()
        val card = bounds("migrationCard:0001"); val detail = bounds("migrationDetail:header")
        assertTrue(card.right <= detail.left + 0.5f, "side by side expected at 1000 (brief: unchanged): list card $card must end left of the detail header $detail")
    }

    @Test
    fun `at 800 wide the page stacks with the detail panel under the list and as wide as the content`() = runDesktopComposeUiTest(800, 800) {
        show()
        val card = bounds("migrationCard:0001"); val header = bounds("migrationDetail:header")
        assertTrue(header.top >= card.bottom - 0.5f, "stacked expected at 800: the detail header $header must start below the list card $card")
        // The header is a text of its own width, so the width is read from the panel (tag added in M2-21b), not from the header.
        val detail = bounds("migrationDetail:panel")
        val content = 800f - DRAWER_WIDTH_DP
        assertTrue(detail.width >= content - 2 * 24f - 1f - 40f, "the detail panel is ${detail.width} px wide at 800, about the content width ($content minus the page padding) was expected")
    }

    /** ASSUMED cap: with 30 files the detail header starts in the upper 75 percent of the window (top <= 600 of 800), so the detail panel stays reachable. */
    @Test
    fun `at 800 wide with 30 files the list height is capped so the detail panel starts on screen`() = runDesktopComposeUiTest(800, 800) {
        show()
        val detail = bounds("migrationDetail:header")
        assertTrue(detail.top >= bounds("migrationCard:0001").bottom - 0.5f, "stacked expected at 800 (list above detail), the header is at ${detail.top}")
        assertTrue(detail.top <= 600f, "the stacked list must be capped: the detail header starts at ${detail.top} px of 800 (ASSUMED limit 600)")
    }

    @Test
    fun `at 800 wide the last file of the stacked list is reachable by scrolling the list`() = runDesktopComposeUiTest(800, 800) {
        show()
        node("migrationCard:0030").performScrollTo().assertIsDisplayed()
        assertTrue(node("migrations:editFileBtn").fetchSemanticsNode().boundsInRoot.width > 0f, "the detail panel is still there")
    }

    /** Screen width = window minus the 280 dp drawer: window 951 gives 671 dp (stacked), 952 gives 672 dp (side by side), the threshold of the brief. */
    @Test
    fun `at window 951 the page is stacked and at 952 it is side by side`() {
        runDesktopComposeUiTest(951, 800) {
            show()
            assertTrue(bounds("migrationDetail:header").top >= bounds("migrationCard:0001").bottom - 0.5f, "stacked expected at window 951 (screen 671 dp)")
        }
        runDesktopComposeUiTest(952, 800) {
            show()
            assertTrue(bounds("migrationCard:0001").right <= bounds("migrationDetail:header").left + 0.5f, "side by side expected at window 952 (screen 672 dp)")
        }
    }

    /** The brief promises the list keeps its scroll across the switch; the scroll state is hoisted above it. */
    @Test
    fun `the list keeps its scroll offset when the window widens past 672 dp and narrows again`() = runDesktopComposeUiTest(800, 800) {
        var width by mutableStateOf(800.dp)
        setContent { PinnedFontTheme { Box(Modifier.width(width).height(800.dp)) { MigrationsScreen(state, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } } }
        waitForIdle()
        node("migrationCard:0030").performScrollTo()
        val before = node("migrationCard:0030").fetchSemanticsNode().positionInRoot.y
        assertTrue(before < 600f, "the last card must be scrolled into the list before the switch: card 0030 y $before")
        width = 952.dp; waitForIdle()
        width = 800.dp; waitForIdle()
        val after = node("migrationCard:0030").fetchSemanticsNode().positionInRoot.y
        assertTrue(abs(after - before) < 1f, "the list scroll was lost by crossing 672 dp: card 0030 at y $before before, $after after")
    }
}
