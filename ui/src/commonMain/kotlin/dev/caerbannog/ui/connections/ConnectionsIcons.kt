package dev.caerbannog.ui.connections

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CrueltyFree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiTethering
import dev.caerbannog.core.connections.PillTone

// The only file that imports androidx.compose.material.icons: material-icons-extended is frozen at 1.7.3, so a later
// move to Compose resources replaces this file alone (docs/tasks/M1-12b-connections-icons.md).

/** Icons of the app frame, the chip and the Connections screen, named as in `docs/ux/Main.dc.html` (Material Symbols names). */
internal enum class AppIcon(val mockupName: String) {
    CrueltyFree("cruelty_free"),
    Power("power"),
    Add("add"),
    Lock("lock"),
    VerifiedUser("verified_user"),
    Help("help"),
    CheckCircle("check_circle"),
    Delete("delete"),
    WifiTethering("wifi_tethering"),
    UploadFile("upload_file"),
    Visibility("visibility"),
    VisibilityOff("visibility_off"),
    Link("link"),
    LinkOff("link_off"),
    Sync("sync"),
    Error("error"),
    ArrowDropDown("arrow_drop_down"),
    Refresh("refresh"),
    Check("check"),
    Search("search"),
    Stream("stream"),
    Hub("hub"),
    AccountTree("account_tree"),
    ArrowUpward("arrow_upward"),
    ArrowDownward("arrow_downward"),
    ChevronLeft("chevron_left"),
    ChevronRight("chevron_right"),
}

private fun AppIcon.vector(): ImageVector = when (this) {
    AppIcon.CrueltyFree -> Icons.Outlined.CrueltyFree
    AppIcon.Power -> Icons.Outlined.Power
    AppIcon.Add -> Icons.Outlined.Add
    AppIcon.Lock -> Icons.Outlined.Lock
    AppIcon.VerifiedUser -> Icons.Outlined.VerifiedUser
    AppIcon.Help -> Icons.Outlined.Help
    AppIcon.CheckCircle -> Icons.Outlined.CheckCircle
    AppIcon.Delete -> Icons.Outlined.Delete
    AppIcon.WifiTethering -> Icons.Outlined.WifiTethering
    AppIcon.UploadFile -> Icons.Outlined.UploadFile
    AppIcon.Visibility -> Icons.Outlined.Visibility
    AppIcon.VisibilityOff -> Icons.Outlined.VisibilityOff
    AppIcon.Link -> Icons.Outlined.Link
    AppIcon.LinkOff -> Icons.Outlined.LinkOff
    AppIcon.Sync -> Icons.Outlined.Sync
    AppIcon.Error -> Icons.Outlined.Error
    AppIcon.ArrowDropDown -> Icons.Outlined.ArrowDropDown
    AppIcon.Refresh -> Icons.Outlined.Refresh
    AppIcon.Check -> Icons.Outlined.Check
    AppIcon.Search -> Icons.Outlined.Search
    AppIcon.Stream -> Icons.Outlined.Stream
    AppIcon.Hub -> Icons.Outlined.Hub
    AppIcon.AccountTree -> Icons.Outlined.AccountTree
    AppIcon.ArrowUpward -> Icons.Outlined.ArrowUpward
    AppIcon.ArrowDownward -> Icons.Outlined.ArrowDownward
    AppIcon.ChevronLeft -> Icons.Outlined.ChevronLeft
    AppIcon.ChevronRight -> Icons.Outlined.ChevronRight
}

/** `.ms.sm` in the mockup. */
internal val SmallIconSize: Dp = 18.dp

/** `.ms` in the mockup. */
internal val IconSize: Dp = 24.dp

/** The icon follows the tone, not the text, so the pill and the banner of one state agree. NEUTRAL has none. */
internal fun PillTone.icon(): AppIcon? = when (this) {
    PillTone.OK -> AppIcon.VerifiedUser
    PillTone.WARNING -> AppIcon.Help
    PillTone.LOCKED -> AppIcon.Lock
    PillTone.NEUTRAL -> null
}

/**
 * One icon, tagged `icon:<mockup name>` for tests. Decorative by default ([contentDescription] null: the text beside it
 * carries the meaning); only functional icons such as the password toggle pass a description.
 */
@Composable
internal fun ConnectionIcon(
    icon: AppIcon,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = icon.vector(),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size).testTag("icon:${icon.mockupName}"),
    )
}
