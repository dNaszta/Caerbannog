package dev.caerbannog.ui.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import dev.caerbannog.ui.planner.node

/** The narrowest a button may be: the Material 3 floor for a short label ("Save", "Edit file"), a touch target and not a sliver (M2-21 D2). */
internal const val MIN_BUTTON_WIDTH_PX = 58f

/** A button is one label line on a 40 dp minimum; a wrapped label measured 136 to 248 px (M2-21 D2). */
internal const val MAX_BUTTON_HEIGHT_PX = 64f

/**
 * M2-21: what "the button is on screen" means, instead of `isDisplayed`, which a 2 px wide Apply passes. Returns the problems found (empty = intact):
 *  - the bounds are inside the window horizontally; vertically too after [scroll] (`performScrollTo`, for a panel that scrolls as a whole);
 *  - the width is at least the width of its own label (the unmerged `Text` below the tag) and at least [minWidthPx];
 *  - the label is one line and the height is at most [MAX_BUTTON_HEIGHT_PX];
 *  - no two of the [tags] overlap (compared in one scroll state, after the last scroll).
 * Pills are not buttons: pass `minWidthPx = 0f` for them.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.intactProblems(
    tags: List<String>,
    windowWidth: Int,
    windowHeight: Int,
    scroll: Boolean = false,
    minWidthPx: Float = MIN_BUTTON_WIDTH_PX,
): List<String> {
    val problems = mutableListOf<String>()
    tags.forEach { tag ->
        if (scroll) node(tag).performScrollTo()
        waitForIdle()
        val b = node(tag).fetchSemanticsNode().boundsInRoot
        val where = "$tag $b at window ${windowWidth}x$windowHeight"
        if (b.left < -0.5f || b.right > windowWidth + 0.5f) problems += "$where is outside the window horizontally"
        if (scroll && (b.top < -0.5f || b.bottom > windowHeight + 0.5f)) problems += "$where is outside the window after scrolling to it"
        val label = onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        val layout: TextLayoutResult? = label?.let { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()
        }
        if (layout != null) {
            val labelWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            if (layout.lineCount > 1) problems += "$where: the label wraps to ${layout.lineCount} lines"
            if (b.width < labelWidth) problems += "$where: ${b.width} px wide, narrower than its label (${labelWidth} px)"
        }
        if (b.width < minWidthPx) problems += "$where: narrower than $minWidthPx px"
        if (b.height > MAX_BUTTON_HEIGHT_PX) problems += "$where: ${b.height} px high, over $MAX_BUTTON_HEIGHT_PX (the label wrapped or the button was squeezed)"
    }
    val bounds: List<Pair<String, Rect>> = tags.map { it to node(it).fetchSemanticsNode().boundsInRoot }
    for (i in bounds.indices) for (j in i + 1 until bounds.size) {
        val (a, ra) = bounds[i]
        val (c, rc) = bounds[j]
        if (ra.overlaps(rc)) problems += "$a $ra overlaps $c $rc"
    }
    return problems
}

/** Fails with every problem of [intactProblems]. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertIntact(tags: List<String>, windowWidth: Int, windowHeight: Int, scroll: Boolean = false, minWidthPx: Float = MIN_BUTTON_WIDTH_PX) {
    val problems = intactProblems(tags, windowWidth, windowHeight, scroll, minWidthPx)
    kotlin.test.assertEquals(emptyList(), problems, "layout is not intact")
}
