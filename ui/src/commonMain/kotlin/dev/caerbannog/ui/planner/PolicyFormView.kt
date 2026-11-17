package dev.caerbannog.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.PolicyForm
import dev.caerbannog.core.planner.PolicyKeyCatalog
import dev.caerbannog.core.planner.PolicySetting
import dev.caerbannog.core.planner.PolicyTarget
import dev.caerbannog.core.planner.PolicyValueKind
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The Policy form (mockup "4. Policy"). Every decision (which keys a row may take, what a target drops, the match line, the problems) is
 * the form's own in `core`; this only draws it. The form is kept here while the page is on show, like the other forms.
 */
@Composable
internal fun PolicyFormView(initial: PolicyForm, details: WizardState.Details, events: PlannerEvents, onNext: () -> Unit) {
    var form by remember(details.editingStepId) { mutableStateOf(initial) }
    val change = { new: PolicyForm -> form = new; events.updateForm(new) }
    // One child of the page, so the gaps are the tight ones of the mockup: three rows and the Add button fit above the fold of a 1280 x 800 window.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Policy", style = MaterialTheme.typography.titleLarge)
        details.pageMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("planWizard:pageMessage"))
        }
        FormField("name", "Name", form.name, details, onNext, autoFocus = true) { change(form.copy(name = it)) }
        FormField("pattern", "Applies to names matching (pattern)", form.pattern, details, onNext, hintTag = "planForm:policyMatches") {
            change(form.copy(pattern = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TargetMenu(form.target, Modifier.weight(2f)) { change(form.withTarget(it)) }
            FormField("priority", "Priority", form.priority, details, onNext, modifier = Modifier.weight(1f)) { change(form.copy(priority = it)) }
        }
        details.hint("target")?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("planForm:policyShortList"))
        }
        Text("Settings", style = MaterialTheme.typography.titleSmall)
        form.settings.forEachIndexed { index, setting ->
            SettingRow(index, setting, form, details.problem("setting:$index"), change)
        }
        details.problem("settings")?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp).testTag("planForm:error:settings"))
        }
        TextButton(
            onClick = { change(form.addSetting()) },
            enabled = form.addSetting() != form,
            modifier = Modifier.testTag("planForm:addSetting"),
        ) {
            ConnectionIcon(AppIcon.Add, SmallIconSize)
            Spacer(Modifier.width(8.dp))
            Text(PlannerTexts.POLICY_ADD_SETTING)
        }
    }
}

/** A dropdown of [options] (value to label) whose button shows the label of [selected]; the items are tagged `<itemTagPrefix><value>`. */
@Composable
private fun Dropdown(tag: String, itemTagPrefix: String, selectedLabel: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag(tag)) {
            Text(selectedLabel, modifier = Modifier.weight(1f))
            ConnectionIcon(AppIcon.ArrowDropDown, 24.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(value) }, modifier = Modifier.testTag("$itemTagPrefix$value"))
            }
        }
    }
}

@Composable
private fun TargetMenu(target: PolicyTarget, modifier: Modifier, onPick: (PolicyTarget) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Applies to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Dropdown("planForm:policyTarget", "planForm:policyTarget:", target.label, PolicyTarget.entries.map { it.wire to it.label }) { wire ->
            PolicyTarget.fromWire(wire)?.let(onPick)
        }
    }
}

/** The key dropdown (only keys of the target that no other row uses), the typed value, the remove button; the row's problem under them. */
@Composable
private fun SettingRow(index: Int, setting: PolicySetting, form: PolicyForm, problem: String?, change: (PolicyForm) -> Unit) {
    val tag = "planForm:setting:$index"
    val entry = PolicyKeyCatalog.entry(setting.key)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                tag = "$tag:name",
                itemTagPrefix = "planForm:settingOption:",
                selectedLabel = entry?.label ?: setting.key,
                options = form.offeredKeys(index).map { it.key to it.label },
                modifier = Modifier.weight(1f),
            ) { change(form.withSettingKey(index, it)) }
            val kind = entry?.kind
            if (kind is PolicyValueKind.Choice) {
                Dropdown(
                    tag = "$tag:value",
                    itemTagPrefix = "$tag:valueOption:",
                    selectedLabel = kind.values.firstOrNull { it.wire == setting.value }?.label ?: setting.value,
                    options = kind.values.map { it.wire to it.label },
                    modifier = Modifier.weight(1f),
                ) { change(form.withSettingValue(index, it)) }
            } else {
                OutlinedTextField(
                    value = setting.value,
                    onValueChange = { change(form.withSettingValue(index, it)) },
                    label = { Text("Value") },
                    singleLine = true,
                    isError = problem != null,
                    modifier = Modifier.weight(1f).testTag("$tag:value"),
                )
            }
            IconButton(onClick = { change(form.removeSetting(index)) }, modifier = Modifier.testTag("$tag:remove")) {
                ConnectionIcon(AppIcon.Close, 24.dp, contentDescription = "Remove setting ${entry?.label ?: setting.key}")
            }
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp).testTag("planForm:error:setting:$index"))
        }
    }
}
