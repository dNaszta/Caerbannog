package dev.caerbannog.ui.layout

import androidx.compose.runtime.Composable

/** Width of the permanent navigation drawer of the app frame (`AppFrameLayout`, drawn by the screen itself: 280 dp). */
internal const val DRAWER_WIDTH_DP = 280

/**
 * A documented no-op, kept so the tests keep reading "behind the drawer": [dev.caerbannog.ui.migrations.MigrationsScreen] already draws the app frame with its
 * own 280 dp drawer, so the screen alone is what the app shows. The first version of this wrapper put another blank column in front of it, which halved the page
 * and made the 1000 case a page with zero-size buttons (the "hang": performScrollTo on a zero-size node never settles). Kept, not removed, to avoid touching every call site.
 */
@Composable
internal fun WithDrawerStandIn(content: @Composable () -> Unit) = content()
