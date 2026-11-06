package dev.caerbannog.ui.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.components.CopyableText

private const val STALE = "Stale"
private const val LOADING_STATE = "Loading"
private const val SELECTED = "Selected"
private const val LOADING_PLACEHOLDER_ROWS = 3
private const val STALE_ALPHA = 0.6f
private val CellPadding = 4.dp

/** One column: [key] is the tag suffix (`cell:<row>:<key>`), [width] the base width (a wider window stretches every column alike). */
internal data class TableColumn(val key: String, val header: String, val width: Dp, val numeric: Boolean = false, val emphasis: Boolean = false)

/**
 * One data row. [cells] has one string per column, drawn unchanged (what is shown is what is copied). [cellKey] names the row in the cell tags.
 * [selected] null means the row cannot be selected; [onOpen] makes the FIRST cell a link (click or Enter). [decorations] draw next to the text of
 * the column with that key (a mark, a pill), outside the text node, so a copy never includes them.
 */
internal data class TableRowSpec(
    val rowTag: String,
    val cellKey: String,
    val cells: List<String>,
    val selected: Boolean? = null,
    val onOpen: (() -> Unit)? = null,
    val decorations: Map<String, CellDecoration> = emptyMap(),
)

/**
 * Drawn before ([leading]) or after the text of a cell. The text fills the rest of the cell, so "after" puts the decoration at the cell's
 * right edge, which suits a pill at the end of a wide name column but not a mark that belongs to a short value (that one is leading).
 */
internal class CellDecoration(val leading: Boolean, val content: @Composable RowScope.() -> Unit)

/**
 * A topology table: header, then the rows of one page in a plain Column (the view model pages, so it is never long). [rows] null draws the
 * loading frame. It scrolls sideways when the window is narrow, so no column is ever dropped. Stale rows are dimmed by the alpha of their
 * text colour, not by an alpha layer: a layer clips popups and the focus outline (F-C lesson). Screen readers hear "Stale" instead of seeing dim.
 */
@Composable
internal fun TopologyTable(tag: String, columns: List<TableColumn>, rows: List<TableRowSpec>?, stale: Boolean) {
    val total = columns.fold(0.dp) { sum, column -> sum + column.width }
    BoxWithConstraints {
        val scale = if (maxWidth.value.isFinite()) (maxWidth / total).coerceAtLeast(1f) else 1f
        val horizontal = rememberScrollState()
        Column(
            Modifier
                .testTag(tag)
                .semantics { if (rows == null) stateDescription = LOADING_STATE else if (stale) stateDescription = STALE }
                .horizontalScroll(horizontal)
                .widthIn(min = total),
        ) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                columns.forEach { column ->
                    Text(
                        column.header,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(column.width * scale).padding(horizontal = CellPadding).testTag("columnHeader:${column.key}"),
                    )
                }
            }
            HorizontalDivider()
            if (rows == null) {
                repeat(LOADING_PLACEHOLDER_ROWS) { index -> LoadingRow(columns, scale, first = index == 0) }
            } else {
                val textColor = if (stale) MaterialTheme.colorScheme.onSurface.copy(alpha = STALE_ALPHA) else Color.Unspecified
                // One SelectionContainer for the body: mouse selection works as in any text; the focusable cells add the keyboard.
                SelectionContainer {
                    Column { rows.forEach { DataRow(columns, it, scale, textColor) } }
                }
            }
        }
    }
}

@Composable
private fun DataRow(columns: List<TableColumn>, row: TableRowSpec, scale: Float, textColor: Color) {
    val selected = row.selected == true
    Column(
        Modifier
            .testTag(row.rowTag)
            .semantics {
                row.selected?.let { this.selected = it }
                if (selected) stateDescription = SELECTED
            }
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
    ) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            columns.forEachIndexed { index, column ->
                Row(Modifier.width(column.width * scale).padding(horizontal = CellPadding, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val decoration = row.decorations[column.key]
                    if (decoration?.leading == true) decoration.content(this)
                    CopyableText(
                        text = row.cells.getOrElse(index) { "" },
                        tag = "cell:${row.cellKey}:${column.key}",
                        style = if (column.emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                        color = textColor,
                        extra = Modifier.weight(1f),
                        onOpen = if (index == 0) row.onOpen else null,
                    )
                    if (decoration?.leading == false) decoration.content(this)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LoadingRow(columns: List<TableColumn>, scale: Float, first: Boolean) {
    // Only the first row is read by a screen reader; the table already says "Loading".
    Row(
        Modifier.heightIn(min = 48.dp).then(if (first) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(Modifier.width(column.width * scale).padding(horizontal = CellPadding)) {
                Text(
                    TopologyTexts.LOADING,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
