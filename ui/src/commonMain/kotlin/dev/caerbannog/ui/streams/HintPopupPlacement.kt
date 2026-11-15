package dev.caerbannog.ui.streams

/**
 * The vertical rule of the inline hint popup (M2-17d), all values in the same pixel space. [anchorY] is the line the popup hangs from:
 * below it by default (the popup's top edge on the line), and above it (its bottom edge on the line) when it does not fit below.
 * Returns the popup's top edge.
 *
 * Why a flip and not only a clamp: a clamp would slide the popup over its own "i" and the row; flipping keeps the beside-the-"i" look.
 * Why a clamp at all: in a viewport shorter than two popups neither side fits, and a popup that is whole beats a popup beside the "i".
 * A popup taller than the viewport starts on the top edge, where the first lines are the ones that matter.
 */
internal fun hintPopupTop(anchorY: Float, popupHeight: Float, viewportTop: Float, viewportBottom: Float): Float {
    fun fits(top: Float): Boolean = top >= viewportTop && top + popupHeight <= viewportBottom
    val below = anchorY
    val above = anchorY - popupHeight
    return when {
        fits(below) -> below
        fits(above) -> above
        else -> anchorY.coerceIn(viewportTop, (viewportBottom - popupHeight).coerceAtLeast(viewportTop))
    }
}
