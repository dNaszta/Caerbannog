package dev.caerbannog.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.DeadLetterForm
import dev.caerbannog.core.planner.DeadLetterStrategy
import dev.caerbannog.core.planner.DeadLetterTarget
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState

private const val TAG = "planWizard:deadLetter:"

/**
 * The Dead-lettering form (mockup "4. Dead-lettering"). Every decision (names, which strategy a queue offers, the problems, the notes) is the
 * form's and the view model's in `core`; this only draws it. The form is kept here while the page is on show, like the other forms, with one
 * exception: when the view model takes At least once away (the queue changed to one that does not offer it), that answer wins over the local copy.
 */
@Composable
internal fun DeadLetterFormView(initial: DeadLetterForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: DeadLetterForm -> form = new; events.updateForm(new) }
    val answered = details.form as? DeadLetterForm
    LaunchedEffect(answered?.strategy, answered?.publishersRefusedAccepted) {
        if (answered != null && (form.strategy != answered.strategy || form.publishersRefusedAccepted != answered.publishersRefusedAccepted)) {
            form = form.copy(strategy = answered.strategy, publishersRefusedAccepted = answered.publishersRefusedAccepted)
        }
    }
    val exchangeName = form.exchangeName ?: details.suggestedExchangeName
    val targetName = form.targetName ?: details.suggestedTargetName
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(PlannerTexts.DEAD_LETTER_KIND, style = MaterialTheme.typography.titleLarge)
        FormField(
            "queue", PlannerTexts.DEAD_LETTER_QUEUE_LABEL, form.queue, details, onNext, autoFocus = true,
            suggestions = details.queueSuggestions, tag = "${TAG}queue",
        ) { change(form.copy(queue = it)) }
        details.formNotes.takeIf { it.isNotEmpty() }?.let { notes ->
            Column(Modifier.padding(start = 16.dp).testTag("${TAG}notes")) {
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(PlannerTexts.DEAD_LETTER_TARGET_HEADING, style = MaterialTheme.typography.titleSmall)
        ChoiceGroup(
            tagPrefix = TAG,
            options = listOf(
                "targetQueue" to PlannerTexts.DEAD_LETTER_TARGET_QUEUE,
                "targetStream" to PlannerTexts.DEAD_LETTER_TARGET_STREAM,
            ),
            selected = if (form.target == DeadLetterTarget.Queue) "targetQueue" else "targetStream",
            note = { value ->
                if (value == "targetQueue") PlannerTexts.deadLetterTargetQueueLine(targetName) else PlannerTexts.deadLetterTargetStreamLine(targetName)
            },
        ) { change(form.copy(target = if (it == "targetStream") DeadLetterTarget.Stream else DeadLetterTarget.Queue)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            FormField("exchange", PlannerTexts.DEAD_LETTER_EXCHANGE_LABEL, exchangeName, details, onNext, modifier = Modifier.weight(1f), tag = "${TAG}exchange") {
                change(form.copy(exchangeName = it))
            }
            FormField(
                "targetName",
                if (form.target == DeadLetterTarget.Stream) PlannerTexts.DEAD_LETTER_TARGET_STREAM_LABEL else PlannerTexts.DEAD_LETTER_TARGET_QUEUE_LABEL,
                targetName, details, onNext, modifier = Modifier.weight(1f), tag = "${TAG}targetName",
            ) { change(form.copy(targetName = it)) }
        }
        StrategySection(form, details, change)
    }
}

/** The two options, the note of the selected one under them and, for At least once only, the tick with its note. */
@Composable
private fun StrategySection(form: DeadLetterForm, details: WizardState.Details, change: (DeadLetterForm) -> Unit) {
    Text(PlannerTexts.DEAD_LETTER_STRATEGY_HEADING, style = MaterialTheme.typography.titleSmall)
    StrategyOption("strategyAtMostOnce", PlannerTexts.DEAD_LETTER_AT_MOST_ONCE, pill = PlannerTexts.DEAD_LETTER_DEFAULT_PILL, selected = form.strategy == DeadLetterStrategy.AtMostOnce) {
        change(form.withStrategy(DeadLetterStrategy.AtMostOnce))
    }
    // Offered only for a queue known to be a quorum queue (S-F1b); the view model decides and says so.
    if (details.offersAtLeastOnce) {
        StrategyOption("strategyAtLeastOnce", PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE, pill = null, selected = form.strategy == DeadLetterStrategy.AtLeastOnce) {
            change(form.withStrategy(DeadLetterStrategy.AtLeastOnce))
        }
    }
    details.problem("strategy")?.let { Note(it, "planForm:error:strategy", error = true) }
    details.hint("strategy")?.let { Note(it, "planForm:hint:strategy") }
    if (form.strategy == DeadLetterStrategy.AtLeastOnce && details.offersAtLeastOnce) {
        Row(
            modifier = Modifier
                .toggleable(value = form.publishersRefusedAccepted, role = Role.Checkbox, onValueChange = { change(form.copy(publishersRefusedAccepted = it)) })
                .padding(vertical = 4.dp)
                .testTag("${TAG}refusedTick"),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = form.publishersRefusedAccepted, onCheckedChange = null)
            Column {
                Text(PlannerTexts.DEAD_LETTER_REFUSED_TICK_LABEL, style = MaterialTheme.typography.bodyMedium)
                details.hint("refusedTick")?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        details.problem("refusedTick")?.let { Note(it, "planForm:error:refusedTick", error = true) }
    }
}

@Composable
private fun StrategyOption(tag: String, label: String, pill: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag("$TAG$tag"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
        pill?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String, tag: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp).testTag(tag),
    )
}
