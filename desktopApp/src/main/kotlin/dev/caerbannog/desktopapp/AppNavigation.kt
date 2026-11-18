package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.SavedPlan
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which screen the window shows. Every destination has a screen now (Migrate from 3.x came with M8-06a); [open] of a destination without one
 * changes nothing, so the drawer never leads to a blank page and an open stream stays.
 */
class AppNavigation(initial: AppDestination = AppDestination.Connections) {
    private val _screen = MutableStateFlow(initial)

    /** The screen on show. */
    val screen: StateFlow<AppDestination> = _screen.asStateFlow()

    private val _selectedStream = MutableStateFlow<String?>(null)

    /** The stream whose screen is open (Streams only), null for the list. One level, no back stack: only one detail exists. */
    val selectedStream: StateFlow<String?> = _selectedStream.asStateFlow()

    private val _initialOffset = MutableStateFlow<Long?>(null)

    /** Optional offset to jump to when opening the stream browser. */
    val initialOffset: StateFlow<Long?> = _initialOffset.asStateFlow()

    /** Opens the screen of [name]. Only from Streams and only for a non-blank name; anything else does nothing. */
    fun openStream(name: String, offset: Long? = null) {
        if (_screen.value == AppDestination.Streams && name.isNotBlank()) {
            _initialOffset.value = offset
            _selectedStream.value = name
        }
    }

    /** Back to the list; does nothing when no stream is open. */
    fun closeStream() {
        _initialOffset.value = null
        _selectedStream.value = null
    }

    private val _requestedSource = MutableStateFlow<SourceKind?>(null)

    /** The source the Migrate from 3.x screen is asked to select (set by the connection popup), null when nothing is pending. */
    val requestedSource: StateFlow<SourceKind?> = _requestedSource.asStateFlow()

    /** Opens Migrate from 3.x and asks it to select [source]. */
    fun openMigrateFromThree(source: SourceKind) {
        open(AppDestination.MigrateFromThree)
        _requestedSource.value = source
    }

    /** The screen took the request. */
    fun sourceRequestHandled() {
        _requestedSource.value = null
    }

    private val _plannerOpen = MutableStateFlow(false)

    /** True while the planner is shown. It is a sub-screen of Migrations, the way a stream is of Streams: the drawer keeps Migrations selected. */
    val plannerOpen: StateFlow<Boolean> = _plannerOpen.asStateFlow()

    private val _savedPlan = MutableStateFlow<SavedPlan?>(null)

    /** The plan that was just saved as a migration, waiting for the Migrations screen to select it and show its notice; null when none. */
    val savedPlan: StateFlow<SavedPlan?> = _savedPlan.asStateFlow()

    private val _plannerMode = MutableStateFlow<PlannerMode>(PlannerMode.NewPlan)

    /** What the open planner works on: the draft of a new plan, or a pending file chosen with "Edit steps" (M9-12). */
    val plannerMode: StateFlow<PlannerMode> = _plannerMode.asStateFlow()

    /** Opens the planner on [mode]. Only from Migrations; anything else does nothing. */
    fun openPlanner(mode: PlannerMode = PlannerMode.NewPlan) {
        if (_screen.value != AppDestination.Migrations) return
        _plannerMode.value = mode
        _plannerOpen.value = true
    }

    /** Back to the Migrations list; harmless when the planner is not open. */
    fun closePlanner() {
        _plannerOpen.value = false
        _plannerMode.value = PlannerMode.NewPlan
    }

    /** The planner saved [saved]: it closes and Migrations is shown, with the new version waiting in [savedPlan]. */
    fun planSaved(saved: SavedPlan) {
        _plannerOpen.value = false
        _plannerMode.value = PlannerMode.NewPlan
        _screen.value = AppDestination.Migrations
        _savedPlan.value = saved
    }

    /** The Migrations screen took the saved plan. */
    fun savedPlanHandled() {
        _savedPlan.value = null
    }

    fun open(destination: AppDestination) {
        if (destination !in WITH_SCREEN) return
        // Any drawer item leads to a list or screen, never back into an open planner; a saved plan nobody took belongs to the save that made it.
        _plannerOpen.value = false
        _plannerMode.value = PlannerMode.NewPlan
        if (destination != AppDestination.Migrations) _savedPlan.value = null
        _initialOffset.value = null
        // Streams or any other drawer item with a screen leads to a list or screen, never back into an old stream.
        _selectedStream.value = null
        // A request belongs to the open that made it: going anywhere else drops it.
        if (destination != AppDestination.MigrateFromThree) _requestedSource.value = null
        _screen.value = destination
    }

    private companion object {
        val WITH_SCREEN = setOf(
            AppDestination.Connections,
            AppDestination.Streams,
            AppDestination.Topology,
            AppDestination.Migrations,
            AppDestination.MigrateFromThree,
        )
    }
}
