package dev.caerbannog.ui.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.BindingRowView
import dev.caerbannog.core.topology.ExchangeRowView
import dev.caerbannog.core.topology.QueueRowView
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

private const val MEMBER_OFFLINE = "Member offline"

private val QueueColumns = listOf(
    TableColumn("Name", "Name", 240.dp, emphasis = true),
    TableColumn("Ready", "Ready", 100.dp, numeric = true),
    TableColumn("Unacked", "Unacked", 100.dp, numeric = true),
    TableColumn("Consumers", "Consumers", 110.dp, numeric = true),
    TableColumn("Leader", "Leader", 200.dp),
    TableColumn("MembersOnline", "Members online", 170.dp),
)

// "Other types" adds the Type column right after Name (T3); the quorum list has only quorum queues, so it needs none.
private val TypeColumn = TableColumn("Type", "Type", 100.dp)
private val OtherTypeColumns = QueueColumns.take(1) + TypeColumn + QueueColumns.drop(1)

private val ExchangeColumns = listOf(
    TableColumn("Name", "Name", 300.dp, emphasis = true),
    TableColumn("Type", "Type", 120.dp),
    TableColumn("Durable", "Durable", 100.dp),
    TableColumn("AutoDelete", "Auto-delete", 120.dp),
    TableColumn("Internal", "Internal", 100.dp),
)

private val BindingColumns = listOf(
    TableColumn("Source", "Source", 170.dp),
    TableColumn("Destination", "Destination", 170.dp),
    TableColumn("DestinationType", "Destination type", 150.dp),
    TableColumn("RoutingKey", "Routing key", 200.dp),
    TableColumn("Arguments", "Arguments", 260.dp),
)

/** The table of the current tab (and, on Queues, the detail of the selected queue under it) in one vertical scroller. */
@Composable
internal fun TopologyTableFor(state: TopologyState, events: TopologyEvents, loading: Boolean) {
    val detail = state.selectedQueue
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Escape closes the detail from anywhere in the table or the detail (the search and the switch keep their own Escape).
            .onPreviewKeyEvent { event ->
                val close = detail != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (close) events.selectQueue(null)
                close
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.tab) {
            dev.caerbannog.core.topology.TopologyTab.Queues -> QueuesTable(state, events, loading)
            dev.caerbannog.core.topology.TopologyTab.Exchanges -> ExchangesTable(state, loading)
            dev.caerbannog.core.topology.TopologyTab.Bindings -> BindingsTable(state, loading)
        }
        if (detail != null && !loading) QueueDetail(detail, events::selectQueue)
    }
}

@Composable
private fun QueuesTable(state: TopologyState, events: TopologyEvents, loading: Boolean) {
    val columns = if (state.queueFilter == QueueTypeFilter.OtherTypes) OtherTypeColumns else QueueColumns
    val rows = if (loading) null else state.queues.map { queueRow(it, state.queueFilter, state.selectedQueue?.name, events) }
    TopologyTable("queuesTable", columns, rows, state.stale)
}

private fun queueRow(queue: QueueRowView, filter: QueueTypeFilter, selectedName: String?, events: TopologyEvents): TableRowSpec {
    val cells = buildList {
        add(queue.name)
        if (filter == QueueTypeFilter.OtherTypes) add(queue.type)
        add(queue.ready); add(queue.unacked); add(queue.consumers); add(queue.leader); add(queue.membersOnline)
    }
    return TableRowSpec(
        rowTag = "queueRow:${queue.name}",
        cellKey = queue.name,
        cells = cells,
        selected = queue.name == selectedName,
        onOpen = { events.selectQueue(queue.name) },
        decorations = if (queue.memberOffline) mapOf("MembersOnline" to CellDecoration(leading = true) { MemberOfflineMark("memberOffline:${queue.name}") }) else emptyMap(),
    )
}

/** An icon with the words as its description: a member that is not online is never shown by colour alone. */
@Composable
internal fun MemberOfflineMark(tag: String) {
    Box(Modifier.testTag(tag).semantics { contentDescription = MEMBER_OFFLINE }.padding(end = 4.dp)) {
        ConnectionIcon(AppIcon.Warning, SmallIconSize, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ExchangesTable(state: TopologyState, loading: Boolean) {
    TopologyTable("exchangesTable", ExchangeColumns, if (loading) null else state.exchanges.map(::exchangeRow), state.stale)
}

private fun exchangeRow(exchange: ExchangeRowView) = TableRowSpec(
    rowTag = "exchangeRow:${exchange.name}",
    cellKey = exchange.name,
    cells = listOf(exchange.name, exchange.type, exchange.durable, exchange.autoDelete, exchange.internal),
    decorations = if (exchange.superStream) mapOf("Name" to CellDecoration(leading = false) { SuperStreamPill(exchange.name) }) else emptyMap(),
)

@Composable
private fun SuperStreamPill(name: String) {
    Text(
        "Super stream",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .testTag("superStreamPill:$name")
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun BindingsTable(state: TopologyState, loading: Boolean) {
    TopologyTable("bindingsTable", BindingColumns, if (loading) null else state.bindings.mapIndexed(::bindingRow), state.stale)
}

// Bindings have no unique name, so rows and cells are tagged by position in the visible page.
private fun bindingRow(index: Int, binding: BindingRowView) = TableRowSpec(
    rowTag = "bindingRow:$index",
    cellKey = index.toString(),
    cells = listOf(binding.source, binding.destination, binding.destinationType, binding.routingKey, binding.arguments),
)
