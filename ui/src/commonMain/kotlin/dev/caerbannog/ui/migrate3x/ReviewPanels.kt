package dev.caerbannog.ui.migrate3x

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SaveProblem
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import dev.caerbannog.core.migrate3x.LeftOutRow

private const val PANEL_MAX_HEIGHT_DP = 360

/**
 * The bar under the header: how many items need a decision, "Show next undecided item" and "Generate migration" (states 9 and 10).
 * The lines come ready from `core`; Generate is enabled only when `core` says so. Tags: `migrate3x-pending-count`, `migrate3x-next-undecided`,
 * `migrate3x-generate`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecisionBar(review: ReviewState, events: ReviewEvents) {
    val done = review.pendingCount == 0
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("migrate3x-decision-bar")) {
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionIcon(if (done) AppIcon.CheckCircle else AppIcon.Rule, IconSize)
                Column(Modifier.testTag("migrate3x-pending-count")) {
                    Text(review.pendingLine, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val detail = review.decidedLine ?: MigrateFromThreeTexts.GENERATE_HINT.takeIf { !done }
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (!done) {
                TextButton(onClick = events::showNextUndecided, modifier = Modifier.testTag("migrate3x-next-undecided")) {
                    Text("Show next undecided item", maxLines = 1)
                }
            }
            Button(onClick = events::generate, enabled = review.canGenerate, modifier = Modifier.testTag("migrate3x-generate")) {
                ConnectionIcon(AppIcon.Check, SmallIconSize)
                Text("Generate migration", Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

/** What the generation says below the bar: nothing (Idle), progress, the question, the result or the problem. */
@Composable
internal fun GenerationPanel(review: ReviewState, events: ReviewEvents, onOpenMigrations: () -> Unit) {
    when (val generation = review.generation) {
        GenerationState.Idle -> Unit
        GenerationState.Saving -> Row(Modifier.testTag("migrate3x-saving"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(IconSize))
            Text("Saving the migration files", style = MaterialTheme.typography.bodyMedium)
        }
        GenerationState.ConfirmAgain -> ConfirmAgainCard(events)
        is GenerationState.Done -> ResultPanel(generation.report, events, onOpenMigrations)
        is GenerationState.Trouble -> TroublePanel(generation, events)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmAgainCard(events: ReviewEvents) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("migrate3x-confirm-again")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(MigrateFromThreeTexts.CONFIRM_AGAIN, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = events::generateAgain, modifier = Modifier.testTag("migrate3x-generate-confirm")) { Text("Generate again", maxLines = 1) }
                OutlinedButton(onClick = events::cancelGenerateAgain, modifier = Modifier.testTag("migrate3x-generate-cancel")) { Text("Cancel", maxLines = 1) }
            }
        }
    }
}

