package dev.caerbannog.ui.streams

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.PublisherRowState
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts

private const val LOADING_STATE = "Loading"
private const val STALE_STATE = "Stale"
private const val LOADING_PLACEHOLDER_ROWS = 2
private val CellPadding = 6.dp
private val CellVerticalPadding = 6.dp
private val OkGreen = Color(0xFF386A20)

internal class ClientColumn(val key: String, val header: String, val width: Dp, val numeric: Boolean = false)

private val PublisherColumns = listOf(
    ClientColumn("Reference", StreamClientsTexts.HEADER_REFERENCE, 200.dp),
    ClientColumn("Connection", StreamClientsTexts.HEADER_CONNECTION, 300.dp),
    ClientColumn("Published", StreamClientsTexts.HEADER_PUBLISHED, 110.dp, numeric = true),
    ClientColumn("Confirmed", StreamClientsTexts.HEADER_CONFIRMED, 110.dp, numeric = true),
    ClientColumn("Errors", StreamClientsTexts.HEADER_ERRORS, 90.dp, numeric = true),
)

private val ConsumerColumns = listOf(
    ClientColumn("Consumer", StreamClientsTexts.HEADER_CONSUMER, 200.dp),
    ClientColumn("Connection", StreamClientsTexts.HEADER_CONNECTION, 300.dp),
    ClientColumn("Offset", StreamClientsTexts.HEADER_OFFSET, 110.dp, numeric = true),
    ClientColumn("Behind", StreamClientsTexts.HEADER_MESSAGES_BEHIND, 150.dp, numeric = true),
    ClientColumn("Status", StreamClientsTexts.HEADER_STATUS, 160.dp),
)

@Composable
internal fun PublishersSection(rows: List<PublisherRowState>?, stale: Boolean) {
    ClientsSection("publishers", StreamClientsTexts.PUBLISHERS_TITLE, rows?.size, PublisherColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_PUBLISHERS, stale) { scale ->
        rows?.forEachIndexed { index, row -> PublisherRow(index, row, scale) }
    }
}

@Composable
internal fun ConsumersSection(rows: List<ConsumerRowState>?, stale: Boolean, state: StreamClientsState) {
    ClientsSection(
        "consumers", StreamClientsTexts.CONSUMERS_TITLE, rows?.size, ConsumerColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_CONSUMERS, stale,
        captionExtra = { if (rows != null) LastOffset(state) },
    ) { scale ->
        // The key is the row identity (a consumer that disappears or reorders keeps the others' state).
        rows?.forEach { row -> key(row.key) { ConsumerRow(row, scale) } }
    }
}

/**
 * One section: caption with the count, the header, and either the rows, "loading..." rows ([count] null) or the empty sentence.
 * The table node announces the caption (so the count is heard) and carries "Loading" or "Stale" as its state description,
 * because dimming alone is a colour-only signal.
 */
