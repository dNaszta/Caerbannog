package dev.caerbannog.ui.migrations

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How the Migrations page lays out the file list and the detail panel (M2-21b, owner decision A). */
internal sealed interface MigrationsLayout {
    /** List on the left (340 dp), detail on the right, as in the 1280 mockup. */
    data object SideBySide : MigrationsLayout

    /** List on top with a capped height and its own scroll, detail below at the full width, the page scrolls as a whole. */
    data object Stacked : MigrationsLayout
}

internal val MIGRATION_LIST_WIDTH: Dp = 340.dp
internal val MIGRATION_PAGE_GAP: Dp = 24.dp
internal val MIGRATION_PAGE_PADDING: Dp = 24.dp

/** The smallest detail panel (with its own padding) that still keeps every button at its label width when the buttons wrap. */
internal val MIGRATION_DETAIL_MIN_WIDTH: Dp = 260.dp

/** The refusal text of a plan that is not offered keeps at least this width to read; its buttons wrap below it. */
internal val PLAN_NOT_OFFERED_MIN_TEXT_WIDTH: Dp = 200.dp

/** How much the smallest detail panel grows per unit of font scale above 1 (dp), measured on the longest button label, "Copy into a new plan": 297 px wide at scale 1.5 with the pinned font, so the row needs 305 px and the panel 337 dp; 150 would leave 6 px, 160 leaves 11 (M2-21b). */
internal val MIGRATION_DETAIL_GROWTH_PER_SCALE: Dp = 160.dp

/** The smallest detail panel we accept side by side: 260 dp at font scale 1, wider as the font grows because labels grow and icons and paddings do not. */
internal fun migrationsMinDetail(fontScale: Float): Dp = MIGRATION_DETAIL_MIN_WIDTH + MIGRATION_DETAIL_GROWTH_PER_SCALE * (fontScale - 1f).coerceAtLeast(0f)

/** The width under which the page stacks: list 340 + gap 24 + page padding 2 x 24 + the smallest detail panel. 672 dp at font scale 1. */
internal fun migrationsStackBelow(fontScale: Float): Dp = MIGRATION_LIST_WIDTH + MIGRATION_PAGE_GAP + MIGRATION_PAGE_PADDING * 2 + migrationsMinDetail(fontScale)

/** The threshold at font scale 1. */
internal val MIGRATIONS_STACK_BELOW: Dp = migrationsStackBelow(1f)

/**
 * Decides the layout from the width the page content is given (the window minus the navigation drawer inside the app frame) and the font scale.
 * A pure function so the decision is tested without a screen.
 */
internal fun migrationsLayoutFor(width: Dp, fontScale: Float = 1f): MigrationsLayout =
    if (width < migrationsStackBelow(fontScale)) MigrationsLayout.Stacked else MigrationsLayout.SideBySide

internal val STACKED_LIST_MAX_HEIGHT: Dp = 240.dp
internal val STACKED_LIST_MIN_CAP: Dp = 160.dp
private const val STACKED_LIST_PAGE_SHARE = 0.35f

/** The most height the stacked file list takes: 240 dp, never over 35 percent of the page height, but at least 160 dp (about two cards) so a short window scrolls the page instead of hiding the list. */
internal fun stackedListMaxHeight(pageHeight: Dp): Dp = minOf(STACKED_LIST_MAX_HEIGHT, pageHeight * STACKED_LIST_PAGE_SHARE).coerceAtLeast(STACKED_LIST_MIN_CAP)

/** The smallest height of the detail panel in stacked mode, so a one-line file does not leave a sliver. */
internal val STACKED_DETAIL_MIN_HEIGHT: Dp = 360.dp
