package dev.caerbannog.ui.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.PlanStep
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize
import dev.caerbannog.ui.migrations.Pill

/**
 * The left panel: "Steps, applied from top to bottom", Add step, the problems of the plan as a whole, and the list (lazy, because a plan
 * opened from a file can be long). The list scrolls where it truly overflows and has a scrollbar for mouse users.
 */
@Composable
internal fun PlanStepsPanel(
    state: PlannerState,
    events: PlannerEvents,
    focusStep: Int?,
    onFocusDone: () -> Unit,
    onFocusStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                PlannerTexts.STEPS_TITLE,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            FilledTonalButton(onClick = events::openWizard, modifier = Modifier.testTag("planner:addStepBtn")) {
                ConnectionIcon(AppIcon.Add, SmallIconSize)
                Spacer(Modifier.width(8.dp))
                Text("Add step")
            }
        }
        state.wholePlanProblems.forEachIndexed { i, problem ->
            ProblemLine(problem.message, Modifier.testTag("planner:wholePlanProblem:$i"))
        }
        if (state.isEmpty) {
            EmptyPlan(events)
        } else {
            // Last in the column, so it gets what is left and no more: a short plan makes a short list.
            StepList(state, events, focusStep, onFocusDone, onFocusStep)
        }
    }
}

@Composable
private fun EmptyPlan(events: PlannerEvents) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("planner:emptyState"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
            ConnectionIcon(AppIcon.AccountTree, 40.dp, tint = MaterialTheme.colorScheme.primary)
            Text(PlannerTexts.NO_STEPS_YET, style = MaterialTheme.typography.titleLarge)
            Text(PlannerTexts.NO_STEPS_HELP, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = events::openWizard, modifier = Modifier.testTag("planner:emptyAddBtn")) {
                ConnectionIcon(AppIcon.Add, SmallIconSize)
                Spacer(Modifier.width(8.dp))
                Text("Add step")
            }
        }
    }
}

@Composable
private fun StepList(
    state: PlannerState,
    events: PlannerEvents,
    focusStep: Int?,
    onFocusDone: () -> Unit,
    onFocusStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // One requester per step id: a keyed row keeps its focus when the list is reordered.
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }
    val steps by rememberUpdatedState(state.plan.steps)
    LaunchedEffect(focusStep) {
        val id = focusStep ?: return@LaunchedEffect
        val index = steps.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters.getOrPut(id) { FocusRequester() }.requestFocus() }
        }
        onFocusDone()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(end = 12.dp).testTag("planner:stepList")) {
                itemsIndexed(state.plan.steps, key = { _, step -> step.id }) { index, step ->
                    StepRow(
                        index = index,
                        step = step,
                        state = state,
                        events = events,
                        isLast = index == state.plan.steps.lastIndex,
                        requester = requesters.getOrPut(step.id) { FocusRequester() },
                        onMoved = onFocusStep,
                    )
                }
            }
            if (listState.canScrollForward || listState.canScrollBackward) {
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).matchParentSize())
            }
        }
    }
}

/** A response stream sits under its request stream, one step further in. */
private val RESPONSE_INDENT = 24.dp

@Composable
private fun StepRow(
    index: Int,
    step: PlanStep,
    state: PlannerState,
    events: PlannerEvents,
    isLast: Boolean,
    requester: FocusRequester,
    onMoved: (Int) -> Unit,
) {
    val all = state.stepProblems(step.id)
    val blocking = all.filter { it.blocking }
    val notes = all.filterNot { it.blocking }
    val selected = state.selectedStepId == step.id
    val result = state.stepCheck(step.id)
    val stale = (state.check as? CheckState.Done)?.stale == true
    val pairLine = state.responseStreamLine(step.id)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                .focusRequester(requester)
                // Alt with Up or Down moves the focused step; the key arrives from the focusable row below, which does not use Alt.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isAltPressed) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp -> { events.moveStepUp(step.id); onMoved(step.id); true }
                            Key.DirectionDown -> { events.moveStepDown(step.id); onMoved(step.id); true }
                            else -> false
                        }
                    }
                }
                .clickable { events.selectStep(step.id) }
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                .testTag("planStep:$index"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            // The number stays in its column; the icon and the words move in.
            if (pairLine != null) Spacer(Modifier.width(RESPONSE_INDENT))
            ConnectionIcon(step.action.icon(), SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(step.title, style = MaterialTheme.typography.bodyMedium)
                pairLine?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("planStepPair:$index"))
                }
                step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (blocking.isNotEmpty()) {
                    Row(Modifier.testTag("planStepProblem:$index"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            blocking.forEach { ProblemLine(it.message) }
                        }
                        Pill(
                            PlannerTexts.PROBLEM_PILL,
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            AppIcon.Error,
                        )
                    }
                }
                if (result != null) StepCheckReasons(index, result, stale)
                if (notes.isNotEmpty()) {
                    Column(Modifier.testTag("planStepNote:$index"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        notes.forEach { NoteLine(it.message) }
                    }
                }
            }
            if (result != null) StepCheckPills(index, result, stale)
            StepMenu(index, step, isFirst = index == 0, isLast = isLast, events = events)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StepMenu(index: Int, step: PlanStep, isFirst: Boolean, isLast: Boolean, events: PlannerEvents) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("planStepMenu:$index")) {
            ConnectionIcon(AppIcon.MoreVert, IconSize, contentDescription = "Step ${index + 1}: more actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StepMenuItem("planStepMenu:$index:edit", "Edit", step.isEditable) { open = false; events.editStep(step.id) }
            StepMenuItem("planStepMenu:$index:moveUp", "Move up", !isFirst) { open = false; events.moveStepUp(step.id) }
            StepMenuItem("planStepMenu:$index:moveDown", "Move down", !isLast) { open = false; events.moveStepDown(step.id) }
            StepMenuItem("planStepMenu:$index:remove", "Remove", true) { open = false; events.removeStep(step.id) }
        }
    }
}

@Composable
private fun StepMenuItem(tag: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = onClick, modifier = Modifier.testTag(tag))
}

@Composable
private fun ProblemLine(message: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectionIcon(AppIcon.Error, SmallIconSize, tint = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun NoteLine(message: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun MigrationAction.icon(): AppIcon = when (this) {
    is MigrationAction.DeclareQueue -> AppIcon.Queue
    is MigrationAction.DeclareExchange -> AppIcon.SwapHoriz
    is MigrationAction.DeclareBinding -> AppIcon.Link
    is MigrationAction.DeclareStream -> AppIcon.Stream
    else -> AppIcon.AccountTree
}
