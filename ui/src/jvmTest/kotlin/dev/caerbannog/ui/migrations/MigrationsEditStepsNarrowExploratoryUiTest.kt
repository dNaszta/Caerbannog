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
import dev.caerbannog.ui.layout.assertIntact
import dev.caerbannog.ui.planner.node
import kotlin.test.Test

/**
 * FINDING F2 (minor). Test 1 (1000 wide) is fixed by the FlowRow action row (M2-21). Test 2 (800 wide) stays @Disabled, split to M2-21b. It failed before M2-21. Measured at 1000 x 800, font scale 1.5: without Edit steps the row already loses
 * Apply (zero size); with Edit steps (Openable) it loses Validate too and Edit steps is squeezed to 8 px of height. At 1280 wide, scale 1.5 all four fit (MigrationsEditStepsUiTest).
 * M9-12 step 4 (test developer): narrow windows at font scale 1.5 for the action row, and the long names of a refusal. The detail panel scrolls as a whole, so each node is scrolled to first. */
@OptIn(ExperimentalTestApi::class)
class MigrationsEditStepsNarrowExploratoryUiTest {
    private val file = MigrationFile("0002", "d", listOf(MigrationAction.DeclareQueue("orders")), "{}", "abc")
    private fun state(decision: PlanOpeningDecision) = MigrationsScreenState(
        entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = "0002", selectedFile = file, profileName = "uat-eu",
        dryRun = MigrationDryRun("0002", file.up.map { ActionDryRun(it, allowed = true) }, canApply = true), planOpening = decision,
    )

    private fun androidx.compose.ui.test.DesktopComposeUiTest.show(s: MigrationsScreenState, scale: Float) {
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } }
        waitForIdle()
    }

    @Test
    fun `at 1000 wide and font scale 1_5 the four buttons are on screen`() = runDesktopComposeUiTest(1000, 800) {
        show(state(PlanOpeningDecision.Openable), 1.5f)
        val tags = listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn")
        tags.forEach { node(it).performScrollTo().assertIsDisplayed() }
        assertIntact(tags, 1000, 800, scroll = true)
    }

    @org.junit.jupiter.api.Disabled("F2, split to brief M2-21b: at 800 wide with the drawer and the 340 dp file list the detail panel is about 100 px wide, so no button of it can be intact; needs a layout decision (stack list and detail)")
    @Test
    fun `at 800 wide and font scale 1_5 the applied text and the copy button are on screen`() = runDesktopComposeUiTest(800, 800) {
        show(
            state(PlanOpeningDecision.NotOffered("0002 is applied on " + "a-very-long-profile-name-".repeat(2).take(60) + ", so it is not edited", "Changing an applied file would mark it as modified there. Copy its steps into a new plan instead.", true)),
            1.5f,
        )
        listOf("migrations:planNotOffered", "migrations:copyIntoPlanBtn", "migrations:editFileBtn").forEach { node(it).performScrollTo().assertIsDisplayed() }
    }
}
