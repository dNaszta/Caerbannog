package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrate3x.ChipView
import dev.caerbannog.core.migrate3x.ExportNotice
import dev.caerbannog.core.migrate3x.FixView
import dev.caerbannog.core.migrate3x.GroupView
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.LossBlockView
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReportView
import dev.caerbannog.core.migrate3x.RowView
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.ui.components.rememberCopyToClipboard
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

private const val LOSS_LIST_HEIGHT_DP = 360
private const val SUMMARY_PANE_WIDTH_DP = 400

/**
 * The report: a fixed header with the file and the actions, then the summary pane and one lazy list of groups with their rows.
 * Only the rows of an open group exist, and only the visible ones are composed, so 240,000 findings stay fast.
 */
@Composable
internal fun ReportState(
    state: MigrateFromThreeState.Report,
    reportText: () -> String,
    onChooseFile: () -> Unit,
    onToggleSeverity: (Severity) -> Unit,
    onToggleGroup: (String) -> Unit,
    onExport: () -> Unit,
    onDismissNotice: () -> Unit,
    onReportCopied: () -> Unit,
) {
    val view = state.view
    val copy = rememberCopyToClipboard()
    Column(Modifier.fillMaxSize().testTag("migrate3x-state-report"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHeader(view, onChooseFile, onExport, onCopyText = { copy(reportText()); onReportCopied() })
        state.notice?.let { Notice(it, onDismissNotice) }
        view.accessNote?.let { AccessNote(it) }
        ReportBody(view, copy, onToggleSeverity, onToggleGroup)
    }
}

@Composable
private fun ReportHeader(view: ReportView, onChooseFile: () -> Unit, onExport: () -> Unit, onCopyText: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionIcon(AppIcon.UploadFile, IconSize)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(view.fileLabel, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                view.sourceVersion?.let { Text("RabbitMQ $it", style = MaterialTheme.typography.labelLarge) }
                Text("File", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(view.countsLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
            ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
            Text("Choose another file", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onCopyText, modifier = Modifier.testTag("migrate3x-copy-text")) {
            ConnectionIcon(AppIcon.ContentCopy, SmallIconSize)
            Text("Copy as text", Modifier.padding(start = 8.dp))
        }
        Button(onClick = onExport, modifier = Modifier.testTag("migrate3x-export")) {
            ConnectionIcon(AppIcon.Download, SmallIconSize)
            Text("Export report", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Notice(notice: ExportNotice, onDismiss: () -> Unit) {
    val container = if (notice.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (notice.failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(container).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("migrate3x-notice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionIcon(if (notice.failed) AppIcon.Error else AppIcon.CheckCircle, SmallIconSize, tint = content)
        SelectionContainer(Modifier.weight(1f)) { Text(notice.text, color = content, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("migrate3x-notice-dismiss")) { Text("Dismiss", color = content) }
    }
}

@Composable
private fun AccessNote(text: String) {
    Row(Modifier.testTag("migrate3x-access-note"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Two panes: on the left the loss block, summary and chips (the "what is wrong, in total" part, first on the screen, scrolls on its own),
 * on the right the groups as one lazy list. One column would push the groups below the loss block; side by side a screen of 800 dp shows
 * a dozen group headers next to the block.
 */
@Composable
private fun ReportBody(view: ReportView, copy: (String) -> Unit, onToggleSeverity: (Severity) -> Unit, onToggleGroup: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryPane(view, copy, onToggleSeverity, Modifier.width(SUMMARY_PANE_WIDTH_DP.dp).fillMaxHeight())
        GroupList(view, copy, onToggleGroup, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SummaryPane(view: ReportView, copy: (String) -> Unit, onToggleSeverity: (Severity) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            view.lossBlock?.let { LossBanner(it, copy) }
            SummaryAndChips(view, onToggleSeverity)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("migrate3x-summary-scrollbar"))
    }
}

@Composable
private fun GroupList(view: ReportView, copy: (String) -> Unit, onToggleGroup: (String) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            view.groups.forEach { group ->
                item(key = "g:${group.key}") { GroupHeader(group, onToggleGroup) }
                if (group.open) {
                    val rows = view.rows(group.key)
                    items(count = rows.size, key = { "r:${group.key}:$it" }) { index ->
                        val row = remember(rows, index) { rows.at(index) }
                        ReportRow(row, group.key, index, copy)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("migrate3x-scrollbar"))
    }
}

@Composable
private fun LossBanner(loss: LossBlockView, copy: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("migrate3x-loss-banner")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Warning, IconSize)
                Text(loss.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            Text(loss.detection, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                LossNameList(loss)
            } else {
                SelectionContainer {
                    Text(
                        loss.preview.joinToString(", ") + if (loss.queueCount > loss.preview.size) ", ..." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loss.queueCount > loss.preview.size) {
                    if (!expanded) Text(MigrateFromThreeTexts.previewNote(loss.preview.size, loss.queueCount), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("migrate3x-loss-show-all")) {
                        Text(if (expanded) "Show first ${loss.preview.size}" else MigrateFromThreeTexts.showAll(loss.queueCount))
                    }
                }
                OutlinedButton(onClick = { copy(loss.copyText) }, modifier = Modifier.testTag("migrate3x-loss-copy")) {
                    ConnectionIcon(AppIcon.ContentCopy, SmallIconSize)
                    Text("Copy queue names", Modifier.padding(start = 8.dp))
                }
            }
            loss.fixes.forEach { Fix(it) }
        }
    }
}

/** All names in place, as a lazy list in a bounded box (the outer list scrolls too, so the box has a height of its own). */
@Composable
private fun LossNameList(loss: LossBlockView) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxWidth().heightIn(max = LOSS_LIST_HEIGHT_DP.dp)) {
        SelectionContainer {
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = LOSS_LIST_HEIGHT_DP.dp).padding(end = 12.dp)) {
                items(count = loss.queueCount, key = { it }) { index ->
                    Text(loss.queueAt(index), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 24.dp).testTag("migrate3x-loss-name-$index"))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Fix(fix: FixView) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionIcon(if (fix.recommended) AppIcon.CheckCircle else AppIcon.Info, SmallIconSize)
        Column {
            Text(fix.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (fix.recommended) FontWeight.Bold else FontWeight.Normal)
            Text(fix.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryAndChips(view: ReportView, onToggleSeverity: (Severity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(view.summaryLine, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            view.chips.forEach { chip -> SeverityChip(chip, onToggleSeverity) }
        }
    }
}

@Composable
private fun SeverityChip(chip: ChipView, onToggle: (Severity) -> Unit) {
    FilterChip(
        selected = chip.selected,
        onClick = { onToggle(chip.severity) },
        label = { Text("${severityLabel(chip.severity)} ${MigrateFromThreeTexts.count(chip.count)}") },
        leadingIcon = if (chip.selected) ({ ConnectionIcon(AppIcon.Check, SmallIconSize) }) else null,
        modifier = Modifier.testTag("migrate3x-chip-${chip.severity.name.lowercase()}"),
    )
}

@Composable
private fun GroupHeader(group: GroupView, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("migrate3x-group-${group.key}")
            .clickable(role = Role.Button) { onToggle(group.key) }
            .heightIn(min = 44.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(if (group.open) AppIcon.ArrowDropDown else AppIcon.ChevronRight, IconSize)
        SeverityBadge(group.severity)
        Text(group.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(MigrateFromThreeTexts.itemCount(group.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeverityBadge(severity: Severity) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (severity) {
        Severity.Blocker -> scheme.errorContainer to scheme.onErrorContainer
        Severity.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Severity.Info -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Text(
        severityLabel(severity),
        color = content,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(container).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun severityLabel(severity: Severity): String = severity.name

@Composable
private fun ReportRow(row: RowView, groupKey: String, index: Int, copy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("migrate3x-row-$groupKey-$index")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(kindIcon(row.kind), IconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Long names are cut here; the full name is what the copy button copies and what selection selects.
                    row.name?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(row.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (row.proposal.isNotEmpty()) Text("Proposed: ${row.proposal}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.user?.let { user ->
                        val algorithm = user.algorithm?.let { " Algorithm: $it." }.orEmpty()
                        Text("Tags: ${user.tags.joinToString(", ")}.$algorithm", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConnectionIcon(AppIcon.VisibilityOff, SmallIconSize)
                            Text(user.hashText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!row.verified) Text("Unverified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.name?.let { name ->
                IconButton(onClick = { copy(name) }, modifier = Modifier.testTag("migrate3x-row-copy-$groupKey-$index")) {
                    ConnectionIcon(AppIcon.ContentCopy, SmallIconSize, contentDescription = "Copy name")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun kindIcon(kind: ItemKind): AppIcon = when (kind) {
    ItemKind.Queue -> AppIcon.Inbox
    ItemKind.Exchange -> AppIcon.SwapHoriz
    ItemKind.Binding -> AppIcon.Link
    ItemKind.Policy, ItemKind.OperatorPolicy -> AppIcon.Rule
    ItemKind.VirtualHost -> AppIcon.Dns
    ItemKind.User -> AppIcon.Person
    ItemKind.Permission -> AppIcon.Lock
    ItemKind.TopicPermission -> AppIcon.Tag
    ItemKind.Parameter -> AppIcon.TableChart
    ItemKind.Global -> AppIcon.Hub
}
