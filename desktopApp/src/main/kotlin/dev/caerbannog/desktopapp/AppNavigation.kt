package dev.caerbannog.desktopapp

import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COMPILE STUB (M2-05c step 2, test developer): no behaviour yet; the rules are in `CompositionRootNavigationTest`.
 * Which screen the window shows: Connections or Streams. [open] of a destination with no screen yet (Topology, Migrations) changes nothing.
 */
class AppNavigation(initial: AppDestination = AppDestination.Connections) {
    private val _screen = MutableStateFlow(initial)

    /** The screen on show. */
    val screen: StateFlow<AppDestination> = _screen.asStateFlow()

    fun open(destination: AppDestination) = Unit
}
