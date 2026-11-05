package dev.caerbannog.ui.streams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.StreamRow
import dev.caerbannog.core.streams.StreamRowKind
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

private const val SIZE_UNAVAILABLE = "—"
private const val SIZE_TOOLTIP = "Not available from the broker (no byte size for streams); left blank until M3."
private const val STALE = "Stale"
private const val LOADING_STATE = "Loading"
private const val LOADING_PLACEHOLDER_ROWS = 3

/** The nine columns of the mockup, in order. The enum name is the tag suffix (`cell:<stream>:<name>`). */
private enum class Column(val header: String, val width: Dp, val numeric: Boolean) {
    Name("Name", 142.dp, false),
    Type("Type", 204.dp, false),
    Retention("Retention", 78.dp, false),
    Messages("Messages (approx.)", 143.dp, true),
    FirstOffset("First offset", 83.dp, true),
    LastOffset("Last offset", 81.dp, true),
    Size("Size", 38.dp, true),
    Publishers("Publishers", 86.dp, true),
    Consumers("Consumers", 86.dp, true),
    ;

    fun cell(row: StreamRow): String = when (this) {
        Name -> row.name
        Type -> row.typeText
        Retention -> row.retention
        Messages -> row.messages
        FirstOffset -> row.firstOffset
        LastOffset -> row.lastOffset
        Size -> SIZE_UNAVAILABLE
        Publishers -> row.publishers
        Consumers -> row.consumers
    }
}

/** The base widths add up to this; a wider window stretches every column by the same factor (a horizontal scroller has no weights). */
private val TotalWidth: Dp = Column.entries.fold(0.dp) { sum, column -> sum + column.width }

private val CellPadding = 4.dp
private val ToggleSize = 28.dp
private val PartitionIndent = 28.dp

/**
 * The table. [rows] null draws the loading frame (header and placeholder rows). It scrolls sideways when the window is narrow,
 * so no column is ever dropped; only the rows scroll down, the header stays.
 */
@Composable
internal fun StreamsTable(rows: List<StreamRow>?, state: StreamListState, events: StreamListEvents, onOpen: (String) -> Unit = {}) {
    BoxWithConstraints {
        val scale = if (maxWidth.value.isFinite()) (maxWidth / TotalWidth).coerceAtLeast(1f) else 1f
        StreamsTableBody(rows, state, events, scale, onOpen)
    }
}

@Composable
private fun StreamsTableBody(rows: List<StreamRow>?, state: StreamListState, events: StreamListEvents, scale: Float, onOpen: (String) -> Unit) {
    val horizontal = rememberScrollState()
    Column(
        Modifier
            .testTag("streamsTable")
            // Dimming alone is not enough (colour-only signal): screen readers are told as well.
            .semantics { if (rows == null) stateDescription = LOADING_STATE else if (state.stale) stateDescription = STALE }
            .horizontalScroll(horizontal)
            .widthIn(min = TotalWidth)
            .fillMaxHeight(),
    ) {
        HeaderRow(state.sort, events::setSort, scale)
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).alpha(if (state.stale) 0.6f else 1f)) {
            if (rows == null) {
                repeat(LOADING_PLACEHOLDER_ROWS) { index -> LoadingRow(first = index == 0, scale = scale) }
            } else {
                // One SelectionContainer for the whole body: mouse selection works as in any text; the focusable cells add the keyboard.
                SelectionContainer {
                    Column { rows.forEach { DataRow(it, scale, events, onOpen) } }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(sort: StreamSort, onSort: (StreamSort) -> Unit, scale: Float) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column.entries.forEach { column ->
            when (column) {
                Column.Name -> NameHeader(sort, onSort, scale)
                Column.Size -> SizeHeader(scale)
                else -> HeaderText(column, scale)
            }
        }
    }
}

@Composable
private fun HeaderText(column: Column, scale: Float, modifier: Modifier = Modifier) {
    Text(
        column.header,
        style = MaterialTheme.typography.labelLarge,
        textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = modifier.width(column.width * scale).padding(horizontal = CellPadding).testTag("columnHeader:${column.name}"),
    )
}

/** The only sort control in M2: a button (Tab, Enter, Space) with an arrow for the direction, announced as a state. */
@Composable
private fun NameHeader(sort: StreamSort, onSort: (StreamSort) -> Unit, scale: Float) {
    val ascending = sort == StreamSort.NameAscending
    Row(
        Modifier
            .width(Column.Name.width * scale)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onSort(if (ascending) StreamSort.NameDescending else StreamSort.NameAscending) }
            .semantics { stateDescription = if (ascending) "Sorted ascending" else "Sorted descending" }
            .padding(horizontal = CellPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(Column.Name.header, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.testTag("columnHeader:Name"))
        ConnectionIcon(if (ascending) AppIcon.ArrowUpward else AppIcon.ArrowDownward, SmallIconSize)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeHeader(scale: Float) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(SIZE_TOOLTIP) } },
        state = rememberTooltipState(),
    ) { HeaderText(Column.Size, scale) }
}

