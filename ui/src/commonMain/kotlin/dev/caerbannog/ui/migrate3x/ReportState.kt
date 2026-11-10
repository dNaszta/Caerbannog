package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import dev.caerbannog.core.migrate3x.DecisionPill
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
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.ui.components.rememberCopyToClipboard
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

private const val LOSS_LIST_HEIGHT_DP = 360

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
    review: ReviewEvents,
    onOpenMigrations: () -> Unit,
    onAnalyseLive: () -> Unit = {},
) {
    val view = state.view
    val copy = rememberCopyToClipboard()
    // Hoisted: the list keeps its place while the sheet of an item is open in its stead.
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().testTag("migrate3x-state-report"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val sheet = state.review.sheet
        if (sheet != null) {
            // The sheet of one item takes the place of the report: a narrow window has no room for both, and the sheet has its own Close.
            ReviewSheet(sheet, review, Modifier.weight(1f))
            return@Column
        }
        ReportHeader(view, onChooseFile, onExport, onAnalyseLive, onCopyText = { copy(reportText()); onReportCopied() })
        state.notice?.let { Notice(it, onDismissNotice) }
        view.accessNote?.let { AccessNote(it) }
        // A state built without a review (no lines yet) shows no bar: the 06a screen.
        if (state.review.pendingLine.isNotEmpty()) {
            DecisionBar(state.review, review)
            GenerationPanel(state.review, review, onOpenMigrations)
        }
        ReportBody(view, listState, copy, onToggleSeverity, onToggleGroup, review, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * The file on one line (cut with an ellipsis, the full name is in the tooltip) and the actions below it in a [FlowRow], so a narrow window
 * wraps the buttons instead of squeezing them.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ReportHeader(view: ReportView, onChooseFile: () -> Unit, onExport: () -> Unit, onAnalyseLive: () -> Unit, onCopyText: () -> Unit) {
    val source = view.source
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(if (source.kind == SourceKind.Live) AppIcon.Link else AppIcon.UploadFile, IconSize)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TooltipArea(
                        tooltip = {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                                Text(source.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.widthIn(max = 480.dp).padding(8.dp))
                            }
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Text(source.label, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    view.sourceVersion?.let { Text("RabbitMQ $it", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    Text(source.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    source.lockText?.let {
                        Row(Modifier.testTag("migrate3x-source-locked"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConnectionIcon(AppIcon.Lock, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Text(view.countsLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.canAnalyseAgain) {
                OutlinedButton(onClick = onAnalyseLive, modifier = Modifier.testTag("migrate3x-analyse-again")) {
                    ConnectionIcon(AppIcon.Refresh, SmallIconSize)
                    Text(MigrateFromThreeTexts.ANALYSE_AGAIN, Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
                ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
                Text("Choose another file", Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(onClick = onCopyText, modifier = Modifier.testTag("migrate3x-copy-text")) {
                ConnectionIcon(AppIcon.ContentCopy, SmallIconSize)
                Text("Copy as text", Modifier.padding(start = 8.dp), maxLines = 1)
            }
            Button(onClick = onExport, modifier = Modifier.testTag("migrate3x-export")) {
                ConnectionIcon(AppIcon.Download, SmallIconSize)
                Text("Export report", Modifier.padding(start = 8.dp), maxLines = 1)
            }
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
 * One lazy list, as in the mockup: the loss banner (only when there is a loss block), the summary, the chips, then each group header
 * followed by its rows when the group is open. Only the visible items are composed, so 240,000 findings stay fast.
 */
@Composable
private fun ReportBody(
    view: ReportView,
    listState: LazyListState,
    copy: (String) -> Unit,
    onToggleSeverity: (Severity) -> Unit,
    onToggleGroup: (String) -> Unit,
    review: ReviewEvents,
    modifier: Modifier,
) {
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp).testTag("migrate3x-report-list"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            view.lossBlock?.let { loss -> item(key = "loss") { LossBanner(loss, copy) } }
            item(key = "summary") { Text(view.summaryLine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            item(key = "chips") { Chips(view, onToggleSeverity) }
            view.groups.forEach { group ->
                item(key = "g:${group.key}") { GroupHeader(group, onToggleGroup) }
                if (group.open) {
                    val rows = view.rows(group.key)
                    items(count = rows.size, key = { "r:${group.key}:$it" }) { index ->
                        val row = remember(rows, index) { rows.at(index) }
                        ReportRow(row, group.key, index, copy, review)
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
private fun Chips(view: ReportView, onToggleSeverity: (Severity) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        view.chips.forEach { chip -> SeverityChip(chip, onToggleSeverity) }
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
        group.severity?.let { SeverityBadge(it) }
        Text(group.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(MigrateFromThreeTexts.itemCount(group.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SeverityBadge(severity: Severity) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportRow(row: RowView, groupKey: String, index: Int, copy: (String) -> Unit, review: ReviewEvents) {
    Column(Modifier.fillMaxWidth().testTag("migrate3x-row-$groupKey-$index")) {
        // The row is cut (name one line, message two); the tooltip holds the full name and message. Nothing else: no hash exists in a RowView.
        TooltipArea(
            tooltip = {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                    Text(
                        listOfNotNull(row.name, row.message).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.widthIn(max = 480.dp).padding(8.dp),
                    )
                }
            },
        ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(kindIcon(row.kind), IconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
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
            DecisionActions(row, groupKey, index, review)
            }
            if (!row.verified) UnverifiedMarker()
            row.name?.let { name ->
                IconButton(onClick = { copy(name) }, modifier = Modifier.testTag("migrate3x-row-copy-$groupKey-$index")) {
                    ConnectionIcon(AppIcon.ContentCopy, SmallIconSize, contentDescription = "Copy name")
                }
            }
        }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The pill and the buttons of a row that needs a decision: Review (or Change once decided) and, in the Skipped group, Un-skip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecisionActions(row: RowView, groupKey: String, index: Int, review: ReviewEvents) {
    val item = row.item ?: return
    val decision = row.decision ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            MigrateFromThreeTexts.pillText(decision),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("migrate3x-pill-$groupKey-$index"),
        )
        TextButton(onClick = { review.openItem(item) }, modifier = Modifier.testTag("migrate3x-review-$groupKey-$index")) {
            Text(MigrateFromThreeTexts.reviewButtonText(decision), maxLines = 1)
        }
        if (decision == DecisionPill.Skipped) {
            TextButton(onClick = { review.unskip(item) }, modifier = Modifier.testTag("migrate3x-unskip-$groupKey-$index")) { Text("Un-skip", maxLines = 1) }
        }
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
