package dev.caerbannog.ui.appframe

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState

// M2-05b step 2: COMPILE STUBS ONLY (test developer). Empty composables; the contract is the signatures and the test tags
// listed in ConnectionControlUiTest and AppFrameUiTest. The senior developer builds the real ones.

/** The closed chip: state icon, name (ellipsis when long), optional pill, drop-down arrow. A button, a normal tab stop. */
@Composable
fun ConnectionChip(chip: ConnectionChipState, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
}

/** The popup content: Connections section, Stream list refresh section, Manage connections. [onDismiss] on Escape or a click outside. */
@Composable
fun ConnectionPopup(state: ConnectionControlState, events: ConnectionControlEvents, onDismiss: () -> Unit) {
}

/** The chip with its popup and the open/closed state (Enter, Space or a click opens; Escape closes and returns focus to the chip). */
@Composable
fun ConnectionControl(state: ConnectionControlState, events: ConnectionControlEvents, modifier: Modifier = Modifier) {
}

/**
 * The frame of every screen, including Connections: drawer (brand, search, the four navigation items) and app bar (title, the
 * screen's own [actions], then the fixed [ConnectionControl] last), with the screen's [content] below the bar.
 */
@Composable
fun AppFrame(
    title: String,
    control: ConnectionControlState,
    events: ConnectionControlEvents,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
}
