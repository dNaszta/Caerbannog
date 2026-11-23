package dev.caerbannog.ui.migrations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.planner.PlanOpeningDecision
import dev.caerbannog.ui.layout.WithDrawerStandIn
import dev.caerbannog.ui.layout.assertIntact
import dev.caerbannog.ui.planner.node
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FINDING F2 (minor). Test 1 (1000 wide) is fixed by the FlowRow action row (M2-21). Test 2 (800 wide) stays @Disabled, split to M2-21b. It failed before M2-21. Measured at 1000 x 800, font scale 1.5: without Edit steps the row already loses
 * Apply (zero size); with Edit steps (Openable) it loses Validate too and Edit steps is squeezed to 8 px of height. At 1280 wide, scale 1.5 all four fit (MigrationsEditStepsUiTest).
 * M9-12 step 4 (test developer): narrow windows at font scale 1.5 for the action row, and the long names of a refusal. The detail panel scrolls as a whole, so each node is scrolled to first. */
@OptIn(ExperimentalTestApi::class)
@Timeout(60)
class MigrationsEditStepsNarrowExploratoryUiTest {
    private val file = MigrationFile("0002", "d", listOf(MigrationAction.DeclareQueue("orders")), "{}", "abc")
    private fun state(decision: PlanOpeningDecision) = MigrationsScreenState(
        entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = "0002", selectedFile = file, profileName = "uat-eu",
        dryRun = MigrationDryRun("0002", file.up.map { ActionDryRun(it, allowed = true) }, canApply = true), planOpening = decision,
    )

    private fun androidx.compose.ui.test.DesktopComposeUiTest.show(s: MigrationsScreenState, scale: Float, drawer: Boolean = false) {
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { if (drawer) WithDrawerStandIn { MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } else MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } }
        waitForIdle()
    }

    @Test
    fun `at 1000 wide and font scale 1_5 the four buttons are on screen`() = runDesktopComposeUiTest(1000, 800) {
        show(state(PlanOpeningDecision.Openable), 1.5f)
        val tags = listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn")
        tags.forEach { node(it).performScrollTo().assertIsDisplayed() }
        assertIntact(tags, 1000, 800, scroll = true)
    }

    @Test
    fun `at 800 wide and font scale 1_5 the applied text and the copy button are on screen`() = runDesktopComposeUiTest(800, 800) {
        show(
            state(PlanOpeningDecision.NotOffered("0002 is applied on " + "a-very-long-profile-name-".repeat(2).take(60) + ", so it is not edited", "Changing an applied file would mark it as modified there. Copy its steps into a new plan instead.", true)),
            1.5f, drawer = true,
        )
        val tags = listOf("migrations:planNotOffered", "migrations:copyIntoPlanBtn", "migrations:editFileBtn")
        tags.forEach { node(it).performScrollTo().assertIsDisplayed() }
        // M2-21b: the refusal text is not a button, so only the two buttons are held to the button rules; the text must be on screen and wide enough to read.
        assertIntact(listOf("migrations:copyIntoPlanBtn", "migrations:editFileBtn"), 800, 800, scroll = true)
        val text = node("migrations:planNotOffered").fetchSemanticsNode().boundsInRoot
        assertTrue(text.left >= 0f && text.right <= 800f && text.width >= 200f, "the refusal text is $text wide at 800, it must be inside the window and at least 200 px wide to read")
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.refusalKeepsButtonsIntact(width: Int) {
        setContent {
            dev.caerbannog.ui.PinnedFontTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    MigrationsScreen(
                        state(PlanOpeningDecision.NotOffered("0002 is applied on uat-eu, so it is not edited", "Copy its steps into a new plan instead.", true)),
                        {}, {}, {}, {}, {}, { _, _ -> }, {}, {},
                    )
                }
            }
        }
        waitForIdle()
        val tags = listOf("migrations:copyIntoPlanBtn", "migrations:editFileBtn")
        tags.forEach { node(it).performScrollTo().assertIsDisplayed() }
        assertIntact(tags, width, 800, scroll = true)
    }

    /** Window 1000 at font scale 1.5: the page is 720 dp, under the 752 dp threshold of that scale, so it stacks and the refusal buttons have the full width (pinned font). */
    @Test
    fun `at 1000 wide and font scale 1_5 a refusal keeps its buttons intact stacked`() = runDesktopComposeUiTest(1000, 800) { refusalKeepsButtonsIntact(1000) }

    /** Window 1100 at font scale 1.5: the page is 820 dp, side by side, detail 408 dp (pinned font). */
    @Test
    fun `at 1100 wide and font scale 1_5 a refusal keeps its buttons intact side by side`() = runDesktopComposeUiTest(1100, 800) { refusalKeepsButtonsIntact(1100) }

    /** Window 1032 at font scale 1.5: the page is 752 dp, the first width that is side by side, so the detail is at its smallest (340 dp) and the copy button has its tightest fit (pinned font). */
    @Test
    fun `at 1032 wide and font scale 1_5 a refusal keeps its buttons intact side by side at the threshold`() = runDesktopComposeUiTest(1032, 800) { refusalKeepsButtonsIntact(1032) }
}
