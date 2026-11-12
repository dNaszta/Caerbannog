package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.help.HowToTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.ui.appframe.ReasonTooltip
import dev.caerbannog.ui.components.HelpTip
import dev.caerbannog.ui.components.HowToLink
import dev.caerbannog.ui.components.TopLineWithHowTo
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The Migrate from 3.x screen, file source (M8-06a, review and generate M8-06b). State in, events out; the screen sends nothing to any broker and
 * holds no decision: every string that depends on the file comes ready from `core`.
 * Test tags: `migrate3x-choose-file`, `migrate3x-state-<name>` (`choose-source`, `loading`, `rejected`, `needs-virtual-host`, `report`),
 * `migrate3x-loss-banner`, `migrate3x-loss-copy`, `migrate3x-loss-show-all`, `migrate3x-loss-name-<i>`, `migrate3x-row-copy-<group key>-<index>`, `migrate3x-chip-<severity>` (`blocker`, `warning`, `info`), `migrate3x-group-<key>`, `migrate3x-row-<group key>-<index>`,
 * `migrate3x-vhost-field`, `migrate3x-vhost-error`, `migrate3x-export`, `migrate3x-copy-text`, plus `migrate3x-cancel`, `migrate3x-vhost-analyse`,
 * `migrate3x-notice`, `migrate3x-notice-dismiss`, `migrate3x-access-note`. The tags of the bar, sheet and panels of M8-06b are named in
 * `ReviewPanels.kt` and `ReviewSheet.kt`; a row of a decidable finding adds `migrate3x-pill-<group key>-<index>`, `migrate3x-review-<group key>-<index>` and
 * `migrate3x-unskip-<group key>-<index>`.
 *
 * [onCancelPrompt] is the one Cancel of the screen: it cancels a running Loading and answers the virtual host prompt with "no"; the caller
 * maps it to the view model events that apply (each is a no-op in the other state).
 */
