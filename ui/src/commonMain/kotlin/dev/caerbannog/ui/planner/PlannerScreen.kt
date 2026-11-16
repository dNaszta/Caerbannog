package dev.caerbannog.ui.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.SaveGate
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrameLayout
import dev.caerbannog.ui.appframe.ConnectionControl
import dev.caerbannog.ui.appframe.ReasonTooltip
import dev.caerbannog.ui.components.rememberCopyToClipboard
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize
import dev.caerbannog.ui.migrations.Pill

/** Below this width the step list and the review card stand one above the other instead of side by side. */
private val SIDE_BY_SIDE_FROM = 900.dp

/**
 * The Topology planner (docs/ux/Planner.dc.html): header, step list, wizard, review card with the migration text, and the limited states.
 * State in, events out: every text, every enabled flag and the order of the banners come from [state]; nothing here reaches the broker.
 * It is a sub-screen of Migrations, so the drawer keeps that item selected.
 */
@Composable
fun PlannerScreen(
    state: PlannerState,
    events: PlannerEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Leaving the screen, whatever the way, writes a description typed here that is still waiting for its quiet time.
    // A plain flag, not state: nothing is drawn from it, and onDispose must read the value of the moment.
    val descriptionEdited = remember { BooleanArray(1) }
    DisposableEffect(events) { onDispose { if (descriptionEdited[0]) events.flush() } }

    // After "Add to plan" the focus goes to the first new step (or to the step that was edited): the ids before the add are the marker.
    var addMarker by remember { mutableStateOf<Pair<Set<Int>, Int?>?>(null) }
    var focusStep by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.plan.steps, state.wizard) {
        val marker = addMarker
        if (marker != null && state.wizard == WizardState.Closed) {
            focusStep = state.plan.steps.firstOrNull { it.id !in marker.first }?.id ?: marker.second?.takeIf { id -> state.plan.steps.any { it.id == id } }
            addMarker = null
        }
    }

    AppFrameLayout(
        title = "Topology planner",
        modifier = modifier,
        onNavigate = onNavigate,
        actions = { PlannerActions(state, events) },
        chip = { ConnectionControl(control, controlEvents) },
        activeItem = AppDestination.Migrations.name,
        leading = {
            IconButton(onClick = { onNavigate(AppDestination.Migrations) }, modifier = Modifier.testTag("planner:backBtn")) {
                ConnectionIcon(AppIcon.ArrowBack, IconSize, contentDescription = "Back to Migrations")
            }
        },
        titleTag = "planner:title",
    ) {
        PlannerBody(state, events, focusStep, onFocusDone = { focusStep = null }, onFocusStep = { focusStep = it }, onDescriptionEdited = { descriptionEdited[0] = true })
    }

    state.question?.let { PlannerQuestionDialog(it, events) }
    PlanWizardDialog(
        wizard = state.wizard,
        events = events,
        onAddToPlan = { summary ->
            addMarker = state.plan.steps.map { it.id }.toSet() to summary.editingStepId
            events.addToPlan()
        },
    )
}