@Composable
private fun ClientsSection(
    prefix: String,
    title: String,
    count: Int?,
    columns: List<ClientColumn>,
    empty: Boolean,
    emptyText: String,
    stale: Boolean,
    captionExtra: @Composable () -> Unit = {},
    rows: @Composable (scale: Float) -> Unit,
) {
    val loading = count == null
    val caption = if (count == null) title else "$title $count"
    Column(Modifier.testTag("${prefix}Section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.clearAndSetSemantics {
                    set(SemanticsProperties.TestTag, "${prefix}Caption")
                    set(SemanticsProperties.Text, listOfNotNull(AnnotatedString(title), count?.let { AnnotatedString(it.toString()) }))
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                count?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            captionExtra()
        }
        val total = columns.fold(0.dp) { sum, column -> sum + column.width }
        BoxWithConstraints {
            val scale = if (maxWidth.value.isFinite()) (maxWidth / total).coerceAtLeast(1f) else 1f
            Column(
                Modifier
                    .testTag("${prefix}Table")
                    .semantics {
                        contentDescription = caption
                        if (loading) stateDescription = LOADING_STATE else if (stale) stateDescription = STALE_STATE
                    }
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = total),
            ) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    columns.forEach { column ->
                        Text(
                            column.header,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding).testTag("${prefix}Header:${column.key}"),
                        )
                    }
                }
                HorizontalDivider()
                when {
                    loading -> repeat(LOADING_PLACEHOLDER_ROWS) { index -> LoadingRow(columns, scale, first = index == 0) }
                    empty -> Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("${prefix}Empty").padding(vertical = 16.dp, horizontal = CellPadding),
                    )
                    // One SelectionContainer per table: mouse selection as in the list; every cell adds the keyboard copy (UI1).
                    else -> SelectionContainer {
                        Column(Modifier.alpha(if (stale) 0.6f else 1f)) { rows(scale) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(columns: List<ClientColumn>, scale: Float, first: Boolean) {
    // Only the first row is read by a screen reader; the table already says "Loading".
    Row(
        Modifier.heightIn(min = 48.dp).then(if (first) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Text(
                StreamClientsTexts.LOADING,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding),
            )
        }
    }
}

// Cells are top aligned on purpose: the selection container orders its texts by position, and a centred one-line cell next to a
// two-line connection cell would be ordered after the connection, so a drag over it selected the wrong text.
@Composable
private fun PublisherRow(index: Int, row: PublisherRowState, scale: Float) {
    val tag = "publisherCell:$index"
    Column {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.reference, "$tag:Reference", PublisherColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, PublisherColumns[1], scale)
            ValueCell(row.published, "$tag:Published", PublisherColumns[2], scale)
            ValueCell(row.confirmed, "$tag:Confirmed", PublisherColumns[3], scale)
            ValueCell(row.errors, "$tag:Errors", PublisherColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ConsumerRow(row: ConsumerRowState, scale: Float) {
    val tag = "consumerCell:${row.key}"
    Column {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.name, "$tag:Name", ConsumerColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, ConsumerColumns[1], scale)
            ValueCell(row.offset, "$tag:Offset", ConsumerColumns[2], scale)
            ValueCell(row.behind, "$tag:Behind", ConsumerColumns[3], scale)
            StatusCell(row.status, "$tag:Status", row.key, ConsumerColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ValueCell(text: String, tag: String, column: ClientColumn, scale: Float, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    // The padding is on the box, not on the text node: a drag that starts at the edge of the cell must still start a selection.
    Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = CellVerticalPadding)) {
        CopyableText(text, tag, style, if (column.numeric) TextAlign.End else TextAlign.Start)
    }
}

/** The socket pair with the user under it; a missing user is an em dash that screen readers hear as the NO_USER_NAME text. */
@Composable
private fun ConnectionCell(connection: String, user: String, tag: String, column: ClientColumn, scale: Float) {
    Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = CellVerticalPadding)) {
        Column {
            CopyableText(connection, "$tag:Connection", MaterialTheme.typography.bodyMedium)
            CopyableText(
                user, "$tag:User", MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                extra = if (user == StreamClientsTexts.NO_USER) Modifier.semantics { contentDescription = StreamClientsTexts.NO_USER_NAME } else Modifier,
            )
        }
    }
}

/**
 * Status is text, never colour alone. The stored-offsets hint is a hover tooltip, the state description (screen readers) AND an
 * "i" control the keyboard can focus, because a tooltip cannot be reached from a keyboard. "N behind" is neutral on purpose
 * (lag ruling): never amber or red.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusCell(status: ConsumerStatus, tag: String, rowKey: String, column: ClientColumn, scale: Float) {
    val hint = status.hint
    val cell: @Composable () -> Unit = {
        Row(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = CellVerticalPadding), verticalAlignment = Alignment.CenterVertically) {
            // CopyableText fills the width it is given, so the weighted box keeps room for the "i" control.
            Box(Modifier.weight(1f)) {
                CopyableText(
                    status.text, tag, MaterialTheme.typography.bodyMedium,
                    color = if (status == ConsumerStatus.CaughtUp) OkGreen else Color.Unspecified,
                    extra = if (hint != null) Modifier.semantics { stateDescription = hint } else Modifier,
                )
            }
            if (hint != null) {
                Spacer(Modifier.width(HintGap))
                StatusHintControl(hint, rowKey)
            }
        }
    }
    if (hint == null) {
        cell()
    } else {
        // The area shows on hover after a delay and never on keyboard focus; the "i" control covers the keyboard.
        TooltipArea(
            tooltip = {
                Surface(shape = RoundedCornerShape(HintCornerRadius), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = HintShadowElevation) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.widthIn(max = HintPopupMaxWidth).padding(HintPopupPadding))
                }
            },
        ) { cell() }
    }
}
