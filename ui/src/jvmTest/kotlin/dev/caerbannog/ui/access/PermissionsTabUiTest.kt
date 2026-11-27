package dev.caerbannog.ui.access

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.access.AccessScreen as Screen
import dev.caerbannog.core.access.AccessState
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.DeclarationView
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.PermissionsScreen
import dev.caerbannog.core.access.PermissionsState
import dev.caerbannog.core.access.PermissionsTexts
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserOption
import dev.caerbannog.core.access.VhostRowView
import dev.caerbannog.core.access.VhostsStatus
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10-03 step 2: every state of the Permissions tab through its stable test tags (contract by the test developer; mockup `docs/ux/Access.dc.html`,
 * frames `perm-*`). The screen draws the strings of [PermissionsState] and decides none. Red until the tab is drawn.
 *
 * Tags the tab must have (keep stable): `accessTabPermissions` (the tab, next to `accessTabWhoAmI`), `accessPermissions` (body root), `accessPermissionsMessage`
 * (the one message of a screen with no table), `accessPermissionsDetail`, `accessPermissionsLoading`, `accessPermissionsHeading`, `accessPermissionsReadLabel`
 * ("Read from the broker"), `accessPermissionsDeclared` (the declaration block, with its label, values and note as text), `accessPermissionsRetry` (the Retry of
 * the failed frame), `accessUserChooser` (opens the chooser), `accessUserCurrent` (the chosen user), `accessUserFilter` (text field), `accessUserCount`,
 * `accessUserOption:<key>` (disabled for a hidden one), `accessVhostFilter` (text field), `accessPermissionsTable`, `accessPermissionRow:<vhost>`,
 * `accessCell:<vhost>:<configure|write|read>`, `accessTopicTable`, `accessTopicRow:<vhost>:<exchange>`, `accessTopicCell:<vhost>:<exchange>:<write|read>`.
 * The banners reuse the tags of the Who am I tab: `accessConnectionBanner`, `accessRefusalBanner`, `accessReadErrorBanner`, `accessNotEncrypted`, `accessContent`.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionsTabUiTest {

    private class Events : PermissionsEvents {
        val shown = mutableListOf<Boolean>()
        val chosen = mutableListOf<String>()
        val userFilters = mutableListOf<String>()
        val vhostFilters = mutableListOf<String>()
        var refreshes = 0
        var retries = 0
        override fun setShown(shown: Boolean) { this.shown += shown }
        override fun refreshNow() { refreshes++ }
        override fun retry() { retries++ }
        override fun chooseUser(key: String) { chosen += key }
        override fun setUserFilter(text: String) { userFilters += text }
        override fun setVhostFilter(text: String) { vhostFilters += text }
    }

    private class WhoEvents : AccessEvents {
        val shown = mutableListOf<Boolean>()
        override fun setShown(shown: Boolean) { this.shown += shown }
        override fun refreshNow() = Unit
        override fun retry() = Unit
    }

    private val noop = object : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    private val control = ConnectionControlState(
        chip = ConnectionChipState(ChipState.NoConnection, ChipIcon.LinkOff, "No connection", null, null, null, "No connection. Open connection menu", false),
        rows = emptyList(),
        emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every5, enabled = false, note = "Connect to refresh"),
    )

    private val who = AccessState(
        screen = Screen.Loaded, message = null, readOnlyNote = AccessTexts.READ_ONLY_NOTE, user = "ops-admin", tags = listOf("administrator"),
        vhostsStatus = VhostsStatus.Listed, vhosts = listOf(VhostRowView("/orders", null)),
    )

    private fun cell(text: String) = PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))

    private val users = listOf(UserOption("svc-billing", "svc-billing", false), UserOption("svc-orders", "svc-orders", false))

    private val loaded = PermissionsState(
        screen = PermissionsScreen.Loaded,
        message = null,
        heading = "Permissions of svc-orders",
        readLabel = PermissionsTexts.READ_FROM_BROKER,
        canChooseUser = true,
        users = users,
        userCountText = "2 of 2 users",
        chosenUser = "svc-orders",
        rows = listOf(
            PermissionRow("/billing", cell(""), cell("^invoices$"), cell("^invoices$")),
            PermissionRow("/orders", cell("^queue\\..*"), cell("^(ex|queue)\\..*"), cell(".*")),
        ),
        topicRows = listOf(
            TopicPermissionRow("/orders", "amq.topic", cell("^orders\\..*"), cell("^orders\\..*")),
            TopicPermissionRow("/orders", "ex.events", cell(""), cell(".*")),
        ),
    )

    private fun DesktopComposeUiTest.show(state: PermissionsState, events: Events = Events(), who: WhoEvents = WhoEvents(), width: Int = 1280): Events {
        setContent { AccessScreen(this@PermissionsTabUiTest.who, null, 10_000L, who, control, noop, permissions = state, permissionsEvents = events) }
        onNodeWithTag("accessTabPermissions").performClick()
        return events
    }

    private fun DesktopComposeUiTest.text(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.children.joinToString(" ") { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() }
        }

    private fun DesktopComposeUiTest.absent(vararg tags: String) = tags.forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }

    // ---------------------------------------------------------------- the tab

    @Test
    fun `the tab row has Who am I and Permissions, selecting Permissions starts its reads and selecting Who am I stops them`() = runDesktopComposeUiTest(1280, 800) {
        val events = Events()
        setContent { AccessScreen(who, null, 10_000L, WhoEvents(), control, noop, permissions = loaded, permissionsEvents = events) }
        onNodeWithTag("accessTabWhoAmI").assertExists()
        onNodeWithTag("accessTabPermissions").assertExists()
        assertEquals(PermissionsTexts.TAB, text("accessTabPermissions"))
        assertEquals(emptyList(), events.shown, "the tab is not shown until it is selected (D3)")
        onNodeWithTag("accessTabPermissions").performClick()
        waitForIdle()
        assertEquals(listOf(true), events.shown)
        onNodeWithTag("accessPermissions").assertExists()
        onNodeWithTag("accessTabWhoAmI").performClick()
        waitForIdle()
        assertEquals(listOf(true, false), events.shown)
        absent("accessPermissions")
        onNodeWithTag("accessUser").assertExists()
    }

    // ---------------------------------------------------------------- loaded

    @Test
    fun `loaded shows the heading, the broker-read label, the chosen user, the three patterns per host and the topic table`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        assertEquals("Permissions of svc-orders", text("accessPermissionsHeading"))
        assertEquals(PermissionsTexts.READ_FROM_BROKER, text("accessPermissionsReadLabel"))
        assertEquals("svc-orders", text("accessUserCurrent"))
        onNodeWithTag("accessPermissionsTable").assertExists()
        assertEquals("^queue\\..*", text("accessCell:/orders:configure"))
        assertEquals("^(ex|queue)\\..*", text("accessCell:/orders:write"))
        assertEquals(".*", text("accessCell:/orders:read"))
        onNodeWithTag("accessTopicTable").assertExists()
        assertEquals("^orders\\..*", text("accessTopicCell:/orders:amq.topic:write"))
        assertEquals(".*", text("accessTopicCell:/orders:ex.events:read"))
        absent("accessPermissionsDeclared", "accessPermissionsMessage", "accessConnectionBanner", "accessNotEncrypted", "accessReadErrorBanner")
    }

    @Test
    fun `an empty pattern is drawn as no permission in the table and in the topic table`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        assertEquals(PermissionsTexts.NO_PERMISSION, text("accessCell:/billing:configure"))
        assertEquals("^invoices$", text("accessCell:/billing:write"))
        assertEquals(PermissionsTexts.NO_PERMISSION, text("accessTopicCell:/orders:ex.events:write"))
    }

    @Test
    fun `a hidden pattern is drawn as the hidden marker`() = runDesktopComposeUiTest(1280, 800) {
        val hiddenCell = PatternCell(SecretGuard.HIDDEN, PatternReading.HasPattern(SecretGuard.HIDDEN), hidden = true)
        show(loaded.copy(rows = listOf(PermissionRow("/orders", hiddenCell, cell(".*"), cell(".*")))))
        assertEquals(SecretGuard.HIDDEN, text("accessCell:/orders:configure"))
    }

    @Test
    fun `a topic table is absent when the user has no topic permission`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(topicRows = emptyList()))
        onNodeWithTag("accessPermissionsTable").assertExists()
        absent("accessTopicTable")
    }

    @Test
    fun `the virtual host filter box sends what is typed`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded)
        onNodeWithTag("accessVhostFilter").performTextInput("bill")
        assertEquals("bill", e.vhostFilters.last())
    }

    @Test
    fun `no control on the tab changes anything`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        for (word in listOf("Delete", "Remove", "Edit", "Save", "Add", "Create", "Grant", "Revoke", "Reset")) {
            onAllNodesWithTag("accessPermissions", useUnmergedTree = true).assertCountEquals(1)
            assertTrue(onAllNodesWithText(word, substring = false).fetchSemanticsNodes().isEmpty(), "a control named $word")
        }
    }

    // ---------------------------------------------------------------- the chooser

    @Test
    fun `the chooser lists the options with the count, choosing one sends its key`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded)
        onNodeWithTag("accessUserChooser").performClick()
        onNodeWithTag("accessUserOption:svc-billing").assertExists()
        onNodeWithTag("accessUserOption:svc-orders").assertExists()
        assertEquals("2 of 2 users", text("accessUserCount"))
        onNodeWithTag("accessUserOption:svc-billing").performClick()
        assertEquals(listOf("svc-billing"), e.chosen)
    }

    @Test
    fun `the chooser filter box sends what is typed and shows the N of M count`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded.copy(users = listOf(users[0]), userFilter = "svc-b", userCountText = "1 of 2 users"))
        onNodeWithTag("accessUserChooser").performClick()
        onNodeWithTag("accessUserFilter").performTextInput("x")
        assertTrue(e.userFilters.isNotEmpty())
        assertEquals("1 of 2 users", text("accessUserCount"))
        onAllNodesWithTag("accessUserOption:svc-orders", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a hidden user option is drawn as hidden, is disabled and sends nothing`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded.copy(users = users + UserOption(SecretGuard.HIDDEN, "hidden:2", true)))
        onNodeWithTag("accessUserChooser").performClick()
        assertEquals(SecretGuard.HIDDEN, text("accessUserOption:hidden:2"))
        onNodeWithTag("accessUserOption:hidden:2").assertIsNotEnabled()
        onNodeWithTag("accessUserOption:hidden:2").performClick()
        assertEquals(emptyList(), e.chosen)
    }

    @Test
    fun `there is no chooser when the user cannot choose`() = runDesktopComposeUiTest(1280, 800) {
        show(
            PermissionsState(
                screen = PermissionsScreen.Locked, message = PermissionsTexts.LOCKED_TITLE, detail = PermissionsTexts.lockedDetail("svc-explorer"),
            ),
        )
        absent("accessUserChooser", "accessUserFilter", "accessVhostFilter", "accessPermissionsTable")
    }

    // ---------------------------------------------------------------- the other frames

    @Test
    fun `loading shows the loading text, never a blank page`() = runDesktopComposeUiTest(1280, 800) {
        show(PermissionsState(screen = PermissionsScreen.Loading, message = PermissionsTexts.LOADING))
        onNodeWithTag("accessPermissionsLoading").assertExists()
        assertTrue(PermissionsTexts.LOADING in text("accessPermissionsLoading"))
        absent("accessPermissionsTable")
    }

    @Test
    fun `empty shows the message of the user and keeps the chooser, no error`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(screen = PermissionsScreen.Empty, message = PermissionsTexts.empty("svc-idle"), heading = null, readLabel = null, rows = emptyList(), topicRows = emptyList(), chosenUser = "svc-idle"))
        assertTrue(PermissionsTexts.empty("svc-idle") in text("accessPermissionsMessage"))
        onNodeWithTag("accessUserChooser").assertExists()
        absent("accessPermissionsTable", "accessPermissionsRetry", "accessReadErrorBanner")
    }

    @Test
    fun `locked with a declaration shows the lock, the declaration labelled as declared, and no broker label`() = runDesktopComposeUiTest(1280, 800) {
        show(
            PermissionsState(
                screen = PermissionsScreen.Locked, message = PermissionsTexts.LOCKED_TITLE, detail = PermissionsTexts.lockedDetail("svc-explorer"),
                declaration = DeclarationView(PermissionsTexts.DECLARED_LABEL, "no", "yes", "not declared", PermissionsTexts.DECLARED_NOTE),
            ),
        )
        assertTrue(PermissionsTexts.LOCKED_TITLE in text("accessPermissionsMessage"))
        assertEquals(PermissionsTexts.lockedDetail("svc-explorer"), text("accessPermissionsDetail"))
        val declared = text("accessPermissionsDeclared")
        for (part in listOf(PermissionsTexts.DECLARED_HEADING, PermissionsTexts.DECLARED_LABEL, "no", "yes", "not declared", PermissionsTexts.DECLARED_NOTE)) {
            assertTrue(part in declared, "'$part' is missing from the declaration: $declared")
        }
        absent("accessPermissionsReadLabel", "accessPermissionsTable")
        assertTrue(PermissionsTexts.READ_FROM_BROKER !in declared, "the declaration must not use the broker-read wording")
    }

    @Test
    fun `locked without a declaration shows no declaration block`() = runDesktopComposeUiTest(1280, 800) {
        show(PermissionsState(screen = PermissionsScreen.Locked, message = PermissionsTexts.LOCKED_TITLE, detail = PermissionsTexts.LOCKED_DETAIL_NO_DECLARATION))
        assertEquals(PermissionsTexts.LOCKED_DETAIL_NO_DECLARATION, text("accessPermissionsDetail"))
        absent("accessPermissionsDeclared", "accessPermissionsReadLabel")
    }

    @Test
    fun `a token user shows the token text, the chooser of a token administrator, and no table or error`() = runDesktopComposeUiTest(1280, 800) {
        show(
            loaded.copy(
                screen = PermissionsScreen.TokenUser, message = PermissionsTexts.TOKEN_TITLE, detail = PermissionsTexts.tokenDetail("jdoe"),
                heading = null, readLabel = null, rows = emptyList(), topicRows = emptyList(), chosenUser = "jdoe",
            ),
        )
        assertTrue(PermissionsTexts.TOKEN_TITLE in text("accessPermissionsMessage"))
        assertEquals(PermissionsTexts.tokenDetail("jdoe"), text("accessPermissionsDetail"))
        onNodeWithTag("accessUserChooser").assertExists()
        absent("accessPermissionsTable", "accessPermissionsRetry", "accessReadErrorBanner", "accessPermissionsReadLabel")
    }

    @Test
    fun `failed shows the message, the broker text and Retry that sends retry, and the chooser stays`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(
            loaded.copy(
                screen = PermissionsScreen.Failed("HTTP 500"), message = PermissionsTexts.ERROR, detail = AccessTexts.brokerText("HTTP 500"),
                heading = null, readLabel = null, rows = emptyList(), topicRows = emptyList(), action = AccessTexts.RETRY,
            ),
        )
        assertTrue(PermissionsTexts.ERROR in text("accessPermissionsMessage"))
        assertEquals(AccessTexts.brokerText("HTTP 500"), text("accessPermissionsDetail"))
        onNodeWithTag("accessUserChooser").assertExists()
        onNodeWithTag("accessPermissionsRetry").performClick()
        assertEquals(1, e.retries)
        absent("accessPermissionsTable")
    }

    @Test
    fun `stale with a lost connection keeps the table, shows the banner with Retry and dims the content`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded.copy(stale = true, connectionBanner = "Connection lost", action = AccessTexts.RETRY))
        assertTrue(AccessTexts.CONNECTION_LOST_DETAIL in text("accessConnectionBanner"))
        onNodeWithTag("accessPermissionsTable").assertExists()
        onNodeWithTag("accessContent").assertExists()
        onNodeWithText(AccessTexts.RETRY).performClick()
        assertEquals(1, e.retries)
    }

    @Test
    fun `an unreadable refresh keeps the table and shows the read-error banner with the reason`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(stale = true, readErrorBanner = "${PermissionsTexts.ERROR} ${AccessTexts.brokerText("HTTP 500")}.", action = AccessTexts.RETRY))
        val said = text("accessReadErrorBanner")
        assertTrue(PermissionsTexts.ERROR in said && AccessTexts.brokerText("HTTP 500") in said, "banner text was: $said")
        onNodeWithTag("accessPermissionsTable").assertExists()
    }

    @Test
    fun `a refused refresh shows the refusal banner and keeps the table`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(stale = true, refusalBanner = AccessTexts.SIGN_IN_REFUSED, action = AccessTexts.RETRY))
        assertTrue(AccessTexts.SIGN_IN_REFUSED in text("accessRefusalBanner"))
        onNodeWithTag("accessPermissionsTable").assertExists()
    }

    @Test
    fun `Not encrypted shows its banner on the Permissions tab too`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(notEncrypted = ConnectionNotes.NOT_ENCRYPTED))
        assertTrue(ConnectionNotes.NOT_ENCRYPTED in text("accessNotEncrypted"))
    }

    @Test
    fun `a narrow window still draws every part of the table`() = runDesktopComposeUiTest(800, 600) {
        show(loaded)
        onNodeWithTag("accessPermissionsTable").assertExists()
        onNodeWithTag("accessCell:/orders:read").assertExists()
        onNodeWithTag("accessTopicTable").assertExists()
        onNodeWithTag("accessUserChooser").assertExists()
    }

    @Test
    fun `long virtual host names and patterns do not drop a cell`() = runDesktopComposeUiTest(800, 600) {
        val long = "^" + "very-long-pattern-".repeat(12) + "$"
        val vhost = "/" + "a-very-long-virtual-host-name-".repeat(6)
        show(loaded.copy(rows = listOf(PermissionRow(vhost, cell(long), cell(long), cell(long))), topicRows = emptyList()))
        assertEquals(long, text("accessCell:$vhost:configure"))
    }

    // ---------------------------------------------------------------- M10-03b item 2: each view model reads only while its own tab is selected (D3)

    @Test
    fun `selecting the Permissions tab reports Who am I as not shown and Permissions as shown, and the reverse on selecting Who am I`() = runDesktopComposeUiTest(1280, 800) {
        val perm = Events()
        val whoEvents = WhoEvents()
        setContent { AccessScreen(who, null, 10_000L, whoEvents, control, noop, permissions = loaded, permissionsEvents = perm) }
        waitForIdle()
        assertEquals(listOf(true), whoEvents.shown, "Who am I is the first tab and reads on entering")
        assertEquals(emptyList(), perm.shown)
        onNodeWithTag("accessTabPermissions").performClick()
        waitForIdle()
        assertEquals(false, whoEvents.shown.last(), "Who am I must stop polling whoami and vhosts while Permissions is selected: ${whoEvents.shown}")
        assertEquals(listOf(true), perm.shown)
        onNodeWithTag("accessTabWhoAmI").performClick()
        waitForIdle()
        assertEquals(true, whoEvents.shown.last(), "Who am I reads again when its tab is selected again: ${whoEvents.shown}")
        assertEquals(listOf(true, false), perm.shown)
    }

    @Test
    fun `leaving the screen while Permissions is selected leaves both view models not shown`() = runDesktopComposeUiTest(1280, 800) {
        val perm = Events()
        val whoEvents = WhoEvents()
        val on = androidx.compose.runtime.mutableStateOf(true)
        setContent { if (on.value) AccessScreen(who, null, 10_000L, whoEvents, control, noop, permissions = loaded, permissionsEvents = perm) }
        onNodeWithTag("accessTabPermissions").performClick()
        waitForIdle()
        on.value = false
        waitForIdle()
        assertEquals(false, whoEvents.shown.last(), "${whoEvents.shown}")
        assertEquals(false, perm.shown.last(), "${perm.shown}")
        assertTrue(whoEvents.shown.count { it } <= whoEvents.shown.count { !it }, "every shown is matched by a not shown: ${whoEvents.shown}")
    }

    @Test
    fun `leaving the screen on the Who am I tab never reports the Permissions tab as shown`() = runDesktopComposeUiTest(1280, 800) {
        val perm = Events()
        val on = androidx.compose.runtime.mutableStateOf(true)
        setContent { if (on.value) AccessScreen(who, null, 10_000L, WhoEvents(), control, noop, permissions = loaded, permissionsEvents = perm) }
        waitForIdle()
        on.value = false
        waitForIdle()
        assertTrue(true !in perm.shown, "${perm.shown}")
    }

    // ---------------------------------------------------------------- M10-03b item 3: the words come from PermissionsTexts

    @Test
    fun `the table headings, filter hints and note are the words of PermissionsTexts`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        for (word in listOf(PermissionsTexts.VHOST_COLUMN, PermissionsTexts.CONFIGURE_COLUMN, PermissionsTexts.WRITE_COLUMN, PermissionsTexts.READ_COLUMN, PermissionsTexts.EXCHANGE_COLUMN)) {
            assertTrue(onAllNodesWithText(word, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "column word '$word' is not drawn")
        }
        onNodeWithText(PermissionsTexts.PATTERN_NOTE, substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText(PermissionsTexts.TOPIC_HEADING, useUnmergedTree = true).assertExists()
        onNodeWithText(PermissionsTexts.VHOST_FILTER_HINT, useUnmergedTree = true).assertExists()
    }

    // ---------------------------------------------------------------- M10-03b item 1 at screen level

    @Test
    fun `the no-user frame shows the message, no broker text line and a Retry that sends retry`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(PermissionsState(screen = PermissionsScreen.Failed(PermissionsTexts.NO_USER), message = PermissionsTexts.NO_USER, detail = null, action = AccessTexts.RETRY))
        assertEquals(PermissionsTexts.NO_USER, text("accessPermissionsMessage"))
        absent("accessPermissionsDetail", "accessPermissionsTable")
        onNodeWithTag("accessPermissionsRetry").performClick()
        assertEquals(1, e.retries)
    }
}
