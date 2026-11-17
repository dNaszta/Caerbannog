package dev.caerbannog.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.HeaderMatch
import dev.caerbannog.core.planner.HeaderValueType
import dev.caerbannog.core.planner.MatchRule
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.Suggestion
import dev.caerbannog.core.planner.SuggestionSource
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

// The forms of wizard page 2 (mockup section 4). Each form keeps its own fields while the page is on show: the view model answers on another
// thread, and a field that is rewritten from a late state loses letters. The problems, hints and suggestions are read from the state.

private fun WizardState.Details.problem(field: String): String? = problems.firstOrNull { it.field == field }?.message

private fun WizardState.Details.hint(field: String): String? = hints.firstOrNull { it.field == field }?.message

@Composable
internal fun QueueFormView(initial: QueueForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: QueueForm -> form = new; events.updateForm(new) }
    Text("Queue", style = MaterialTheme.typography.titleLarge)
    FormField("name", "Name", form.name, details, onNext, autoFocus = true) { change(form.copy(name = it)) }
    ChoiceGroup(
        tagPrefix = "planForm:type:",
        options = listOf("quorum" to "Quorum queue", "classic" to "Classic queue"),
        selected = form.type,
        note = PlannerTexts::queueTypeNote,
    ) { change(form.copy(type = it)) }
    Text(PlannerTexts.QUEUE_FORM_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ExchangeFormView(initial: ExchangeForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: ExchangeForm -> form = new; events.updateForm(new) }
    Text("Exchange", style = MaterialTheme.typography.titleLarge)
    FormField("name", "Name", form.name, details, onNext, autoFocus = true) { change(form.copy(name = it)) }
    ChoiceGroup(
        tagPrefix = "planForm:type:",
        options = listOf("fanout", "direct", "topic", "headers").map { it to it },
        selected = form.type,
        note = null,
        horizontal = true,
    ) { change(form.copy(type = it)) }
    Text(PlannerTexts.exchangeTypeNote(form.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun BindingFormView(initial: BindingForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: BindingForm -> form = new; events.updateForm(new) }
    Text("Binding", style = MaterialTheme.typography.titleLarge)
    details.pageMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("planWizard:pageMessage"))
    }
    FormField(
        "exchange", "From exchange", form.exchange, details, onNext, autoFocus = true,
        suggestions = details.exchangeSuggestions,
    ) { change(form.copy(exchange = it)) }
    ChoiceGroup(
        tagPrefix = "planForm:destinationKind:",
        options = listOf("queue" to "Queue or stream", "exchange" to "Exchange"),
        selected = if (form.destinationKind == BindingDestinationType.EXCHANGE) "exchange" else "queue",
        note = null,
        horizontal = true,
    ) { change(form.copy(destinationKind = if (it == "exchange") BindingDestinationType.EXCHANGE else BindingDestinationType.QUEUE)) }
    FormField(
        "destination", "To", form.destination, details, onNext,
        suggestions = details.destinationSuggestions,
    ) { change(form.copy(destination = it)) }
    if (details.asksRoutingKey) {
        FormField("routingKey", "Routing key", form.routingKey, details, onNext) { change(form.copy(routingKey = it)) }
    }
    if (details.asksHeaders) HeadersSection(form, details, change)
}

/**
 * The match rule and the header rows of a binding on a headers exchange. A rule the form does not offer (`all-with-x` of an opened file) leaves
 * both choices unselected until the user picks one. The problem or warning of the rows is drawn once, under them: it has no row of its own.
 */
