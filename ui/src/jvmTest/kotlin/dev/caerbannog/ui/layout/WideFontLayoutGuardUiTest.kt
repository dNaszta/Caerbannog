package dev.caerbannog.ui.layout

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-21 drift guard. The five layout tests that failed on the Linux CI runner (Ubuntu draws DejaVu Sans, the Mac draws Helvetica: the same sample line is 324 px wide
 * against 303 px, measured in M2-21 step 1) are run here again with DejaVu Sans pinned in the test, so they fail on the Mac too and do not depend on the machine's font.
 * A product defect is asserted with the same promise as the original test (RED until the layout is fixed); where the original test encodes a Mac pixel measurement,
 * the guard asserts the promise (the control is reachable) and passes today. Names of the originals are given on every test.
 */
@OptIn(ExperimentalTestApi::class)
class WideFontLayoutGuardUiTest {
    private fun check(run: Run) {
        run.facts.forEach { println("M2-21 MEASURE $it") }
        assertEquals(emptyList(), run.problems, "with DejaVu Sans pinned (the Linux default)")
    }

    /** Original: MigrationsAppBarUiTest "at 1280 px the title, five buttons and the chip share one line, whatever the entry label". */
    @Test fun `at 1280 px the Migrations app bar keeps the five buttons and the chip on one line with a wide font`() = check(WideFontScenarios.appBar(pinned = true))

    /** Original: MigrationsEditStepsUiTest "at font scale 1_5 the four buttons of the action row are all on screen". */
    @Test fun `at font scale 1_5 the four buttons of the Migrations action row are on screen with a wide font`() = check(WideFontScenarios.actionRow(pinned = true))

    /** Original: PlannerEditFileNarrowExploratoryUiTest "at 900 wide and font scale 1_5 ...". */
    @Test fun `at 900 wide and font scale 1_5 the file bar pills and buttons are on screen with a wide font`() = check(WideFontScenarios.editFileBar(pinned = true))

    /** Original: PlannerCheckExploratoryUiTest "at 1280 by 800 a checked small plan ...". Promise asserted: the card is in the window or the page scrolls to it. */
    @Test fun `at 1280 by 800 the review card of a checked plan is in the window or the page scrolls to it with a wide font`() = check(WideFontScenarios.checkedCard(pinned = true))

    /** Original: PlanPairLayoutExploratoryUiTest "the stacked and the side by side layouts ...". Promise asserted: the stacked card is in the window or the page scrolls. */
    @Test fun `at 1025 by 800 the stacked review card is in the window or the page scrolls to it with a wide font`() = check(WideFontScenarios.stackedCard(pinned = true))

    /** Original: MigrationsEditStepsNarrowExploratoryUiTest "at 1000 wide and font scale 1_5 the four buttons are on screen" (finding F2). */
    @Test fun `at 1000 wide and font scale 1_5 the four buttons of the Migrations action row are intact with a wide font`() = check(WideFontScenarios.narrowActionRow(pinned = true))

    /** M2-21b test 7: the Migrations detail panel behind the drawer at 800 and 1000 wide, font scale 1 and 1.5 (brief M2-21b). Red at 800 until the page stacks. */
    @Test fun `at 800 wide and font scale 1 the Migrations detail panel buttons are intact behind the drawer with a wide font`() = check(WideFontScenarios.detailBehindDrawer(pinned = true, width = 800, scale = 1f))
    @Test fun `at 800 wide and font scale 1_5 the Migrations detail panel buttons are intact behind the drawer with a wide font`() = check(WideFontScenarios.detailBehindDrawer(pinned = true, width = 800, scale = 1.5f))
    @Test fun `at 1000 wide and font scale 1 the Migrations detail panel buttons are intact without a drawer with a wide font`() = check(WideFontScenarios.detailBehindDrawer(pinned = true, width = 1000, scale = 1f, drawer = false))
    @Test fun `at 1000 wide and font scale 1_5 the Migrations detail panel buttons are intact without a drawer with a wide font`() = check(WideFontScenarios.detailBehindDrawer(pinned = true, width = 1000, scale = 1.5f, drawer = false))

    /** M2-21b test 7b: the machine's own font at 800 (the other half of the brief: machine font and pinned DejaVu). */
    @Test fun `at 800 wide and font scale 1_5 the Migrations detail panel buttons are intact behind the drawer with the machine font`() =
        WideFontScenarios.detailBehindDrawer(pinned = false, width = 800, scale = 1.5f).let { run ->
            run.facts.forEach { println("M2-21b MEASURE $it") }
            assertEquals(emptyList(), run.problems, "with the machine's own font")
        }
}
