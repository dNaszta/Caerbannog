package dev.caerbannog.ui.migrations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The draft of the planner as a card above the migration files (mockup section 1): not a file of the folder, so it has no version and no
 * status. Nothing is drawn for [PlannerEntry.NewPlan]. A draft that cannot be read has the card and no Dismiss (the planner reports it).
 */
@Composable
internal fun DraftCard(entry: PlannerEntry, onContinue: () -> Unit, onDismiss: () -> Unit) {
    if (entry is PlannerEntry.NewPlan) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().testTag("migrations:draftCard"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (entry) {
                is PlannerEntry.ContinuePlan -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(PlannerTexts.DRAFT_CARD_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("migrations:draftTitle"))
                        Pill(
                            PlannerTexts.DRAFT_CARD_PILL,
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.testTag("migrations:draftPill"),
                        )
                    }
                    if (entry.description.isNotEmpty()) {
                        Text(entry.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("migrations:draftDescription"))
                    }
                    Text(
                        entry.stepsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("migrations:draftSteps"),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onContinue, modifier = Modifier.testTag("migrations:draftContinueBtn")) { Text(entry.label) }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("migrations:draftDismissBtn")) {
                            Text(PlannerTexts.DISMISS_LABEL, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                is PlannerEntry.DraftUnreadable -> {
                    Text(entry.cardText, style = MaterialTheme.typography.bodySmall)
                    Text(entry.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = onContinue, modifier = Modifier.testTag("migrations:draftContinueBtn")) { Text(entry.label) }
                    }
                }
                PlannerEntry.NewPlan -> Unit
            }
        }
    }
}

/** "Plan saved as 0005 ...": a notice the window sets after a save; Close hides it. */
@Composable
internal fun PlannerNoticeBanner(text: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(12.dp)
            .testTag("migrations:plannerNotice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionIcon(AppIcon.CheckCircle, size = IconSize, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose, modifier = Modifier.testTag("migrations:plannerNoticeCloseBtn")) {
            ConnectionIcon(AppIcon.Close, SmallIconSize, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(6.dp))
            Text("Close", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** The question before a draft is thrown away: Cancel, Esc and a click outside keep it. */
@Composable
internal fun DismissDraftDialog(question: PlannerQuestion, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(16.dp)
                .testTag("migrations:draftDismissDialog")
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onCancel(); true } else false
                }
                .focusable(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(question.text, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).testTag("migrations:draftDismissCancelBtn")) { Text(question.cancelLabel) }
                    Button(onClick = onConfirm, modifier = Modifier.testTag("migrations:draftDismissConfirmBtn")) { Text(question.confirmLabel) }
                }
            }
        }
    }
}
