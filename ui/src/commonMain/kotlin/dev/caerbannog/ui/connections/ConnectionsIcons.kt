package dev.caerbannog.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.caerbannog.core.connections.ConnectionNotes
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.LockPerson
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.FolderOff
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
    LockOpen("lock_open"),
    LockPerson("lock_person"),
    TravelExplore("travel_explore"),
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
    ArrowBack("arrow_back"),
    Info("info"),
    ArrowUpward("arrow_upward"),
    ArrowDownward("arrow_downward"),
    ChevronLeft("chevron_left"),
    ChevronRight("chevron_right"),
    Close("close"),
    Dns("dns"),
    Block("block"),
    Inbox("inbox"),
    Warning("warning"),
    TableChart("table_chart"),
    SwapHoriz("swap_horiz"),
    FilterList("filter_list"),
    ContentCopy("content_copy"),
    Tag("tag"),
    Clear("clear"),
    Send("send"),
    PlayArrow("play_arrow"),
    Download("download"),
    Edit("edit"),
    Rule("rule"),
    Folder("folder"),
    FolderOpen("folder_open"),
    DriveFileMove("drive_file_move"),
    History("history"),
    RestartAlt("restart_alt"),
    Person("person"),
    MoreVert("more_vert"),
    Save("save"),
    FactCheck("fact_check"),
    DeleteSweep("delete_sweep"),
    Queue("queue"),
    Lightbulb("lightbulb"),
    FolderOff("folder_off"),
}

private fun AppIcon.vector(): ImageVector = when (this) {
    AppIcon.CrueltyFree -> Icons.Outlined.CrueltyFree
    AppIcon.Power -> Icons.Outlined.Power
    AppIcon.Add -> Icons.Outlined.Add
    AppIcon.Lock -> Icons.Outlined.Lock
    AppIcon.LockOpen -> Icons.Outlined.LockOpen
    AppIcon.LockPerson -> Icons.Outlined.LockPerson
    AppIcon.TravelExplore -> Icons.Outlined.TravelExplore
    AppIcon.VerifiedUser -> Icons.Outlined.VerifiedUser
    AppIcon.Help -> Icons.AutoMirrored.Outlined.Help
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
    AppIcon.ArrowBack -> Icons.AutoMirrored.Outlined.ArrowBack
    AppIcon.Info -> Icons.Outlined.Info
    AppIcon.ArrowUpward -> Icons.Outlined.ArrowUpward
    AppIcon.ArrowDownward -> Icons.Outlined.ArrowDownward
    AppIcon.ChevronLeft -> Icons.Outlined.ChevronLeft
    AppIcon.ChevronRight -> Icons.Outlined.ChevronRight
    AppIcon.Close -> Icons.Outlined.Close
    AppIcon.Dns -> Icons.Outlined.Dns
    AppIcon.Block -> Icons.Outlined.Block
    AppIcon.Inbox -> Icons.Outlined.Inbox
    AppIcon.Warning -> Icons.Outlined.Warning
    AppIcon.TableChart -> Icons.Outlined.TableChart
    AppIcon.SwapHoriz -> Icons.Outlined.SwapHoriz
    AppIcon.FilterList -> Icons.Outlined.FilterList
    AppIcon.ContentCopy -> Icons.Outlined.ContentCopy
    AppIcon.Tag -> Icons.Outlined.Tag
    AppIcon.Clear -> Icons.Outlined.Clear
    AppIcon.Send -> Icons.AutoMirrored.Outlined.Send
    AppIcon.PlayArrow -> Icons.Outlined.PlayArrow
    AppIcon.Download -> Icons.Outlined.FileDownload
    AppIcon.Edit -> Icons.Outlined.Edit
    AppIcon.Rule -> Icons.Outlined.Check
    AppIcon.Folder -> Icons.Outlined.Folder
    AppIcon.FolderOpen -> Icons.Outlined.FolderOpen
    AppIcon.DriveFileMove -> Icons.AutoMirrored.Outlined.DriveFileMove
    AppIcon.History -> Icons.Outlined.History
    AppIcon.RestartAlt -> Icons.Outlined.RestartAlt
    AppIcon.Person -> Icons.Outlined.Person
    AppIcon.MoreVert -> Icons.Outlined.MoreVert
    AppIcon.Save -> Icons.Outlined.Save
    AppIcon.FactCheck -> Icons.Outlined.FactCheck
    AppIcon.DeleteSweep -> Icons.Outlined.DeleteSweep
    AppIcon.Queue -> Icons.Outlined.Queue
    AppIcon.Lightbulb -> Icons.Outlined.Lightbulb
    AppIcon.FolderOff -> Icons.Outlined.FolderOff
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

/**
 * The "Not encrypted" warning of the chip and the popup rows: icon and text together (colour is never the only signal), one
 * wording from core. [tag] marks it for tests; the text is also a semantics property of the tagged node.
 */
@Composable
internal fun NotEncryptedNote(tag: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag(tag).clearAndSetSemantics { text = AnnotatedString(ConnectionNotes.NOT_ENCRYPTED) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConnectionIcon(AppIcon.LockOpen, SmallIconSize, tint = MaterialTheme.colorScheme.error)
        Text(ConnectionNotes.NOT_ENCRYPTED, style = style, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
    }
}