/** Dismiss, Check and Save as migration. A locked planner (3.x, unreadable draft) draws none of them: there is nothing to act on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlannerActions(state: PlannerState, events: PlannerEvents) {
    if (state.isLocked) return
    TextButton(
        onClick = events::dismiss,
        enabled = state.canDismiss,
        modifier = Modifier.testTag("planner:dismissBtn"),
    ) {
        ConnectionIcon(AppIcon.DeleteSweep, SmallIconSize)
        Spacer(Modifier.width(8.dp))
        Text(PlannerTexts.DISMISS_LABEL)
    }
    val checkReason = state.checkDisabledReason
    val check: @Composable () -> Unit = {
        OutlinedButton(onClick = {}, enabled = state.checkEnabled, modifier = Modifier.testTag("planner:checkBtn")) {
            ConnectionIcon(AppIcon.FactCheck, SmallIconSize)
            Spacer(Modifier.width(8.dp))
            Text(PlannerTexts.CHECK_LABEL)
        }
    }
    if (checkReason != null && !state.checkEnabled) TooltipArea(tooltip = { ReasonTooltip(checkReason) }) { check() } else check()
    Button(
        onClick = events::saveAsMigration,
        enabled = state.saveGate == SaveGate.Enabled,
        modifier = Modifier.testTag("planner:saveBtn"),
    ) {
        ConnectionIcon(AppIcon.Save, SmallIconSize)
        Spacer(Modifier.width(8.dp))
        Text("Save as migration")
    }
}

@Composable
private fun PlannerBody(
    state: PlannerState,
    events: PlannerEvents,
    focusStep: Int?,
    onFocusDone: () -> Unit,
    onFocusStep: (Int) -> Unit,
    onDescriptionEdited: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("planner:info")) {
            ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(PlannerTexts.INFO_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.banner?.let { PlannerBannerView(it, events) }
        state.errorMessage?.let { PlannerErrorView(it, state.errorDetail) }
        if (state.isLocked) return@Column

        PlanHeader(state, events, onDescriptionEdited)
        (state.saveGate as? SaveGate.Disabled)?.let { gate ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    gate.reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("planner:saveReason"),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= SIDE_BY_SIDE_FROM) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlanStepsPanel(state, events, focusStep, onFocusDone, onFocusStep, Modifier.weight(1f).fillMaxHeight())
                    PlanReviewCard(state, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlanStepsPanel(state, events, focusStep, onFocusDone, onFocusStep, Modifier.weight(1f).fillMaxWidth())
                    PlanReviewCard(state, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// ---- header ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanHeader(state: PlannerState, events: PlannerEvents, onEdited: () -> Unit) {
    // The field keeps its own text: the view model answers on another thread, and a field that is rewritten from a late state loses letters.
    var typed by remember { mutableStateOf<String?>(null) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = typed ?: state.description,
            onValueChange = {
                typed = it
                onEdited()
                events.setDescription(it)
            },
            label = { Text("Description") },
            singleLine = true,
            modifier = Modifier.weight(1f).widthIn(min = 320.dp).testTag("planner:description"),
        )
        VirtualHostPill(state.plan.vhost, events)
        NamingChip(state.namingStyle, events)
        Pill(
            label = state.draftLabel,
            background = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.testTag("planner:draftPill"),
        )
    }
}

/** The virtual host of the plan; a click opens a small field, because a plan is for one virtual host and it may not be the connection's. */
@Composable
private fun VirtualHostPill(vhost: String, events: PlannerEvents) {
    var open by remember { mutableStateOf(false) }
    var text by remember(vhost, open) { mutableStateOf(vhost) }
    Box(Modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("planner:vhost"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConnectionIcon(AppIcon.Dns, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Virtual host $vhost", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(12.dp).widthIn(min = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Virtual host of this plan") },
                    singleLine = true,
                    modifier = Modifier.testTag("planner:vhostField"),
                )
                Button(
                    onClick = {
                        events.setVirtualHost(text.trim())
                        open = false
                    },
                    enabled = text.isNotBlank() && text.trim() != vhost,
                    modifier = Modifier.testTag("planner:vhostApplyBtn"),
                ) { Text("Use") }
            }
        }
    }
}

@Composable
private fun NamingChip(style: NamingStyle, events: PlannerEvents) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("planner:namingChip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConnectionIcon(AppIcon.Tag, SmallIconSize)
            Text("Naming ", style = MaterialTheme.typography.labelLarge)
            Text(style.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            ConnectionIcon(AppIcon.ArrowDropDown, SmallIconSize)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NamingStyle.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, fontWeight = if (option == style) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                PlannerTexts.namingExample(option),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        events.setNamingStyle(option)
                        open = false
                    },
                    modifier = Modifier.testTag("planner:namingOption:${option.name}"),
                )
            }
        }
    }
}

// ---- banners ----

private enum class Tone { Warning, Error, Info }

