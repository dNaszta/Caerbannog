package dev.caerbannog.desktopapp

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

    fun open(destination: AppDestination) {
        if (destination !in WITH_SCREEN) return
        _initialOffset.value = null
        // Streams or any other drawer item with a screen leads to a list or screen, never back into an old stream.
        _selectedStream.value = null
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
