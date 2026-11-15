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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.StreamClientsTexts
import kotlin.math.roundToInt

private val HintControlSize = 20.dp
internal val HintGap = 6.dp
private val HintPopupGap = 4.dp
internal val HintPopupMaxWidth = 320.dp
internal val HintCornerRadius = 4.dp
internal val HintShadowElevation = 4.dp
internal val HintPopupPadding = 8.dp

private object NoHintViewport : State<Rect?> {
    override val value: Rect? = null
}

/**
 * The area, in root pixels, that an inline hint popup must stay inside: the viewport of the lazy list that clips it. The consumers
 * tables are items of one `LazyColumn`, so the popup of the bottom-most row would be cut by it. Without a provider the popup stays
 * below the "i", as before. A [State] and not a value, so that resizing the window moves no recomposition through the whole list.
 */
internal val LocalHintViewport = staticCompositionLocalOf<State<Rect?>> { NoHintViewport }

/**
 * Places this node to the LEFT of its zero-size slot without taking layout space (the row does not move). The top edge is
 * `top(popupHeight)` below the slot's origin: 0 puts the popup's top on the slot, which is the centre of the "i" (the slot is
 * centred in the control). Left of the "i", not above: for the first row "above" is the table header, and the status column is the
 * last one, so the space to the left is inside the table. Only the placement changes; nothing here clips.
 */
private fun Modifier.floatingBesideLeft(gap: Dp, top: (popupHeight: Int) -> Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
    // Read here, in the measure block, so that a moved row or a resized viewport measures this node again.
    val y = top(placeable.height)
    layout(0, 0) { placeable.place(-placeable.width - gap.roundToPx(), y) }
}

/**
 * A focusable "i" that shows [hint] in an INLINE popup while it has keyboard focus (not a Popup window: that would add a second root).
 * Escape closes the popup and keeps the focus; the popup is drawn beside the "i" with zero layout size so the row does not move.
 * It hangs below the "i"; for the bottom-most rows of a list it goes above, or is clamped, so that the clipping list never cuts it
 * ([hintPopupTop], inside [LocalHintViewport]).
 */
@Composable
internal fun StatusHintControl(hint: String, rowKey: String) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    // The vertical centre of the control in root pixels, where its popup hangs from. Tracked for every "i" (it is a float write, no
    // recomposition) so that it is known before the popup opens and the popup never appears at the wrong place for a frame.
    var anchorY by remember { mutableFloatStateOf(Float.NaN) }
    val viewport = LocalHintViewport.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(HintControlSize)
            .onGloballyPositioned { anchorY = it.positionInRoot().y + it.size.height / 2f }
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
                // Centre, spelled out: the placement below measures from the centre of the control.
                modifier = Modifier.align(Alignment.Center).floatingBesideLeft(HintPopupGap) { popupHeight ->
                    val area = viewport.value
                    if (area == null || anchorY.isNaN()) {
                        0
                    } else {
                        (hintPopupTop(anchorY, popupHeight.toFloat(), area.top, area.bottom) - anchorY).roundToInt()
                    }
                },
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