@Composable
private fun PlannerBannerView(banner: PlannerBanner, events: PlannerEvents) {
    val (icon, tone) = when (banner) {
        is PlannerBanner.NotOffered -> AppIcon.Lock to Tone.Warning
        is PlannerBanner.DraftUnreadable -> AppIcon.History to Tone.Error
        PlannerBanner.ReadOnlyLevel, is PlannerBanner.NoConfigurePermission -> AppIcon.Lock to Tone.Info
        PlannerBanner.NotConnected -> AppIcon.LinkOff to Tone.Info
        is PlannerBanner.FolderProblem -> AppIcon.FolderOff to Tone.Warning
        is PlannerBanner.VhostMismatch -> AppIcon.Dns to Tone.Info
    }
    val (container, content) = tonePair(tone)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(container).padding(12.dp).testTag("planner:banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(icon, IconSize, tint = content)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(banner.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = content)
            if (banner is PlannerBanner.DraftUnreadable) {
                Text(banner.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = content)
            }
            banner.detail?.let { detail ->
                val mono = banner is PlannerBanner.FolderProblem
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                    color = content,
                )
            }
        }
        BannerButtons(banner, events, content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BannerButtons(banner: PlannerBanner, events: PlannerEvents, content: Color) {
    when (banner) {
        is PlannerBanner.DraftUnreadable -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = events::retryDraft, modifier = Modifier.testTag("planner:retryDraftBtn")) {
                ConnectionIcon(AppIcon.Refresh, SmallIconSize, tint = content)
                Spacer(Modifier.width(6.dp))
                Text("Try again", color = content)
            }
            TextButton(onClick = { events.showFolder(banner.path) }, modifier = Modifier.testTag("planner:showFolderBtn")) {
                ConnectionIcon(AppIcon.FolderOpen, SmallIconSize, tint = content)
                Spacer(Modifier.width(6.dp))
                Text("Show folder", color = content)
            }
            TextButton(onClick = events::startNewPlan, modifier = Modifier.testTag("planner:startNewPlanBtn")) {
                ConnectionIcon(AppIcon.RestartAlt, SmallIconSize, tint = content)
                Spacer(Modifier.width(6.dp))
                Text("Start a new plan", color = content)
            }
        }
        is PlannerBanner.FolderProblem -> TextButton(onClick = events::chooseFolder, modifier = Modifier.testTag("planner:chooseFolderBtn")) {
            ConnectionIcon(AppIcon.FolderOpen, SmallIconSize, tint = content)
            Spacer(Modifier.width(6.dp))
            Text("Choose folder", color = content)
        }
        else -> Unit
    }
}

@Composable
private fun PlannerErrorView(message: String, detail: String?) {
    val (container, content) = tonePair(Tone.Error)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(container).padding(12.dp).testTag("planner:error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(AppIcon.Error, IconSize, tint = content)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = content)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = content) }
        }
    }
}

@Composable
private fun tonePair(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    Tone.Warning -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Tone.Info -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

// ---- review card ----

/** Until the review graph (M9-04) the card shows the migration text the plan would be saved as, read only. */
@Composable
private fun PlanReviewCard(state: PlannerState, modifier: Modifier = Modifier) {
    val copy = rememberCopyToClipboard()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.testTag("planner:reviewCard"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PlannerTexts.REVIEW_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!state.isEmpty) {
                    TextButton(onClick = { copy(state.migrationText) }, modifier = Modifier.testTag("planner:jsonCopyBtn")) {
                        ConnectionIcon(AppIcon.ContentCopy, SmallIconSize)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                }
            }
            if (state.isEmpty) {
                Text(
                    PlannerTexts.NOTHING_TO_REVIEW,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("planner:reviewEmpty"),
                )
            } else {
                val scroll = rememberScrollState()
                Row(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            text = state.migrationText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.verticalScroll(scroll).testTag("planner:jsonView"),
                        )
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxHeight())
                }
                Text(PlannerTexts.REVIEW_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
