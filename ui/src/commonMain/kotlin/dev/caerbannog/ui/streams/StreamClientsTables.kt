package dev.caerbannog.ui.streams

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import dev.caerbannog.ui.components.CopyableText
import dev.caerbannog.ui.components.MetricSparklineBadge
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.MetricSparkline
import dev.caerbannog.core.streams.PublisherRowState
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts

private const val LOADING_STATE = "Loading"
private const val STALE_STATE = "Stale"
private const val LOADING_PLACEHOLDER_ROWS = 2
private val CellPadding = 6.dp
private val CellVerticalPadding = 6.dp
private val CaptionGap = 12.dp
private val SectionGap = 16.dp
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

/** The scroll state of the two tables, hoisted so a row that leaves the composition does not lose the sideways position. */
internal class ClientsScrollStates(val publishers: ScrollState, val consumers: ScrollState)

@Composable
internal fun rememberClientsScrollStates(): ClientsScrollStates {
    val publishers = rememberScrollState()
    val consumers = rememberScrollState()
    return remember(publishers, consumers) { ClientsScrollStates(publishers, consumers) }
}

internal fun LazyListScope.publishersSection(
    rows: List<PublisherRowState>?,
    stale: Boolean,
    state: StreamClientsState,
    contentWidth: Dp,
    scroll: ScrollState,
) {
    clientsSection(
        "publishers", StreamClientsTexts.PUBLISHERS_TITLE, rows?.size, PublisherColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_PUBLISHERS,
        stale, contentWidth, scroll,
    ) { scale ->
        rows?.let { list ->
            itemsIndexed(list, key = { _, row -> "publisher:${row.key}" }) { index, row ->
                PublisherRow(index, row, state.publisherSparklines[row.key], scale, scroll, stale)
            }
        }
    }
}

internal fun LazyListScope.consumersSection(rows: List<ConsumerRowState>?, stale: Boolean, state: StreamClientsState, contentWidth: Dp, scroll: ScrollState) {
    clientsSection(
        "consumers", StreamClientsTexts.CONSUMERS_TITLE, rows?.size, ConsumerColumns, rows?.isEmpty() == true, StreamClientsTexts.NO_CONSUMERS,
        stale, contentWidth, scroll,
        captionExtra = { if (rows != null) LastOffset(state) },
    ) { scale ->
        // The key is the row identity (a consumer that disappears or reorders keeps the others' state); the prefix keeps it apart from
        // a publisher on the same connection, because both tables live in one list.
        rows?.let { list ->
            items(list, key = { "consumer:${it.key}" }) { row ->
                ConsumerRow(row, state.consumerLagSparklines[row.key], scale, scroll, stale)
            }
        }
    }
}

/**
 * One section as list items: caption with the count, the header, and either the rows, "loading..." rows ([count] null) or the empty
 * sentence, then a gap. The header item carries the table node: it announces the caption (so the count is heard) and "Loading" or
 * "Stale" as its state description, because dimming alone is a colour-only signal. Every item of the table shares [scroll], so the
 * columns move together. The rows are in a SelectionContainer around the whole list; everything else is excluded from the selection.
 */
private fun LazyListScope.clientsSection(
    prefix: String,
    title: String,
    count: Int?,
    columns: List<ClientColumn>,
    empty: Boolean,
    emptyText: String,
    stale: Boolean,
    contentWidth: Dp,
    scroll: ScrollState,
    captionExtra: @Composable () -> Unit = {},
    rows: LazyListScope.(scale: Float) -> Unit,
) {
    val loading = count == null
    val caption = if (count == null) title else "$title $count"
    val total = columns.totalWidth()
    val scale = if (contentWidth.value.isFinite()) (contentWidth / total).coerceAtLeast(1f) else 1f
    item(key = "$prefix:caption") {
        DisableSelection {
            Row(Modifier.padding(bottom = CaptionGap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
    }
    item(key = "$prefix:header") {
        DisableSelection {
            Column(
                Modifier
                    .testTag("${prefix}Table")
                    .semantics {
                        contentDescription = caption
                        if (loading) stateDescription = LOADING_STATE else if (stale) stateDescription = STALE_STATE
                    }
                    .tableWidth(scroll, total),
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
            }
        }
    }
    when {
        loading -> items(LOADING_PLACEHOLDER_ROWS, key = { "$prefix:loading:$it" }) { index ->
            DisableSelection { Column(Modifier.tableWidth(scroll, total)) { LoadingRow(columns, scale, first = index == 0) } }
        }
        empty -> item(key = "$prefix:empty") {
            DisableSelection {
                Column(Modifier.tableWidth(scroll, total)) {
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("${prefix}Empty").padding(vertical = 16.dp, horizontal = CellPadding),
                    )
                }
            }
        }
        else -> rows(scale)
    }
    item(key = "$prefix:gap") { Spacer(Modifier.height(SectionGap)) }
}

/**
 * The width and sideways scroll of the header and the one-off items of a table (loading rows, the empty sentence). The header is the
 * item that sets the size limits of [scroll] (a ScrollState takes them from the `horizontalScroll` node that lays it out).
 */
private fun Modifier.tableWidth(scroll: ScrollState, total: Dp): Modifier = horizontalScroll(scroll).widthIn(min = total)

/**
 * The same sideways scroll for a data row, WITHOUT the clip of `horizontalScroll`: that clip is the row's own bounds plus 30 dp, and the
 * keyboard hint popup of a row is taller than that, so it would be cut off at the next row. Gestures come from `scrollable` on the shared
 * [scroll], the content is laid out unbounded and shifted by the scroll value (never past its own overflow, in case the header that
 * set the limits is not composed). Left to right only, like the rest of the app.
 */
@Composable
private fun Modifier.rowScroll(scroll: ScrollState, total: Dp): Modifier {
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, reverseScrolling = false)
    return scrollable(scroll, Orientation.Horizontal, reverseDirection = reverse)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            val width = minOf(placeable.width, constraints.maxWidth)
            layout(width, placeable.height) { placeable.place(-scroll.value.coerceIn(0, placeable.width - width), 0) }
        }
        .widthIn(min = total)
}

