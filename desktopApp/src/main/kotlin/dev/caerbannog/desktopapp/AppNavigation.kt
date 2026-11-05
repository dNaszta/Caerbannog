package dev.caerbannog.desktopapp

import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which screen the window shows. Only Connections and Streams have a screen (M2-05c); [open] of a destination without one
 * (Topology, Migrations) changes nothing, so the drawer never leads to a blank page.
 */
class AppNavigation(initial: AppDestination = AppDestination.Connections) {
    private val _screen = MutableStateFlow(initial)

    /** The screen on show. */
    val screen: StateFlow<AppDestination> = _screen.asStateFlow()

    private val _selectedStream = MutableStateFlow<String?>(null)

    /** COMPILE STUB (M2-06a step 2): the stream whose screen is open (Streams only), null for the list. Never set yet. */
    val selectedStream: StateFlow<String?> = _selectedStream.asStateFlow()

    /** COMPILE STUB: no behaviour yet. */
    fun openStream(name: String) = Unit

    /** COMPILE STUB: no behaviour yet. */
    fun closeStream() = Unit

    fun open(destination: AppDestination) {
        if (destination in WITH_SCREEN) _screen.value = destination
    }

    private companion object {
        val WITH_SCREEN = setOf(AppDestination.Connections, AppDestination.Streams)
    }
}
