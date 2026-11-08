package dev.caerbannog.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import dev.caerbannog.ui.components.SearchField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/** The tab row (Queues, Exchanges, Bindings, and optionally Mqtt). Each tab is one tab stop: click, Enter or Space selects it. */
@Composable
internal fun TopologyTabs(current: TopologyTab, onSelect: (TopologyTab) -> Unit, hasMqtt: Boolean = false) {
    val tabs = if (hasMqtt) TopologyTab.entries else TopologyTab.entries.filter { it != TopologyTab.Mqtt }
    Column(Modifier.fillMaxWidth()) {
        Row {
            tabs.forEach { tab ->
                val on = tab == current
                val label = tab.name
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag("tab:${tab.name}")
                        // The label is also the node's own text, so the tab reads the same in the unmerged tree.
                        .semantics { text = AnnotatedString(label) }
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.heightIn(min = 45.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // The 3 dp indicator of the mockup; transparent on the other tabs so the row does not jump.
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The line above the table: the virtual host switch (only when the state shows it), then, when [searchAndFilters], the search field and the
 * controls of the current tab (type chips, the default-exchange switch).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TopologyControls(
    state: TopologyState,
    events: TopologyEvents,
    searchAndFilters: Boolean,
    onOpenPublishMqtt: (() -> Unit)? = null,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.showVhostSwitch) VhostSwitch(state.vhost, state.vhosts, state.homeVhost, events::selectVhost)
        if (searchAndFilters) {
            when (state.tab) {
                TopologyTab.Queues -> {
                    TopologySearchField("queueSearch", "Search queues", state.search, events::setSearch)
                    TypeChips(state.queueFilter, events::setQueueFilter)
                }
                TopologyTab.Exchanges -> TopologySearchField("exchangeSearch", "Search exchanges", state.search, events::setSearch)
                TopologyTab.Bindings -> {
                    TopologySearchField("bindingSearch", "Search bindings", state.search, events::setSearch)
                    BindingsViewSwitch(state.bindingsView, events::setBindingsView)
                    DefaultExchangeSwitch(state.showDefaultExchange, events::setShowDefaultExchange)
                }
                TopologyTab.Mqtt -> {
                    TopologySearchField("mqttSearch", "Search MQTT clients", state.search, events::setSearch)
                    if (onOpenPublishMqtt != null) {
                        Button(
                            onClick = onOpenPublishMqtt,
                            modifier = Modifier.testTag("publishMqttButton"),
                        ) {
                            ConnectionIcon(AppIcon.Add, SmallIconSize)
                            Spacer(Modifier.width(6.dp))
                            Text("Publish message")
                        }
                    }
                }
            }
        }
    }
}

/** The chips of the Queues tab; exclusive. The selected one follows the state, a click sends the filter. */
@Composable
private fun TypeChips(selected: QueueTypeFilter, onSelect: (QueueTypeFilter) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        QueueTypeFilter.entries.forEach { filter ->
            val label = if (filter == QueueTypeFilter.Quorum) "Quorum" else "Other types"
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(label) },
                modifier = Modifier.testTag("typeChip:${filter.name}").semantics { text = AnnotatedString(label) },
            )
        }
    }
}

/** The Bindings tab's switch: the default exchange and its implicit binding of every queue are hidden unless it is on. One tab stop; Space toggles. */
@Composable
private fun DefaultExchangeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .testTag("showDefaultExchange")
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Show default exchange", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The Bindings tab's view toggle: switches between Table and Graph representations. */
@Composable
private fun BindingsViewSwitch(view: BindingsViewMode, onSelect: (BindingsViewMode) -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(4.dp)
            .testTag("bindingsViewSwitch"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BindingsViewOption(
            icon = AppIcon.TableChart,
            label = "Table",
            tag = "bindingsView:Table",
            selected = view == BindingsViewMode.Table,
            onClick = { onSelect(BindingsViewMode.Table) },
        )
        BindingsViewOption(
            icon = AppIcon.Hub,
            label = "Graph",
            tag = "bindingsView:Graph",
            selected = view == BindingsViewMode.Graph,
            onClick = { onSelect(BindingsViewMode.Graph) },
        )
    }
}

@Composable
private fun BindingsViewOption(
    icon: AppIcon,
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .height(40.dp)
            .testTag(tag)
            .background(bg, RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ConnectionIcon(icon, SmallIconSize, tint = fg)
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * The search field of one tab. Delegates to [SearchField].
 */
@Composable
private fun TopologySearchField(tag: String, placeholder: String, search: String, onSearch: (String) -> Unit) {
    SearchField(
        value = search,
        placeholder = placeholder,
        tag = tag,
        onSearch = onSearch,
        minWidth = 280.dp,
        maxWidth = 400.dp,
    )
}

/** The range, the approximate note (Queues only: only queue counts lag) and the page buttons. */
@Composable
internal fun TopologyFooter(state: TopologyState, events: TopologyEvents) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val style = MaterialTheme.typography.bodySmall
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (state.tab == TopologyTab.Queues) {
            Text(state.approximateNote, style = style, color = dim, modifier = Modifier.weight(1f).testTag("approximateNote"))
        } else {
            Box(Modifier.weight(1f))
        }
        Text(state.rangeText, style = style, color = dim, modifier = Modifier.testTag("rangeText"))
        PageButton(AppIcon.ChevronLeft, "Previous page", "pagePrevious", state.hasPreviousPage, events::previousPage)
        PageButton(AppIcon.ChevronRight, "Next page", "pageNext", state.hasNextPage, events::nextPage)
    }
}

@Composable
private fun PageButton(icon: AppIcon, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = description }) {
        ConnectionIcon(icon, IconSize)
    }
}