private const val STALE_ALPHA = 0.6f

/** 1 on a fresh row; [STALE_ALPHA] inside [StaleDim]. Read by the colours the theme does not own (the green "caught up"). */
private val LocalRowAlpha = compositionLocalOf { 1f }

/**
 * The stale look as colour alpha on the text, divider and "i" colours, NOT as an alpha layer: a layer is an offscreen buffer
 * clipped to the row, which cut the inline hint popup (a child of the row) down to the row height. The popup keeps its own
 * inverse colours, so it stays opaque. A fresh row gets no wrapper at all, so it is drawn exactly as before.
 */
@Composable
private fun StaleDim(stale: Boolean, content: @Composable () -> Unit) {
    if (!stale) {
        content()
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dimmed = scheme.copy(
        onSurface = scheme.onSurface.copy(alpha = STALE_ALPHA),
        onSurfaceVariant = scheme.onSurfaceVariant.copy(alpha = STALE_ALPHA),
        outline = scheme.outline.copy(alpha = STALE_ALPHA),
        outlineVariant = scheme.outlineVariant.copy(alpha = STALE_ALPHA),
    )
    MaterialTheme(colorScheme = dimmed, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = STALE_ALPHA), LocalRowAlpha provides STALE_ALPHA, content = content)
    }
}

private fun List<ClientColumn>.totalWidth(): Dp = fold(0.dp) { sum, column -> sum + column.width }

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
private fun PublisherRow(
    index: Int,
    row: PublisherRowState,
    sparkline: MetricSparkline?,
    scale: Float,
    scroll: ScrollState,
    stale: Boolean,
) {
    val tag = "publisherCell:$index"
    StaleDim(stale) { Column(Modifier.rowScroll(scroll, PublisherColumns.totalWidth())) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.reference, "$tag:Reference", PublisherColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, PublisherColumns[1], scale)
            ValueWithSparklineCell(
                text = row.published,
                tag = "$tag:Published",
                sparkline = sparkline,
                sparklineTag = "publisherSparkline:${row.key}",
                column = PublisherColumns[2],
                scale = scale,
            )
            ValueCell(row.confirmed, "$tag:Confirmed", PublisherColumns[3], scale)
            ValueCell(row.errors, "$tag:Errors", PublisherColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    } }
}

@Composable
private fun ConsumerRow(
    row: ConsumerRowState,
    lagSparkline: MetricSparkline?,
    scale: Float,
    scroll: ScrollState,
    stale: Boolean,
) {
    val tag = "consumerCell:${row.key}"
    StaleDim(stale) { Column(Modifier.rowScroll(scroll, ConsumerColumns.totalWidth())) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
            ValueCell(row.name, "$tag:Name", ConsumerColumns[0], scale, MaterialTheme.typography.titleSmall)
            ConnectionCell(row.connection, row.user, tag, ConsumerColumns[1], scale)
            ValueCell(row.offset, "$tag:Offset", ConsumerColumns[2], scale)
            ValueWithSparklineCell(
                text = row.behind,
                tag = "$tag:Behind",
                sparkline = lagSparkline,
                sparklineTag = "consumerLagTrend:${row.key}",
                column = ConsumerColumns[3],
                scale = scale,
            )
            StatusCell(row.status, "$tag:Status", row.key, ConsumerColumns[4], scale)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    } }
}

@Composable
private fun ValueWithSparklineCell(
    text: String,
    tag: String,
    sparkline: MetricSparkline?,
    sparklineTag: String,
    column: ClientColumn,
    scale: Float,
) {
    if (sparkline == null) {
        ValueCell(text, tag, column, scale)
        return
    }
    Column(
        Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = CellVerticalPadding),
        horizontalAlignment = if (column.numeric) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CopyableText(text, tag, MaterialTheme.typography.bodyMedium, if (column.numeric) TextAlign.End else TextAlign.Start)
        MetricSparklineBadge(
            sparkline = sparkline,
            tag = sparklineTag,
        )
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
                    color = if (status == ConsumerStatus.CaughtUp) OkGreen.copy(alpha = LocalRowAlpha.current) else Color.Unspecified,
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
