package dev.caerbannog.ui.streams

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.StreamClientsTexts

private val HintControlSize = 20.dp
internal val HintGap = 6.dp
private val HintPopupGap = 4.dp
internal val HintPopupMaxWidth = 320.dp
internal val HintCornerRadius = 4.dp
internal val HintShadowElevation = 4.dp
internal val HintPopupPadding = 8.dp

/**
 * Places this node to the LEFT of its zero-size slot, top aligned with the slot, without taking layout space (the row does not move).
 * Left and top aligned, not above: for the first row "above" is the table header, and the status column is the last one, so the
 * space to the left is inside the table. Only the placement changes; nothing here clips.
 */
private fun Modifier.floatingBesideLeft(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
    layout(0, 0) { placeable.place(-placeable.width - gap.roundToPx(), 0) }
}

/**
 * A focusable "i" that shows [hint] in an INLINE popup while it has keyboard focus (not a Popup window: that would add a second root).
 * Escape closes the popup and keeps the focus; the popup is drawn beside the "i" with zero layout size so the row does not move.
 */
@Composable
internal fun StatusHintControl(hint: String, rowKey: String) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(HintControlSize)
            .onKeyEvent { event ->
                if (focused && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    dismissed = true
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) dismissed = false
            }
            .testTag("statusHint:$rowKey")
            // No clip here: a clip on the control would cut the inline popup (a child) down to the 20 dp circle. Only the border is round.
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClickLabel = StreamClientsTexts.HINT_CONTROL_LABEL) { dismissed = !dismissed },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (focused && !dismissed) {
            Surface(
                shape = RoundedCornerShape(HintCornerRadius),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = HintShadowElevation,
                modifier = Modifier.floatingBesideLeft(HintPopupGap),
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.widthIn(max = HintPopupMaxWidth).padding(HintPopupPadding).testTag("statusHintPopup:$rowKey"),
                )
            }
        }
    }
}