@Composable
fun MigrateFromThreeScreen(
    state: MigrateFromThreeState,
    reportText: suspend () -> String,
    onChooseFile: () -> Unit,
    onGiveVirtualHost: (String) -> Unit,
    onCancelPrompt: () -> Unit,
    onToggleSeverity: (Severity) -> Unit,
    onToggleGroup: (String) -> Unit,
    onExport: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called after the report text was put on the clipboard (the view model then shows "Report copied as text"). */
    onReportCopied: () -> Unit = {},
    /** The review, generate and save events (see [ReviewEvents]); a screen that gets none shows the report and decides nothing. */
    review: ReviewEvents = ReviewEvents.None,
    /** The "Open Migrations" button of a saved result; no view model event navigates, the caller does (desktopApp: the Migrations screen). */
    onOpenMigrations: () -> Unit = {},
    /** M8-06c: the source picker, its event and "Analyse live connection"; the defaults draw the file source only, as before. */
    picker: SourcePicker = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE))),
    onChooseSource: (SourceKind) -> Unit = {},
    onAnalyseLive: () -> Unit = {},
    /** M8-09: the "Forward messages" button of a live report (`migrate3x-forward`); null draws none. */
    onForward: (() -> Unit)? = null,
    /** M8-09: while the forwarding view is open ([ForwardingScreenState.Closed] otherwise) it takes the place of the body, under the info line. */
    forwarding: ForwardingScreenState = ForwardingScreenState.Closed,
    forwardingEvents: ForwardingEvents? = null,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopLineWithHowTo(link = { HowToLink(HowToTexts.ANALYSE_AND_GENERATE) }) {
            Row(Modifier.weight(1f).testTag("migrate3x-info"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    MigrateFromThreeTexts.INFO_LINE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (forwarding != ForwardingScreenState.Closed && forwardingEvents != null) {
            ForwardingView(forwarding, forwardingEvents)
            return@Column
        }
        when (state) {
            MigrateFromThreeState.ChooseSource -> ChooseSourceState(picker, onChooseFile, onChooseSource, onAnalyseLive)
            is MigrateFromThreeState.Loading -> LoadingState(state, onCancelPrompt)
            is MigrateFromThreeState.Rejected -> RejectedState(state, onChooseFile)
            is MigrateFromThreeState.NeedsVirtualHost -> VirtualHostPrompt(state, onGiveVirtualHost, onChooseFile)
            is MigrateFromThreeState.LoadingLive -> LoadingLiveState(state, onCancelPrompt)
            is MigrateFromThreeState.CannotExport -> CannotExportState(state, onChooseFile)
            is MigrateFromThreeState.LiveFailed -> LiveFailedState(state, onAnalyseLive, onChooseFile)
            is MigrateFromThreeState.Report -> ReportState(state, reportText, onChooseFile, onToggleSeverity, onToggleGroup, onExport, onDismissNotice, onReportCopied, review, onOpenMigrations, onAnalyseLive, onForward)
        }
    }
}

@Composable
private fun ChooseSourceState(picker: SourcePicker, onChooseFile: () -> Unit, onChooseSource: (SourceKind) -> Unit, onAnalyseLive: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-choose-source"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Advice(MigrateFromThreeTexts.ADVICE_TOP, "migrate3x-advice-top")
        Text("Where does the 3.x topology come from?", style = MaterialTheme.typography.titleMedium)
        SourcePickerRow(picker, onChooseSource)
        val live = picker.options.filterIsInstance<SourceOption.Live>().firstOrNull()
        if (picker.selected == SourceKind.Live && live != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAnalyseLive, modifier = Modifier.testTag("migrate3x-analyse-live")) {
                    ConnectionIcon(AppIcon.TravelExplore, SmallIconSize)
                    Text(MigrateFromThreeTexts.ANALYSE_LIVE, Modifier.padding(start = 8.dp))
                }
                Text(
                    MigrateFromThreeTexts.analyseLiveHint(live.connectionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("migrate3x-analyse-live-hint"),
                )
            }
        } else {
            Button(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
                ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
                Text(MigrateFromThreeTexts.CHOOSE_FILE, Modifier.padding(start = 8.dp))
            }
            Text(
                MigrateFromThreeTexts.FILE_EXPORT_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Nothing is read until you choose a file. The file is read on this machine only; it is never uploaded. Files over 64 MB are refused.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The segmented picker: a selectable group of radio buttons. An enabled option is focusable (Enter and Space choose it); a disabled one
 * is not, says why as its description and tooltip, and shows the reason as text under it. The picker never offers to connect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcePickerRow(picker: SourcePicker, onChooseSource: (SourceKind) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        picker.options.forEach { option ->
            val kind = if (option is SourceOption.File) SourceKind.File else SourceKind.Live
            val enabled = option !is SourceOption.LiveDisabled
            val selected = enabled && picker.selected == kind
            val reason = (option as? SourceOption.LiveDisabled)?.reason
            val tag = if (kind == SourceKind.File) "migrate3x-source-file" else "migrate3x-source-live"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val chip: @Composable () -> Unit = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag(tag)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onChooseSource(kind) })
                            .semantics { reason?.let { contentDescription = "${option.label}: $it" } },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ConnectionIcon(if (selected) AppIcon.Check else if (!enabled) AppIcon.Lock else if (kind == SourceKind.Live) AppIcon.TravelExplore else AppIcon.UploadFile, SmallIconSize)
                            Text(option.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (reason == null) chip() else TooltipArea(tooltip = { ReasonTooltip(reason) }) { chip() }
                    M3xTip(if (kind == SourceKind.File) 3 else 4)
                }
                reason?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("migrate3x-source-live-reason"))
                }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

@Composable
private fun LoadingLiveState(state: MigrateFromThreeState.LoadingLive, onCancel: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-loading-live"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(IconSize))
            Text(MigrateFromThreeTexts.liveLoading(state.connectionName), style = MaterialTheme.typography.titleMedium)
        }
        Text(MigrateFromThreeTexts.LIVE_LOADING_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("migrate3x-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun CannotExportState(state: MigrateFromThreeState.CannotExport, onChooseFile: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-cannot-export"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FailureBanner(AppIcon.LockPerson, state.text)
        Text(MigrateFromThreeTexts.NOTHING_ANALYSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            MigrateFromThreeTexts.FILE_EXPORT_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("migrate3x-state-cannot-export-hint"),
        )
        ChooseFileButton(onChooseFile)
    }
}

@Composable
private fun LiveFailedState(state: MigrateFromThreeState.LiveFailed, onAnalyseLive: () -> Unit, onChooseFile: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-live-failed"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FailureBanner(AppIcon.Error, state.text)
        Text(MigrateFromThreeTexts.NOTHING_ANALYSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAnalyseLive, modifier = Modifier.testTag("migrate3x-try-again")) { Text(MigrateFromThreeTexts.TRY_AGAIN) }
            ChooseFileButton(onChooseFile)
        }
    }
}

