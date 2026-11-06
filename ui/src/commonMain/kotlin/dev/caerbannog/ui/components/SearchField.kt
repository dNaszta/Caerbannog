package dev.caerbannog.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * Shared search field for table and list screens (Streams, Topology).
 * It keeps its own text so typing is never lost while the view model's state arrives a moment later;
 * the state's filter/search wins when it differs.
 */
@Composable
internal fun SearchField(
    value: String,
    placeholder: String,
    tag: String,
    onSearch: (String) -> Unit,
    minWidth: Dp = 280.dp,
    maxWidth: Dp = 480.dp,
) {
    var field by remember(tag) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value, tag) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    BasicTextField(
        value = field,
        onValueChange = {
            val changed = it.text != field.text
            field = it
            if (changed) onSearch(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag(tag).widthIn(min = minWidth, max = maxWidth),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionIcon(AppIcon.Search, SmallIconSize)
                Box(Modifier.weight(1f)) {
                    if (field.text.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        },
    )
}