@Composable
private fun DataRow(row: StreamRow, scale: Float, events: StreamListEvents, onOpen: (String) -> Unit) {
    Column(Modifier.testTag("streamRow:${row.name}")) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column.entries.forEach { column ->
                if (column == Column.Name) {
                    NameCell(row, scale, events, onOpen)
                } else {
                    CopyableCell(column.cell(row), "cell:${row.name}:${column.name}", column, scale, MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (row.kind == StreamRowKind.SuperStream && row.expanded) PartitionNote(row.name)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The name cell. A stream or a partition name is the link of M2-06 (a partition is indented under its super stream); a super stream
 * name is plain text, because no stream of that name exists to open, and carries the expand control. The text node holds the name only,
 * so a copy never includes the indent or the control.
 */
@Composable
private fun NameCell(row: StreamRow, scale: Float, events: StreamListEvents, onOpen: (String) -> Unit) {
    val openable = row.kind != StreamRowKind.SuperStream
    Row(
        Modifier.width(Column.Name.width * scale).padding(horizontal = CellPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.kind == StreamRowKind.Partition) Spacer(Modifier.width(PartitionIndent))
        if (row.kind == StreamRowKind.SuperStream) ExpandToggle(row, events)
        CopyableText(
            text = row.name,
            tag = "cell:${row.name}:Name",
            style = MaterialTheme.typography.titleSmall,
            extra = Modifier.weight(1f),
            onOpen = if (openable) ({ onOpen(row.name) }) else null,
        )
    }
}

@Composable
private fun ExpandToggle(row: StreamRow, events: StreamListEvents) {
    IconButton(
        onClick = { events.toggleExpanded(row.name) },
        modifier = Modifier
            .size(ToggleSize)
            .testTag("expandToggle:${row.name}")
            .semantics { contentDescription = if (row.expanded) "Collapse ${row.name}" else "Expand ${row.name}" },
    ) {
        ConnectionIcon(if (row.expanded) AppIcon.ArrowDownward else AppIcon.ChevronRight, SmallIconSize)
    }
}

/** Order exists only inside a partition; the list says so under an expanded super stream (there is no merged view to open). */
@Composable
private fun PartitionNote(name: String) {
    Text(
        StreamListTexts.PARTITION_ORDER_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = CellPadding + ToggleSize, end = CellPadding, bottom = 8.dp).testTag("partitionNote:$name"),
    )
}

/** One list cell; it only selects and copies (the name cell is [NameCell]). */
@Composable
private fun CopyableCell(text: String, tag: String, column: Column, scale: Float, style: androidx.compose.ui.text.TextStyle) {
    // The padding is on the box, not on the text node: a mouse drag that starts at the edge of the cell must still start a selection.
    Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = 4.dp)) {
        CopyableText(text, tag, style, if (column.numeric) TextAlign.End else TextAlign.Start)
    }
}

/**
 * One value: its text node is focusable (Tab, visible outline) and Cmd+C or Ctrl+C copies the WHOLE displayed text, because
 * [SelectionContainer] alone gives no keyboard copy of a focused text (spike, M2-05c). The mouse selection still works.
 * With [onOpen] the same node is also a link (role Button; click or Enter opens; a drag still selects, spike M2-06b item 11).
 * Shared by the stream list and the publishers and consumers tables (UI1: one copy mechanism).
 */
@Composable
internal fun CopyableText(
    text: String,
    tag: String,
    style: androidx.compose.ui.text.TextStyle,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = Color.Unspecified,
    extra: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val outline = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    Text(
        text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(extra)
            .onKeyEvent { event ->
                val copy = event.type == KeyEventType.KeyDown && event.key == Key.C && (event.isMetaPressed || event.isCtrlPressed)
                // `ui` has one target (jvm); the desktop ClipEntry wraps a Transferable. Move this when a second target is added.
                if (copy) scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
                copy
            }
            .onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(2.dp, outline), RoundedCornerShape(4.dp))
            // clickable is focusable and opens on Enter; a plain cell only needs the focus.
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier.focusable()),
    )
}

@Composable
private fun LoadingRow(first: Boolean, scale: Float) {
    // Only the first row is read by a screen reader; the table already says "Loading".
    Row(
        Modifier.heightIn(min = 48.dp).then(if (first) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column.entries.forEach { column ->
            Text(
                if (column == Column.Size) SIZE_UNAVAILABLE else StreamListTexts.LOADING,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding),
            )
        }
    }
}

/**
 * The search field. It keeps its own text so typing is never lost while the view model's state arrives a moment later; the state's
 * filter wins when it differs (for example after a connection switch cleared it).
 */
@Composable
internal fun StreamsSearchField(filter: String, onFilter: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(filter, TextRange(filter.length))) }
    LaunchedEffect(filter) {
        if (filter != field.text) field = TextFieldValue(filter, TextRange(filter.length))
    }
    BasicTextField(
        value = field,
        onValueChange = {
            val changed = it.text != field.text
            field = it
            if (changed) onFilter(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag("streamsSearch").widthIn(min = 320.dp, max = 480.dp),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionIcon(AppIcon.Search, SmallIconSize)
                Box(Modifier.weight(1f)) {
                    if (field.text.isEmpty()) {
                        Text("Search streams", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        },
    )
}
