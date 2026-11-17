package dev.caerbannog.ui.migrations

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.MqttTestMessageTexts
import dev.caerbannog.core.planner.TestMessageSampleState

/**
 * The small dialog of "Send a test message": one value for each wildcard of [filter], then Open. It knows nothing of the broker and publishes
 * nothing; [onOpen] gets the values in slot order, only when none is refused. Enter opens, Esc cancels. The host draws it in a window.
 */
@Composable
fun MqttTestMessageDialog(
    filter: String,
    onOpen: (List<String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sample by remember(filter) { mutableStateOf(TestMessageSampleState.start(filter)) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(filter) { firstField.requestFocus() }
    val open = { if (sample.canOpen) onOpen(sample.values) }

    Surface(
        modifier = modifier
            .widthIn(min = 360.dp, max = 520.dp)
            .semantics { paneTitle = MqttTestMessageTexts.DIALOG_TITLE }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { open(); true }
                    Key.Escape -> { onCancel(); true }
                    else -> false
                }
            }
            .testTag("testMessageDialog"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = MqttTestMessageTexts.DIALOG_TITLE, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = filter,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val scroll = rememberScrollState()
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sample.slots.indices.forEach { field ->
                        val error = sample.errorFor(field)
                        OutlinedTextField(
                            value = sample.values[field],
                            onValueChange = { sample = sample.withValue(field, it) },
                            label = { Text(sample.labelFor(field)) },
                            placeholder = { Text(MqttTestMessageTexts.VALUE_HINT) },
                            isError = error != null,
                            supportingText = error?.let { message ->
                                { Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("testMessageError:$field")) }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (field == 0) Modifier.focusRequester(firstField) else Modifier)
                                .testTag("testMessageValue:$field"),
                        )
                    }
                }
                if (scroll.canScrollForward || scroll.canScrollBackward) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).matchParentSize())
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("testMessageCancelBtn")) { Text(MqttTestMessageTexts.CANCEL_BUTTON) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = open, enabled = sample.canOpen, modifier = Modifier.testTag("testMessageOpenBtn")) {
                    Text(MqttTestMessageTexts.OPEN_BUTTON)
                }
            }
        }
    }
}