@Composable
private fun ChooseFileButton(onChooseFile: () -> Unit) {
    OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
        ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
        Text(MigrateFromThreeTexts.CHOOSE_FILE, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LoadingState(state: MigrateFromThreeState.Loading, onCancel: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(IconSize))
            Text("Reading and analysing ", style = MaterialTheme.typography.titleMedium)
            Text(state.fileLabel, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
        Text("Large files can take a few seconds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("migrate3x-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun RejectedState(state: MigrateFromThreeState.Rejected, onChooseFile: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-rejected"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FailureBanner(AppIcon.Error, state.text) {
            Text(state.fileLabel, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Text(MigrateFromThreeTexts.NOTHING_ANALYSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
            ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
            Text("Choose another file", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun VirtualHostPrompt(state: MigrateFromThreeState.NeedsVirtualHost, onGive: (String) -> Unit, onChooseFile: () -> Unit) {
    // Empty on purpose: there is no default and "/" is not assumed. The text goes out as typed; the view model trims it.
    // The typed text is owned here and keyed on the file label ONLY, never on the state or its error: a refusal re-emits this state with an error
    // and the user must find the text as typed; a new file (another label) starts empty.
    var name by remember(state.fileLabel) { mutableStateOf("") }
    Column(Modifier.testTag("migrate3x-state-needs-virtual-host"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Which virtual host is this file from?", style = MaterialTheme.typography.titleMedium)
        Text(
            "The file holds one virtual host and does not say which. Its queues, exchanges and policies will be written for the name you give. " +
                "The file has no users, virtual hosts or permissions, so there will be no access migration, and the virtual host must already exist on the 4.x broker.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Advice(MigrateFromThreeTexts.ADVICE_VHOST, "migrate3x-advice-vhost")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Virtual host name") },
            singleLine = true,
            isError = state.error != null,
            supportingText = state.error?.let { error -> { Text(error, Modifier.testTag("migrate3x-vhost-error")) } },
            modifier = Modifier.fillMaxWidth().testTag("migrate3x-vhost-field").onPreviewKeyEvent { event ->
                val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                if (enter) onGive(name)
                enter
            },
        )
        Text(
            "Empty on purpose: there is no default and \"/\" is not assumed. Leading and trailing spaces are removed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onGive(name) }, modifier = Modifier.testTag("migrate3x-vhost-analyse")) { Text("Analyse") }
            OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) { Text("Choose another file") }
        }
    }
}

/** The tip of row [n] of the brief for this screen; the text comes from core. */
@Composable
internal fun M3xTip(n: Int) = HelpTip("m3x-$n", MigrateFromThreeTexts.tip("m3x-$n"))

/** An always visible advice line (owner decision D2): plain text, never only a tooltip. */
@Composable
internal fun Advice(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.testTag(tag))
}
