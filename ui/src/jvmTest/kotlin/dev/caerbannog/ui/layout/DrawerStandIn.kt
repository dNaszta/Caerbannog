package dev.caerbannog.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Width of the permanent navigation drawer of the app frame (the review card of a 1280 window starts at x = 304, M2-21). */
internal const val DRAWER_WIDTH_DP = 304

/**
 * M2-21b: the Migrations screen measures the width it is given, and in the app that is the window minus the drawer. The older tests show the screen alone
 * (the whole window), which hides the defect at 800. This puts a blank column of the drawer's width in front of [content], so the screen gets what it gets in the app.
 */
@Composable
internal fun WithDrawerStandIn(content: @Composable () -> Unit) = Row(Modifier.fillMaxSize()) {
    Box(Modifier.width(DRAWER_WIDTH_DP.dp).fillMaxHeight())
    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
}
