package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.layout.assertIntact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-12 step 4 (test developer): the file-mode bar on a narrow window at font scale 1.5. */
@OptIn(ExperimentalTestApi::class)
class PlannerEditFileNarrowExploratoryUiTest {
    @Test
    fun `at 900 wide and font scale 1_5 the file bar, the pills and the buttons are on screen`() = runDesktopComposeUiTest(900, 800) {
        val raw = EditFileUiFixtures.rawOf(EditFileUiFixtures.threeSteps)
        val r = UiRig(mode = PlannerMode.EditFile("0005", MigrationParser.computeChecksum(raw)), rawFiles = listOf(raw))
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { PlannerOver(r) } }
        waitForIdle()
        r.queue("invoices")
        waitForIdle()
        listOf("planner:saveBtn", "planner:discardBtn", "planner:editPill", "planner:unsavedPill").forEach { node(it).assertIsDisplayed() }
        // isDisplayed passes a squeezed button; assertIntact is the promise (M2-21): one label line, not narrower than the label, inside the window, no overlap.
        assertIntact(listOf("planner:saveBtn", "planner:discardBtn"), 900, 800)
        assertIntact(listOf("planner:editPill", "planner:unsavedPill"), 900, 800, minWidthPx = 0f)
        r.close()
    }
}
