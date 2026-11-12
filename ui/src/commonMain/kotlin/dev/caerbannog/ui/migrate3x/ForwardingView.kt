package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ForwardingViewModel
import dev.caerbannog.core.forwarding.ForwardingWarning
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.forwarding.RemoveButton
import dev.caerbannog.core.help.HowToTexts
import dev.caerbannog.ui.components.HelpTip
import dev.caerbannog.ui.components.HowToLink
import dev.caerbannog.ui.components.TopLineWithHowTo
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/** The events of the forwarding view, one to one onto `ForwardingViewModel`. */
interface ForwardingEvents {
    fun setQuery(query: String)
    fun pick(row: QueueRow)
    fun setAmqpHost(host: String)
    fun setAmqpPort(port: Int)
    fun setAnotherUser(on: Boolean, user: String, password: String)
    fun setKeepRunning(on: Boolean)
    fun setTyped(text: String)
    fun confirm()
    fun cancel()
    fun remove()
    fun close()
}

fun ForwardingViewModel.events(): ForwardingEvents = object : ForwardingEvents {
    override fun setQuery(query: String) = this@events.setQuery(query)
    override fun pick(row: QueueRow) = this@events.pick(row)
    override fun setAmqpHost(host: String) = this@events.setAmqpHost(host)
    override fun setAmqpPort(port: Int) = this@events.setAmqpPort(port)
    override fun setAnotherUser(on: Boolean, user: String, password: String) = this@events.setAnotherUser(on, user, password)
    override fun setKeepRunning(on: Boolean) = this@events.setKeepRunning(on)
    override fun setTyped(text: String) = this@events.setTyped(text)
    override fun confirm() = this@events.confirm()
    override fun cancel() = this@events.cancel()
    override fun remove() = this@events.remove()
    override fun close() = this@events.close()
}

/**
 * The forwarding view: the queue list, the plan, the confirmation and the progress of ONE queue. State in, events out; every text and every
 * decision comes from `core`. Test tags: `forward-find`, `forward-pick-<n>`, `forward-close`, `forward-plan`, `forward-block-<n>`, `forward-warning-<n>`,
 * `forward-amqp-host`, `forward-amqp-port`, `forward-other-user`, `forward-user`, `forward-password`, `forward-keep-running`, `forward-typed-name`,
 * `forward-confirm`, `forward-cancel`, `forward-progress`, `forward-ended`, `forward-remove`, `forward-remove-reason`.
 */
@Composable
fun ForwardingView(state: ForwardingScreenState, events: ForwardingEvents, modifier: Modifier = Modifier) {
    when (state) {
        ForwardingScreenState.Closed -> Unit
        is ForwardingScreenState.Pick -> PickView(state, events, modifier)
        is ForwardingScreenState.Preparing -> Working(ForwardingTexts.PREPARING, modifier)
        is ForwardingScreenState.Starting -> Working(ForwardingTexts.STARTING, modifier)
        is ForwardingScreenState.Blocked -> BlockedView(state, events, modifier)
        is ForwardingScreenState.Problem -> Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FailureBanner(AppIcon.Error, state.text)
            OutlinedButton(onClick = events::cancel, modifier = Modifier.testTag("forward-cancel")) { Text("Back to the queues") }
        }
        is ForwardingScreenState.Confirm -> ConfirmView(state, events, modifier)
        is ForwardingScreenState.Progress -> ProgressView(state, events, modifier)
    }
}

@Composable
private fun Working(text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(IconSize))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PickView(state: ForwardingScreenState.Pick, events: ForwardingEvents, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var query by remember { mutableStateOf(state.query) }
        TopLineWithHowTo(link = { HowToLink(HowToTexts.MOVE_MESSAGES) }) { Advice(ForwardingTexts.ADVICE_TOP, "fwd-advice-top") }
        Advice(ForwardingTexts.ADVICE_PICK, "fwd-advice-pick")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it; events.setQuery(it) }, singleLine = true, label = { Text("Find a queue") },
                modifier = Modifier.weight(1f).testTag("forward-find"),
            )
            OutlinedButton(onClick = events::close, modifier = Modifier.testTag("forward-close")) { Text("Back to the report") }
        }
        state.forwardDisabledReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.rows, key = { _, row -> "${row.vhost}/${row.name}" }) { index, row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (row.vhost != "/") Text(row.vhost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { events.pick(row) }, enabled = state.forwardDisabledReason == null, modifier = Modifier.testTag("forward-pick-$index")) {
                            Text(ForwardingTexts.BUTTON_FORWARD)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionIcon(AppIcon.Warning, SmallIconSize, tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoveControl(remove: RemoveButton?, events: ForwardingEvents) {
    remove ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = events::remove, enabled = remove.enabled, modifier = Modifier.testTag("forward-remove")) { Text("Remove shovel") }
        remove.reason?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("forward-remove-reason"))
                FwdTip(41)
            }
        }
    }
}

@Composable
private fun BlockedView(state: ForwardingScreenState.Blocked, events: ForwardingEvents, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("forward-plan"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Forward ${state.queue} to ${state.destination}", style = MaterialTheme.typography.titleMedium)
        state.blocks.forEachIndexed { i, block -> FailureBanner(AppIcon.Error, ForwardingTexts.block(block, state.destination, state.queue), Modifier.testTag("forward-block-$i")) }
        state.warnings.forEachIndexed { i, w -> WarningBanner(ForwardingTexts.warning(w), Modifier.testTag("forward-warning-$i")) }
        RemoveControl(state.remove, events)
        OutlinedButton(onClick = events::cancel, modifier = Modifier.testTag("forward-cancel")) { Text("Back to the queues") }
    }
}

