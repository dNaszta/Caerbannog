package dev.caerbannog.ui.planner

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.MqttRouteWords
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/** True for Enter and the keypad Enter on key down: the key of "Next" and "Add to plan". */
internal fun androidx.compose.ui.input.key.KeyEvent.isEnterDown(): Boolean =
    type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter)

/** Runs [action] on Enter before a child (a radio, a text field) can use the key itself; Back, Cancel and the other buttons are left alone. */
internal fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { if (it.isEnterDown()) { action(); true } else false }

/** What the dialog needs besides the pages: padding, stepper, buttons, the gaps and the dialog's own margin. */
private val WIZARD_CHROME: Dp = 220.dp
private val MIN_PAGES_HEIGHT: Dp = 160.dp

/**
 * The three pages of the wizard as one dialog: Kind, Details (the form of the kind) and Summary. Arrow keys move between the kinds, Enter is
 * Next (Add to plan on the last page), Esc and Cancel close it and add nothing. The content scrolls when the window is short, so the buttons
 * stay in reach. Nothing here is sent anywhere until the view model's "Add to plan", and even that changes a plan only.
 */
@Composable
internal fun PlanWizardDialog(
    wizard: WizardState,
    events: PlannerEvents,
    wordsOf: (MigrationAction) -> MqttRouteWords,
    onAddToPlan: (WizardState.Summary) -> Unit,
) {
    if (wizard == WizardState.Closed) return
    // The height of the window the dialog stands over, read here: inside the dialog the constraints are the dialog's own. The pages scroll
    // within what is left after the stepper, the buttons and the padding, so the buttons stay in reach on a short window.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val pagesMaxHeight = (windowHeight - WIZARD_CHROME).coerceAtLeast(MIN_PAGES_HEIGHT)
    Dialog(onDismissRequest = events::cancelWizard) {
        var selectedKind by remember { mutableStateOf(PlannerTexts.BUILT_KINDS.first()) }
        val primary: () -> Unit = {
            when (wizard) {
                WizardState.Kind -> events.chooseKind(selectedKind)
                is WizardState.Details -> if (wizard.nextEnabled) events.next()
                is WizardState.Summary -> onAddToPlan(wizard)
                WizardState.Closed -> Unit
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .padding(16.dp)
                .testTag("planWizard")
                .onKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> { events.cancelWizard(); true }
                        event.isEnterDown() -> { primary(); true }
                        else -> false
                    }
                },
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Stepper(wizard)
                val scroll = rememberScrollState()
                // Box, not Row: a scrollbar that fills the height would make the content area as tall as it can be.
                Box(Modifier.heightIn(max = pagesMaxHeight)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (wizard) {
                            WizardState.Kind -> KindPage(selectedKind, onSelect = { selectedKind = it }, onChoose = events::chooseKind)
                            is WizardState.Details -> DetailsPage(wizard, events, onNext = primary)
                            is WizardState.Summary -> SummaryPage(wizard, wordsOf)
                            WizardState.Closed -> Unit
                        }
                    }
                    // Only while there is something to scroll: the bar has a minimum length of its own, which would make a short page tall.
                    if (scroll.canScrollForward || scroll.canScrollBackward) {
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).matchParentSize())
                    }
                }
                WizardButtons(wizard, events, primary)
            }
        }
    }
}

@Composable
private fun Stepper(wizard: WizardState) {
    val current = when (wizard) {
        WizardState.Kind -> 1
        is WizardState.Details -> 2
        is WizardState.Summary -> 3
        WizardState.Closed -> 0
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Kind", "Details", "Summary").forEachIndexed { i, label ->
            val on = i + 1 == current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(24.dp).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i + 1 < current) {
                        ConnectionIcon(AppIcon.Check, 14.dp)
                    } else {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (i < 2) Box(Modifier.weight(1f).size(width = 16.dp, height = 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun WizardButtons(wizard: WizardState, events: PlannerEvents, primary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = events::cancelWizard, modifier = Modifier.testTag("planWizard:cancel")) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        if (wizard !is WizardState.Kind) {
            TextButton(onClick = events::back, modifier = Modifier.testTag("planWizard:back")) { Text("Back") }
        }
        when (wizard) {
            is WizardState.Summary -> Button(onClick = primary, modifier = Modifier.testTag("planWizard:add")) {
                ConnectionIcon(AppIcon.Add, SmallIconSize)
                Spacer(Modifier.width(8.dp))
                Text("Add to plan")
            }
            else -> Button(
                onClick = primary,
                enabled = (wizard as? WizardState.Details)?.nextEnabled ?: true,
                modifier = Modifier.testTag("planWizard:next"),
            ) { Text("Next") }
        }
    }
}

// ---- page 1 ----

@Composable
private fun KindPage(selected: String, onSelect: (String) -> Unit, onChoose: (String) -> Unit) {
    val kinds = PlannerTexts.BUILT_KINDS
    val requesters = remember { kinds.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { requesters.getValue(selected).requestFocus() } }
    Text(PlannerTexts.WIZARD_KIND_TITLE, style = MaterialTheme.typography.titleLarge)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        kinds.forEachIndexed { i, kind ->
            val on = kind == selected
            fun move(by: Int) {
                val target = kinds[(i + by).coerceIn(kinds.indices)]
                onSelect(target)
                runCatching { requesters.getValue(target).requestFocus() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requesters.getValue(kind))
                    // Preview: the radio itself would take Enter as a click, and Enter here is Next.
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.DirectionDown -> { move(1); true }
                            event.key == Key.DirectionUp -> { move(-1); true }
                            event.isEnterDown() -> { onSelect(kind); onChoose(kind); true }
                            else -> false
                        }
                    }
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(kind) })
                    .border(
                        width = if (on) 2.dp else 1.dp,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("planWizard:kind:$kind"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionIcon(kindIcon(kind), 24.dp)
                Column {
                    Text(kind, style = MaterialTheme.typography.titleSmall)
                    Text(PlannerTexts.kindDescription(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun kindIcon(kind: String): AppIcon = when (kind) {
    "Queue" -> AppIcon.Queue
    "Exchange" -> AppIcon.SwapHoriz
    "Stream" -> AppIcon.Stream
    else -> AppIcon.Link
}

// ---- page 2 ----

@Composable
private fun DetailsPage(details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    when (val form = details.form) {
        is QueueForm -> QueueFormView(form, details, events, onNext)
        is ExchangeForm -> ExchangeFormView(form, details, events, onNext)
        is BindingForm -> BindingFormView(form, details, events, onNext)
        is StreamForm -> StreamFormView(form, details, events, onNext)
        is MqttRouteForm -> MqttRouteFormView(form, details, events, onNext)
    }
}

// ---- page 3 ----

@Composable
private fun SummaryPage(summary: WizardState.Summary, wordsOf: (MigrationAction) -> MqttRouteWords) {
    Column(Modifier.fillMaxWidth().testTag("planWizard:summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(PlannerTexts.summaryTitle(summary.actions.size), style = MaterialTheme.typography.titleLarge)
        summary.actions.forEachIndexed { i, action ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                Column {
                    val words = wordsOf(action)
                    Text(words.title, style = MaterialTheme.typography.bodyMedium)
                    words.detail.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