@Composable
private fun HeadersSection(form: BindingForm, details: WizardState.Details, change: (BindingForm) -> Unit) {
    Text("A message matches when", style = MaterialTheme.typography.titleSmall)
    ChoiceGroup(
        tagPrefix = "planForm:matchRule:",
        options = listOf(MatchRule.ALL to "all headers match", MatchRule.ANY to "any header matches").map { (rule, label) -> rule.wire to label },
        selected = if (form.heldRule != null) "" else form.matchRule.wire,
        note = null,
        horizontal = true,
    ) { wire -> MatchRule.entries.firstOrNull { it.wire == wire }?.let { change(form.withRule(it)) } }
    form.headers.forEachIndexed { index, header ->
        HeaderRow(index, header, onChange = { new -> change(form.copy(headers = form.headers.mapIndexed { i, old -> if (i == index) new else old })) }) {
            change(form.copy(headers = form.headers.filterIndexed { i, _ -> i != index }))
        }
    }
    TextButton(
        onClick = { change(form.copy(headers = form.headers + HeaderMatch(""))) },
        modifier = Modifier.testTag("planForm:addHeader"),
    ) {
        ConnectionIcon(AppIcon.Add, SmallIconSize)
        Spacer(Modifier.width(8.dp))
        Text("Add header")
    }
    val error = details.problem("headers")
    val hint = details.hint("headers")
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp).testTag("planForm:error:headers"))
    } else if (hint != null) {
        Row(Modifier.padding(start = 16.dp).testTag("planForm:hint:headers"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionIcon(AppIcon.Lightbulb, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Name and value side by side with the remove button, the type chips under them (they would not fit one line at the narrow dialog width). */
@Composable
private fun HeaderRow(index: Int, header: HeaderMatch, onChange: (HeaderMatch) -> Unit, onRemove: () -> Unit) {
    val tag = "planForm:header:$index"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = header.name,
                onValueChange = { onChange(header.copy(name = it)) },
                label = { Text("Header") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("$tag:name"),
            )
            if (header.type == HeaderValueType.FLAG) {
                ChoiceGroup(
                    tagPrefix = "$tag:value:",
                    options = listOf("true" to "true", "false" to "false"),
                    selected = header.value,
                    note = null,
                    horizontal = true,
                    modifier = Modifier.weight(1f),
                ) { onChange(header.copy(value = it)) }
            } else {
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { onChange(header.copy(value = it)) },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("$tag:value"),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag("$tag:remove")) {
                ConnectionIcon(AppIcon.Close, 24.dp, contentDescription = "Remove header ${header.name}")
            }
        }
        ChoiceGroup(
            tagPrefix = "$tag:type:",
            options = listOf("text" to "text", "number" to "number", "flag" to "true or false"),
            selected = when (header.type) {
                HeaderValueType.TEXT -> "text"
                HeaderValueType.WHOLE -> "number"
                HeaderValueType.FLAG -> "flag"
            },
            note = null,
            horizontal = true,
        ) { chosen ->
            onChange(header.withType(when (chosen) { "number" -> HeaderValueType.WHOLE; "flag" -> HeaderValueType.FLAG; else -> HeaderValueType.TEXT }))
        }
    }
}

@Composable
internal fun StreamFormView(initial: StreamForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: StreamForm -> form = new; events.updateForm(new) }
    Text("Stream", style = MaterialTheme.typography.titleLarge)
    FormField("name", "Name", form.name, details, onNext, autoFocus = true) { change(form.copy(name = it)) }
    // The tick is for adding: an edit replaces one step, so it offers no second stream.
    if (details.editingStepId == null) {
        Row(
            modifier = Modifier
                .toggleable(value = form.withResponse, role = Role.Checkbox, onValueChange = { change(form.withPair(it)) })
                .padding(vertical = 4.dp)
                .testTag("planForm:responseTick"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = form.withResponse, onCheckedChange = null)
            Text(PlannerTexts.RESPONSE_STREAM_TICK, style = MaterialTheme.typography.bodyMedium)
        }
        if (form.withResponse) {
            // The view has no naming style: the view model hands over the suggestion, which follows the stream name until the user edits it.
            FormField("responseName", PlannerTexts.RESPONSE_STREAM_NAME_LABEL, form.responseName ?: details.suggestedResponseName, details, onNext) {
                change(form.copy(responseName = it))
            }
            Text(
                PlannerTexts.RESPONSE_STREAM_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("planForm:responseNote"),
            )
            MqttTick("answers", PlannerTexts.ANSWERS_OVER_MQTT_TICK, PlannerTexts.ANSWERS_OVER_MQTT_SUBLINE, form.answersTopic != null) {
                change(form.copy(answersTopic = if (it) "" else null))
            }
            form.answersTopic?.let { filter ->
                FormField(
                    "answersTopic", PlannerTexts.ANSWERS_TOPIC_LABEL, filter, details, onNext,
                    placeholder = PlannerTexts.ANSWERS_TOPIC_PLACEHOLDER,
                ) { change(form.copy(answersTopic = it)) }
                form.answersKey()?.let { KeyLine("answersKey", it) }
            }
            MqttTick("requests", PlannerTexts.REQUESTS_OVER_MQTT_TICK, PlannerTexts.REQUESTS_OVER_MQTT_SUBLINE, form.requestsTopic != null) {
                change(form.copy(requestsTopic = if (it) "" else null))
            }
            form.requestsTopic?.let { filter ->
                FormField("requestsTopic", PlannerTexts.REQUESTS_TOPIC_LABEL, filter, details, onNext) { change(form.copy(requestsTopic = it)) }
                form.requestsKey()?.let { KeyLine("requestsKey", it) }
            }
            // One sentence for both ticks: either route is an MQTT route and the app cannot read the broker's MQTT exchange.
            if (form.answersTopic != null || form.requestsTopic != null) {
                Text(
                    PlannerTexts.MQTT_EXCHANGE_ASSUMPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("planForm:mqttAssumption"),
                )
            }
        }
    }
}

/** One of the two MQTT ticks of a pair: the checkbox row, then its sub-line, which says which stream the route points at. */
@Composable
private fun MqttTick(name: String, label: String, subline: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
                .padding(vertical = 4.dp)
                .testTag("planForm:${name}OverMqtt"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            subline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp).testTag("planForm:${name}Subline"),
        )
    }
}

