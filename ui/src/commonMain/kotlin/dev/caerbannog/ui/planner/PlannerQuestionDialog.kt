package dev.caerbannog.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.planner.PlannerQuestion

/**
 * The question that waits for an answer (Dismiss, Start a new plan). Esc, a click outside and the cancel button all keep things as they are;
 * the focus starts on the cancel button so that a stray Enter is the safe answer. The words come from [PlannerQuestion].
 */
@Composable
internal fun PlannerQuestionDialog(question: PlannerQuestion, events: PlannerEvents) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(question) { runCatching { cancelFocus.requestFocus() } }
    Dialog(onDismissRequest = events::cancelQuestion) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .width(520.dp)
                .padding(16.dp)
                .testTag("planner:question")
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        events.cancelQuestion()
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(question.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = events::cancelQuestion,
                        modifier = Modifier.focusRequester(cancelFocus).testTag("planner:questionCancelBtn"),
                    ) { Text(question.cancelLabel) }
                    Button(onClick = events::confirm, modifier = Modifier.testTag("planner:questionConfirmBtn")) { Text(question.confirmLabel) }
                }
            }
        }
    }
}