/**
 * The folder problem or a partial save (state 11): the sentence built in `core`, the path under it, and the ways on. Try again only when
 * files are waiting to be saved with the same versions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TroublePanel(trouble: GenerationState.Trouble, events: ReviewEvents) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().testTag("migrate3x-trouble")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Error, IconSize)
                SelectionContainer(Modifier.weight(1f)) { Text(MigrateFromThreeTexts.problemText(trouble), style = MaterialTheme.typography.bodyMedium) }
            }
            (trouble.problem as? SaveProblem.Folder)?.let { folder ->
                SelectionContainer { Text(folder.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trouble.problem is SaveProblem.FileRefused) {
                    Button(onClick = events::retrySave, modifier = Modifier.testTag("migrate3x-trouble-retry")) { Text("Try again", maxLines = 1) }
                }
                OutlinedButton(onClick = events::chooseFolder, modifier = Modifier.testTag("migrate3x-trouble-choose-folder")) {
                    ConnectionIcon(AppIcon.Folder, SmallIconSize)
                    Text("Choose another folder", Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(onClick = events::exportFiles, modifier = Modifier.testTag("migrate3x-trouble-export")) {
                    ConnectionIcon(AppIcon.Download, SmallIconSize)
                    Text("Export the files", Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
    }
}

/**
 * The result (state 10): the headline and the fixed sentences, then one lazy list with the files, what was left out and the notes, so a
 * generation that left out 60,000 items draws only what is visible. Left out comes before the notes: it is what the user must check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultPanel(report: GenerationReport, events: ReviewEvents, onOpenMigrations: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth().testTag("migrate3x-result")) {
        Column(Modifier.padding(16.dp).heightIn(max = PANEL_MAX_HEIGHT_DP.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionIcon(AppIcon.CheckCircle, IconSize)
                Text(report.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            if (report.how == How.Saved && report.files.isNotEmpty()) Text(MigrateFromThreeTexts.RESULT_NOTHING_SENT, style = MaterialTheme.typography.bodySmall)
            report.vhostHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            report.accessLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            report.secretWarning?.let { AccessWarning(it) }
            ResultList(report, Modifier.weight(1f, fill = false))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.how == How.Saved && report.files.isNotEmpty()) {
                    Button(onClick = onOpenMigrations, modifier = Modifier.testTag("migrate3x-result-open-migrations")) { Text("Open Migrations", maxLines = 1) }
                }
                OutlinedButton(onClick = events::dismissResult, modifier = Modifier.testTag("migrate3x-result-dismiss")) { Text("Close", maxLines = 1) }
            }
        }
    }
}

/** The secret-file warning (D3) in the warning tone: shown on a saved and on an exported result. */
@Composable
private fun AccessWarning(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp).testTag("migrate3x-access-warning"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionIcon(AppIcon.Warning, SmallIconSize, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun LeftOutRows(title: String, rows: List<LeftOutRow>, tag: String) {
    Column(Modifier.padding(top = 4.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title (${MigrateFromThreeTexts.count(rows.size)})", style = MaterialTheme.typography.labelLarge)
        rows.forEach { Text("${it.label}: ${it.text}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ResultList(report: GenerationReport, modifier: Modifier) {
    val state = rememberLazyListState()
    Box(modifier) {
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().padding(end = 12.dp).testTag("migrate3x-result-left-out"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(report.files.size, key = { "f:$it" }) { index ->
                val file = report.files[index]
                SelectionContainer {
                    Column {
                        Text("${file.version}  ${file.description}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        val isAccess = file.vhost == MigrateFromThreeTexts.ACCESS_FILE_LABEL
                        Text(
                            listOfNotNull(
                                MigrateFromThreeTexts.ACCESS_PILL.takeIf { isAccess },
                                file.path.takeIf { it.isNotEmpty() },
                                MigrateFromThreeTexts.READABLE_BY_YOU_ONLY.takeIf { isAccess && report.how == How.Saved },
                                MigrateFromThreeTexts.plural(file.actionCount, "action"),
                            ).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (report.leftOut.isNotEmpty()) {
                item(key = "left-out-title") { Text("Left out (${MigrateFromThreeTexts.count(report.leftOut.size)})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp)) }
                items(report.leftOut.size, key = { "l:$it" }) { index ->
                    val row = report.leftOut[index]
                    Text("${row.label}: ${row.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (report.usersNotMigrated.isNotEmpty()) {
                item(key = "users-not-migrated") { LeftOutRows(MigrateFromThreeTexts.NOT_MIGRATED_TITLE, report.usersNotMigrated, "migrate3x-result-users-not-migrated") }
            }
            if (report.permissionsLeftOut.isNotEmpty()) {
                item(key = "permissions-left-out") { LeftOutRows(MigrateFromThreeTexts.PERMISSIONS_LEFT_OUT_TITLE, report.permissionsLeftOut, "migrate3x-result-permissions-left-out") }
            }
            if (report.notes.isNotEmpty()) {
                item(key = "notes-title") { Text("Notes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp)) }
                items(report.notes.size, key = { "n:$it" }) { index -> Text(report.notes[index], style = MaterialTheme.typography.bodySmall) }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
