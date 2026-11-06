package dev.caerbannog.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * A table-cell text value that can be copied (UI1: exactly what is drawn is copied). Mouse selection works across it, and when the
 * cell has keyboard focus (Tab stop or Click), Cmd+C / Ctrl+C copies its whole text.
 */
@Composable
internal fun CopyableText(
    text: String,
    tag: String,
    style: androidx.compose.ui.text.TextStyle,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = Color.Unspecified,
    extra: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val outline = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    Text(
        text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(extra)
            .onKeyEvent { event ->
                val copy = event.type == KeyEventType.KeyDown && event.key == Key.C && (event.isMetaPressed || event.isCtrlPressed)
                // `ui` has one target (jvm); the desktop ClipEntry wraps a Transferable. Move this when a second target is added.
                if (copy) scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
                copy
            }
            .onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(2.dp, outline), RoundedCornerShape(4.dp))
            // clickable is focusable and opens on Enter; a plain cell only needs the focus.
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier.focusable()),
    )
}