@Composable
private fun ConfirmView(state: ForwardingScreenState.Confirm, events: ForwardingEvents, modifier: Modifier) {
    // The typed user name and password live here only; the view model gets them through the event and keeps a redacting value.
    // Keyed on the switch: turning it off, or a new form after Cancel, starts with empty fields.
    var user by remember(state.form.anotherUser) { mutableStateOf("") }
    var password by remember(state.form.anotherUser) { mutableStateOf("") }
    var portText by remember { mutableStateOf(state.form.amqpPort.toString()) }
    val form = state.form
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Advice(ForwardingTexts.ADVICE_MOVE, "fwd-advice-move")
        Column(Modifier.testTag("forward-plan"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Forward ${state.queue} to ${state.destination}", style = MaterialTheme.typography.titleMedium)
            // The two sentences the user must not miss: the messages are moved, and the source password is stored in clear text.
            state.lines.forEach {
                val emphasis = if (it == ForwardingTexts.MOVED || it == ForwardingTexts.CLEAR_TEXT) FontWeight.Bold else null
                if (it == ForwardingTexts.NOT_ENCRYPTED) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(it, style = MaterialTheme.typography.bodyMedium); FwdTip(36) }
                } else {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = emphasis)
                }
            }
            val plainWithoutLine = ForwardingTexts.NOT_ENCRYPTED !in state.lines && state.warnings.any { it is ForwardingWarning.NotEncrypted }
            // The plan carries the warning even if the lines do not: the sentence and its tip are drawn either way.
            if (plainWithoutLine) Row(verticalAlignment = Alignment.CenterVertically) { Text(ForwardingTexts.NOT_ENCRYPTED, style = MaterialTheme.typography.bodyMedium); FwdTip(36) }
            state.warnings.filterIsInstance<ForwardingWarning.DestinationNotQuorumOrStream>().forEachIndexed { i, w ->
                WarningBanner(ForwardingTexts.warning(w), Modifier.testTag("forward-warning-$i"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ForwardingTexts.amqpLabel(state.destination), style = MaterialTheme.typography.labelLarge)
            FwdTip(33)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.amqpHost, onValueChange = events::setAmqpHost, singleLine = true, label = { Text("Host") },
                modifier = Modifier.weight(1f).testTag("forward-amqp-host").semantics { contentDescription = "Host ${form.amqpHost}" },
            )
            OutlinedTextField(
                value = portText, singleLine = true, label = { Text("Port") },
                onValueChange = { text -> portText = text.filter { it.isDigit() }; portText.toIntOrNull()?.let(events::setAmqpPort) },
                modifier = Modifier.width(120.dp).testTag("forward-amqp-port").semantics { contentDescription = "Port $portText" },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwitchRow(ForwardingTexts.LABEL_ANOTHER_USER, form.anotherUser, "forward-other-user") { events.setAnotherUser(it, user, password) }
            FwdTip(34)
        }
        if (form.anotherUser) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user, singleLine = true, label = { Text("User name") },
                    onValueChange = { user = it; events.setAnotherUser(true, user, password) }, modifier = Modifier.weight(1f).testTag("forward-user"),
                )
                OutlinedTextField(
                    value = password, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                    onValueChange = { password = it; events.setAnotherUser(true, user, password) }, modifier = Modifier.weight(1f).testTag("forward-password"),
                )
            }
        }
        SwitchRow(ForwardingTexts.LABEL_KEEP_RUNNING, form.keepRunning, "forward-keep-running", events::setKeepRunning)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = form.typed, onValueChange = events::setTyped, singleLine = true, label = { Text(ForwardingTexts.LABEL_TYPED) },
                modifier = Modifier.weight(1f).testTag("forward-typed-name"),
            )
            FwdTip(37)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = events::confirm, enabled = state.canConfirm, modifier = Modifier.testTag("forward-confirm")) { Text(ForwardingTexts.BUTTON_FORWARD) }
            OutlinedButton(onClick = events::cancel, modifier = Modifier.testTag("forward-cancel")) { Text("Cancel") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgressView(state: ForwardingScreenState.Progress, events: ForwardingEvents, modifier: Modifier) {
    val ended = state.state is ForwardingState.Finished || state.state is ForwardingState.Failed
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.testTag(if (ended) "forward-ended" else "forward-progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Forwarding ${state.queue}", style = MaterialTheme.typography.titleMedium)
            if (!ended) CircularProgressIndicator(Modifier.size(IconSize))
            state.texts.forEachIndexed { i, text ->
                when {
                    i == 0 && state.state is ForwardingState.Failed -> FailureBanner(AppIcon.Error, text)
                    text == ForwardingTexts.NOT_ENCRYPTED -> Row(verticalAlignment = Alignment.CenterVertically) { Text(text, style = MaterialTheme.typography.bodyMedium); FwdTip(36) }
                    else -> Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!ended) Advice(ForwardingTexts.ADVICE_LEAVE, "fwd-advice-leave")
            (state.state as? ForwardingState.Failed)?.let { Advice(ForwardingTexts.failureAdvice(it.cleanedUp), "fwd-advice-failure") }
        }
        RemoveControl(state.remove, events)
        if (ended) OutlinedButton(onClick = events::cancel, modifier = Modifier.testTag("forward-cancel")) { Text("Back to the queues") }
        else OutlinedButton(onClick = events::close, modifier = Modifier.testTag("forward-close")) { Text("Back to the report") }
    }
}

/** The tip of row [n] of the brief for the forwarding view; the text comes from core. */
@Composable
private fun FwdTip(n: Int) = HelpTip("fwd-$n", ForwardingTexts.tip("fwd-$n"))