@Composable
private fun KeyLine(tagName: String, key: String) {
    Text(
        PlannerTexts.mqttRouteSaves(key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp).testTag("planForm:$tagName"),
    )
}

@Composable
internal fun MqttRouteFormView(initial: MqttRouteForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: MqttRouteForm -> form = new; events.updateForm(new) }
    Text(PlannerTexts.MQTT_ROUTE_LABEL, style = MaterialTheme.typography.titleLarge)
    FormField("filter", PlannerTexts.MQTT_FILTER_LABEL, form.filter, details, onNext, autoFocus = true) { change(form.copy(filter = it)) }
    // The key and the topic are drawn only while the filter is accepted; the assumption is drawn always, because the app cannot check it.
    form.bindingKey()?.let {
        Text(PlannerTexts.mqttRouteSaves(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("planForm:mqttKey"))
    }
    form.matchingTopic()?.let {
        Text(PlannerTexts.mqttMatchingTopic(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("planForm:mqttTopic"))
    }
    ChoiceGroup(
        tagPrefix = "planForm:destinationKind:",
        options = listOf("queue" to PlannerTexts.MQTT_QUEUE_LABEL, "stream" to PlannerTexts.MQTT_STREAM_LABEL),
        selected = when (form.destinationKind) {
            MqttDestinationKind.Queue -> "queue"
            MqttDestinationKind.Stream -> "stream"
            null -> ""
        },
        note = null,
        horizontal = true,
    ) { change(form.copy(destinationKind = if (it == "stream") MqttDestinationKind.Stream else MqttDestinationKind.Queue)) }
    FormField("destination", PlannerTexts.MQTT_DESTINATION_LABEL, form.destination, details, onNext, suggestions = details.destinationSuggestions) {
        change(form.copy(destination = it))
    }
    Text(
        PlannerTexts.MQTT_EXCHANGE_ASSUMPTION,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("planForm:mqttAssumption"),
    )
}

/**
 * One text field with its reason or hint underneath (the error is red and disables Next; a hint is advice and does not). With [suggestions] a
 * button opens a list, the items of this plan first; a typed name that is in no list is allowed and is told so.
 */
@Composable
private fun FormField(
    field: String,
    label: String,
    value: String,
    details: WizardState.Details,
    onNext: () -> Unit,
    autoFocus: Boolean = false,
    suggestions: List<Suggestion>? = null,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    val error = details.problem(field)
    val hint = details.hint(field)
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                // Material shows it only while the field is focused and empty; it is a hint and never the value.
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.weight(1f).focusRequester(focus).onEnter(onNext).testTag("planForm:$field"),
            )
            if (suggestions != null) SuggestionsMenu(field, suggestions, onPick = onChange)
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp).testTag("planForm:error:$field"))
        } else if (hint != null) {
            Row(Modifier.padding(start = 16.dp).testTag("planForm:hint:$field"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConnectionIcon(AppIcon.Lightbulb, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (suggestions != null && value.isNotEmpty() && suggestions.none { it.name == value }) {
            Text(
                PlannerTexts.NOT_IN_PLAN_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp).testTag("planForm:note:$field"),
            )
        }
    }
}

@Composable
private fun SuggestionsMenu(field: String, suggestions: List<Suggestion>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = suggestions.isNotEmpty(), modifier = Modifier.testTag("planForm:suggestionsBtn:$field")) {
            ConnectionIcon(AppIcon.ArrowDropDown, 24.dp, contentDescription = "Suggestions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(suggestion.name)
                            Text(
                                if (suggestion.source == SuggestionSource.PLAN) "in this plan" else "on the broker",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(suggestion.name)
                    },
                    modifier = Modifier.testTag("planForm:suggestion:$field:${suggestion.name}"),
                )
            }
        }
    }
}

/** A choice of one value as radios, each tagged `<tagPrefix><value>`. [note] is the second line of an option, when it has one. */
@Composable
private fun ChoiceGroup(
    tagPrefix: String,
    options: List<Pair<String, String>>,
    selected: String,
    note: ((String) -> String)?,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val content: @Composable (Pair<String, String>) -> Unit = { (value, label) ->
        Row(
            modifier = Modifier
                .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .testTag("$tagPrefix$value"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = value == selected, onClick = null)
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                note?.invoke(value)?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (horizontal) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { options.forEach { content(it) } }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { content(it) } }
    }
}
