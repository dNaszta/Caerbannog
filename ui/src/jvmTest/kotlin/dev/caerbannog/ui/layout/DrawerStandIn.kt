package dev.caerbannog.ui.layout

import androidx.compose.runtime.Composable

/** Width of the permanent navigation drawer of the app frame (`AppFrameLayout`: 280 dp; the first list card of a 1280 window starts at x = 304 = 280 + 24 dp page padding). */
internal const val DRAWER_WIDTH_DP = 280

/**
 * M2-21b correction: [dev.caerbannog.ui.migrations.MigrationsScreen] already draws the app frame with its own 280 dp drawer, so the screen alone is what the
 * app shows. The first version of this stand-in put another 304 dp column in front of it, which gave the page 584 dp less than the window and made the
 * 1000 case a 416 dp page with zero-size buttons (the "hang": performScrollTo on a zero-size node never settles). It is now a pass-through, kept so the tests keep reading
 * "behind the drawer"; the real drawer is part of the screen.
 */
@Composable
internal fun WithDrawerStandIn(content: @Composable () -> Unit) = content()
