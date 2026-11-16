package dev.caerbannog.desktopapp

import dev.caerbannog.core.planner.SavedPlan
import dev.caerbannog.ui.appframe.AppDestination
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-03 step 2: the planner is a sub-screen of the Migrations destination (brief, Scope; owner decision Q11), the way a stream is a sub-screen of
 * Streams: the drawer keeps Migrations selected. Contract (test developer's proposal, to be confirmed by the architect):
 *  - `AppNavigation.plannerOpen: StateFlow<Boolean>`, `openPlanner()` (only from Migrations, else nothing), `closePlanner()`;
 *  - any `open(destination)` closes the planner, Migrations included (the drawer item takes the user back to the list, like Streams);
 *  - `planSaved(saved: SavedPlan)`: closes the planner, shows Migrations and leaves the saved plan in `savedPlan` until `savedPlanHandled()`
 *    (the window selects that version and shows the notice); going to any other screen drops it, like `requestedSource`.
 */
class AppNavigationPlannerTest {
    private fun onMigrations() = AppNavigation(AppDestination.Migrations)
    private val saved = SavedPlan(version = "0005", fileName = "0005.json")

    @Test
    fun `the planner is closed at the start`() {
        assertFalse(AppNavigation().plannerOpen.value)
        assertFalse(onMigrations().plannerOpen.value)
        assertNull(onMigrations().savedPlan.value)
    }

    @Test
    fun `openPlanner on the Migrations screen opens it and the drawer keeps Migrations selected`() {
        val nav = onMigrations()
        nav.openPlanner()
        assertTrue(nav.plannerOpen.value)
        assertEquals(AppDestination.Migrations, nav.screen.value)
    }

    @Test
    fun `openPlanner from any other screen does nothing`() {
        listOf(AppDestination.Connections, AppDestination.Streams, AppDestination.Topology, AppDestination.MigrateFromThree).forEach { screen ->
            val nav = AppNavigation(screen)
            nav.openPlanner()
            assertFalse(nav.plannerOpen.value, "opened from $screen")
            assertEquals(screen, nav.screen.value)
        }
    }

    @Test
    fun `closePlanner goes back to the Migrations list and is harmless twice`() {
        val nav = onMigrations()
        nav.openPlanner()
        nav.closePlanner()
        assertFalse(nav.plannerOpen.value)
        assertEquals(AppDestination.Migrations, nav.screen.value)
        nav.closePlanner()
        assertFalse(nav.plannerOpen.value)
    }

    @Test
    fun `every drawer destination closes the planner, the Migrations item included`() {
        listOf(AppDestination.Connections, AppDestination.Streams, AppDestination.Topology, AppDestination.Migrations, AppDestination.MigrateFromThree).forEach { target ->
            val nav = onMigrations()
            nav.openPlanner()
            nav.open(target)
            assertFalse(nav.plannerOpen.value, "still open after going to $target")
            assertEquals(target, nav.screen.value)
        }
    }

    @Test
    fun `openPlanner twice stays open once`() {
        val nav = onMigrations()
        nav.openPlanner()
        nav.openPlanner()
        assertTrue(nav.plannerOpen.value)
        nav.closePlanner()
        assertFalse(nav.plannerOpen.value)
    }

    @Test
    fun `a saved plan closes the planner, shows Migrations and waits for the screen to take it`() {
        val nav = onMigrations()
        nav.openPlanner()
        nav.planSaved(saved)
        assertFalse(nav.plannerOpen.value)
        assertEquals(AppDestination.Migrations, nav.screen.value)
        assertEquals(saved, nav.savedPlan.value)
        nav.savedPlanHandled()
        assertNull(nav.savedPlan.value)
    }

    @Test
    fun `going to another screen drops a saved plan that nobody took`() {
        val nav = onMigrations()
        nav.openPlanner()
        nav.planSaved(saved)
        nav.open(AppDestination.Topology)
        assertNull(nav.savedPlan.value)
    }

    @Test
    fun `a stream or a 3x request does not leak into the planner and back`() {
        val nav = AppNavigation(AppDestination.Streams)
        nav.openStream("orders-events")
        nav.open(AppDestination.Migrations)
        nav.openPlanner()
        assertTrue(nav.plannerOpen.value)
        assertNull(nav.selectedStream.value)
    }
}
