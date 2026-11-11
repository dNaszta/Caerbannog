package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrate3x.DecidedAs
import dev.caerbannog.core.migrate3x.FindingChoice
import dev.caerbannog.core.migrate3x.ItemSheet
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReplaceEditor
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The review sheet of one item (states 6 to 8 of the mockup). It shows what `core` built (an [ItemSheet]) and sends events; it decides nothing:
 * whether Accept is enabled, which ticks and which steps show, and every text come ready. Esc closes the sheet (a typed Replace text is kept by
 * the view model); Enter submits nothing. Tags: `migrate3x-review-sheet`, `migrate3x-sheet-close`, `migrate3x-option-<finding>-<option>`,
 * `migrate3x-tick-<Acknowledgement name>`, `migrate3x-accept`, `migrate3x-accept-block`, `migrate3x-skip`, `migrate3x-replace`,
 * `migrate3x-replace-text`, `migrate3x-replace-error`, `migrate3x-replace-save`, `migrate3x-replace-back`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewSheet(sheet: ItemSheet, events: ReviewEvents, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // The typed text is edited here and reported to the view model on every change (it keeps it as the draft of the item); the text area and
    // the Use button share it. Starts again from the view model's text each time the Replace sheet is shown.
    val editing = sheet.replace != null
    var replaceText by remember(sheet.item, editing) { mutableStateOf(sheet.replace?.text.orEmpty()) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("migrate3x-review-sheet").onPreviewKeyEvent { event ->
            val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
            if (escape) events.closeItem()
            escape
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        // The header and the buttons stay in place; the findings scroll between them, so Accept, Skip and Replace are always reachable.
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader(sheet, events)
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                Column(Modifier.verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    sheet.decided?.let { DecidedNote(it) }
                    val editor = sheet.replace
                    if (editor == null) {
                        ProposalPart(sheet, events)
                    } else {
                        ReplacePart(editor, replaceText) { replaceText = it; events.setReplaceText(it) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val editor = sheet.replace
                if (editor == null) ProposalButtons(sheet, events) else ReplaceButtons(events) { events.submitReplace(replaceText) }
            }
        }
    }
}

@Composable
private fun ProposalButtons(sheet: ItemSheet, events: ReviewEvents) {
    Button(onClick = events::accept, enabled = sheet.canAccept, modifier = Modifier.testTag("migrate3x-accept")) {
        ConnectionIcon(AppIcon.Check, SmallIconSize)
        Text("Accept", Modifier.padding(start = 8.dp), maxLines = 1)
    }
    M3xTip(9)
    OutlinedButton(onClick = events::skip, modifier = Modifier.testTag("migrate3x-skip")) {
        ConnectionIcon(AppIcon.Block, SmallIconSize)
        Text("Skip", Modifier.padding(start = 8.dp), maxLines = 1)
    }
    M3xTip(10)
    OutlinedButton(onClick = events::startReplace, modifier = Modifier.testTag("migrate3x-replace")) {
        ConnectionIcon(AppIcon.Edit, SmallIconSize)
        Text("Replace with my own actions", Modifier.padding(start = 8.dp), maxLines = 1)
    }
    M3xTip(11)
}

@Composable
private fun SheetHeader(sheet: ItemSheet, events: ReviewEvents) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                Text(MigrateFromThreeTexts.itemTitle(sheet.item), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            }
            Text(
                MigrateFromThreeTexts.itemPosition(sheet.itemNumber, sheet.itemTotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = events::closeItem, modifier = Modifier.testTag("migrate3x-sheet-close")) {
            ConnectionIcon(AppIcon.Close, IconSize, contentDescription = "Close")
        }
    }
}

@Composable
private fun DecidedNote(decided: DecidedAs) {
    val text = when (decided) {
        is DecidedAs.Accepted -> if (decided.ticked) "Accepted earlier, ordering tick set" else "Accepted earlier"
        is DecidedAs.Replaced -> "Replaced earlier by your own actions"
        DecidedAs.Skipped -> "Skipped earlier"
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("migrate3x-sheet-decided"))
}

@Composable
private fun ProposalPart(sheet: ItemSheet, events: ReviewEvents) {
    sheet.findings.forEachIndexed { index, finding -> FindingBlock(index, finding, events) }
    sheet.manualSteps.forEach { step ->
        Column(Modifier.testTag("migrate3x-manual-step")) {
            Text(step.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(step.text, style = MaterialTheme.typography.bodySmall)
        }
    }
    sheet.ticks.forEach { tick ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("migrate3x-tick-${tick.acknowledgement.name}")
                .toggleable(value = tick.ticked, role = Role.Checkbox) { events.setTick(tick.acknowledgement, it) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = tick.ticked, onCheckedChange = null)
            Text(tick.text, style = MaterialTheme.typography.bodyMedium)
        }
        M3xTip(12)
      }
    }
    val lines = MigrateFromThreeTexts.acceptBlockLines(sheet.acceptBlock)
    if (lines.isNotEmpty()) {
        Column(Modifier.testTag("migrate3x-accept-block"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun FindingBlock(index: Int, finding: FindingChoice, events: ReviewEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SeverityBadge(finding.severity)
            Text(finding.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!finding.verified) UnverifiedMarker()
        }
        Text(finding.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (finding.options.size > 1) {
            finding.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("migrate3x-option-$index-${option.index}")
                        .selectable(selected = finding.chosen == option.index, role = Role.RadioButton) { events.chooseAlternative(finding.findingId, option.index) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = finding.chosen == option.index, onClick = null)
                    Text(option.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            finding.options.singleOrNull()?.let { Text("Proposed: ${it.title}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The "unverified" marker; hovering it says why. One place for the sheet and the rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UnverifiedMarker() {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                Text(
                    MigrateFromThreeTexts.UNVERIFIED_TOOLTIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.widthIn(max = 320.dp).padding(8.dp),
                )
            }
        },
        delayMillis = 0,
    ) {
        Text(
            MigrateFromThreeTexts.UNVERIFIED,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("migrate3x-unverified"),
        )
    }
}

@Composable
private fun ReplacePart(editor: ReplaceEditor, text: String, onChange: (String) -> Unit) {
    Text(editor.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        isError = editor.error != null,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).testTag("migrate3x-replace-text")
            // The text is exposed as the node's text too, so a reader of the semantics tree (and the tests) can see what is typed.
            .semantics { this.text = AnnotatedString(text) },
    )
    editor.error?.let { error ->
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("migrate3x-replace-error"))
    }
}

@Composable
private fun ReplaceButtons(events: ReviewEvents, onSubmit: () -> Unit) {
    Button(onClick = onSubmit, modifier = Modifier.testTag("migrate3x-replace-save")) { Text("Use these actions", maxLines = 1) }
    OutlinedButton(onClick = events::backToProposal, modifier = Modifier.testTag("migrate3x-replace-back")) { Text("Back to the proposal", maxLines = 1) }
}
