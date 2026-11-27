package dev.caerbannog.ui.access

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.access.AccessScreen as Screen
import dev.caerbannog.core.access.AccessState
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.SecretGuard
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

/** M10-02: every state of the read-only Access screen (Who am I tab), through its stable test tags. The screen draws the strings of the state and decides none. */
@OptIn(ExperimentalTestApi::class)
class AccessScreenUiTest {

    private class Events : AccessEvents {
        val shown = mutableListOf<Boolean>()
        var refreshes = 0
        var retries = 0
        override fun setShown(shown: Boolean) { this.shown += shown }
        override fun refreshNow() { refreshes++ }
        override fun retry() { retries++ }
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

    private val loaded = AccessState(
        screen = Screen.Loaded,
        message = null,
        readOnlyNote = AccessTexts.READ_ONLY_NOTE,
        user = "alice",
        tags = listOf("monitoring", "management"),
        vhostsStatus = VhostsStatus.Listed,
        vhosts = listOf(VhostRowView("/", AccessTexts.CONNECTION_VHOST_NOTE), VhostRowView("orders", null)),
    )

    private fun DesktopComposeUiTest.show(state: AccessState, events: Events = Events(), updatedAt: Long? = null, now: Long = 10_000L): Events {
        setContent { AccessScreen(state, updatedAt, now, events, control, noop) }
        return events
    }

    private fun DesktopComposeUiTest.text(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.children.joinToString(" ") { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() }
        }

    private fun DesktopComposeUiTest.absent(vararg tags: String) = tags.forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }

    @Test
    fun `not connected shows the one message and no data`() = runDesktopComposeUiTest(1280, 800) {
        show(AccessState())
        assertTrue(AccessTexts.NOT_CONNECTED in text("accessMessage"))
        absent("accessScreen", "accessUser", "accessReadOnlyNote", "accessVhostsTable")
    }

    @Test
    fun `connecting shows the connecting message`() = runDesktopComposeUiTest(1280, 800) {
        show(AccessState(screen = Screen.Connecting, message = AccessTexts.CONNECTING))
        assertTrue(AccessTexts.CONNECTING in text("accessMessage"))
        absent("accessUser", "accessVhostsTable")
    }

    @Test
    fun `loading shows the tab, the read only note and loading rows, never a blank page`() = runDesktopComposeUiTest(1280, 800) {
        show(AccessState(screen = Screen.Loading, message = AccessTexts.LOADING, readOnlyNote = AccessTexts.READ_ONLY_NOTE))
        onNodeWithTag("accessTabWhoAmI").assertExists()
        assertTrue(AccessTexts.READ_ONLY_NOTE in text("accessReadOnlyNote"))
        onNodeWithTag("accessLoading").assertExists()
        assertTrue(onAllNodesWithTag("accessLoadingRow", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        absent("accessUser", "accessVhostsTable")
    }

    @Test
    fun `locked shows the title and the detail and no data, no virtual host list, no retry`() = runDesktopComposeUiTest(1280, 800) {
        show(AccessState(screen = Screen.Locked, message = AccessTexts.LOCKED_TITLE, detail = AccessTexts.LOCKED_DETAIL))
        assertTrue(AccessTexts.LOCKED_TITLE in text("accessMessage"))
        assertEquals(AccessTexts.LOCKED_DETAIL, text("accessLockedDetail"))
        absent("accessUser", "accessTags", "accessVhostsTable", "accessVhostsEmpty", "accessRetry", "accessReadOnlyNote")
    }

    @Test
    fun `failed shows the reason, a detail and a Retry that sends retry`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(AccessState(screen = Screen.Failed("HTTP 500"), message = AccessTexts.USER_ERROR, detail = AccessTexts.brokerText("HTTP 500"), action = AccessTexts.RETRY))
        assertTrue(AccessTexts.USER_ERROR in text("accessMessage"))
        assertEquals(AccessTexts.brokerText("HTTP 500"), text("accessFailedDetail"))
        onNodeWithTag("accessRetry").performClick()
        assertEquals(1, e.retries)
        absent("accessUser", "accessVhostsTable")
    }

    @Test
    fun `loaded shows user, tags in the broker order, the connection vhost first with its note, and the read only note`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        assertEquals("alice", text("accessUser"))
        onNodeWithTag("accessTag:monitoring").assertExists()
        onNodeWithTag("accessTag:management").assertExists()
        onNodeWithTag("accessVhostsTable").assertExists()
        onNodeWithTag("accessVhostRow:/").assertExists()
        onNodeWithTag("accessVhostRow:orders").assertExists()
        assertEquals(AccessTexts.CONNECTION_VHOST_NOTE, text("accessVhostNote:/"))
        assertEquals("", text("accessVhostNote:orders"))
        assertTrue(AccessTexts.READ_ONLY_NOTE in text("accessReadOnlyNote"))
        absent("accessTokenNote", "accessConnectionBanner", "accessRefusalBanner", "accessNotEncrypted", "accessVhostsEmpty", "accessVhostsError")
    }

    @Test
    fun `a token user gets the as the broker reports it note`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(tokenUser = true))
        assertEquals(AccessTexts.TOKEN_USER_NOTE, text("accessTokenNote"))
    }

    @Test
    fun `no tags shows the user and an empty tag row`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(tags = emptyList()))
        onNodeWithTag("accessUser").assertExists()
        onNodeWithTag("accessTags").assertExists()
    }

    @Test
    fun `an empty virtual host list says so and shows no table`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(vhostsStatus = VhostsStatus.Empty, vhosts = emptyList(), vhostsMessage = AccessTexts.NO_VHOSTS))
        assertEquals(AccessTexts.NO_VHOSTS, text("accessVhostsEmpty"))
        absent("accessVhostsTable", "accessVhostsError")
    }

    @Test
    fun `a loading virtual host list shows its loading line and the user stays`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(vhostsStatus = VhostsStatus.Loading, vhosts = emptyList()))
        onNodeWithTag("accessVhostsLoading").assertExists()
        onNodeWithTag("accessUser").assertExists()
        absent("accessVhostsTable")
    }

    @Test
    fun `an unreadable virtual host list shows the error, the mapped reason and its own Retry, the user stays`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded.copy(vhostsStatus = VhostsStatus.Unreadable, vhosts = emptyList(), vhostsMessage = AccessTexts.VHOSTS_ERROR, vhostsReason = "HTTP 500", action = AccessTexts.RETRY))
        assertEquals(AccessTexts.VHOSTS_ERROR, text("accessVhostsError"))
        assertEquals(AccessTexts.brokerText("HTTP 500"), text("accessVhostsReason"))
        onNodeWithTag("accessUser").assertExists()
        onNodeWithTag("accessVhostsRetry").performClick()
        assertEquals(1, e.retries)
        absent("accessVhostsTable")
    }

    @Test
    fun `a lost connection with data shows the banner with Retry, keeps the data, and disables refresh`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded.copy(stale = true, connectionBanner = "Connection lost", action = AccessTexts.RETRY))
        assertTrue(AccessTexts.CONNECTION_LOST_DETAIL in text("accessConnectionBanner"))
        onNodeWithTag("accessUser").assertExists()
        onNodeWithTag("accessVhostsTable").assertExists()
        onNodeWithTag("refreshIcon").assertIsNotEnabled()
        onNodeWithTag("accessContent").assertExists()
        assertEquals(0, e.refreshes)
    }

    @Test
    fun `a refused refresh shows the sign in refusal banner and keeps the last data`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(stale = true, refusalBanner = AccessTexts.SIGN_IN_REFUSED, action = AccessTexts.RETRY))
        assertTrue(AccessTexts.SIGN_IN_REFUSED in text("accessRefusalBanner"))
        onNodeWithTag("accessUser").assertExists()
        absent("accessConnectionBanner", "accessVhostsRetry")
    }

    // ---------------------------------------------------------------- M10-02b

    private val readError = loaded.copy(
        stale = true,
        readErrorBanner = "${AccessTexts.USER_ERROR} ${AccessTexts.brokerText("HTTP 500")}.",
        action = AccessTexts.RETRY,
    )

    @Test
    fun `an unreadable user with data shows the read-error banner with the reason and the last-read note, keeps the data and dims it`() = runDesktopComposeUiTest(1280, 800) {
        show(readError)
        val said = text("accessReadErrorBanner")
        assertTrue(AccessTexts.USER_ERROR in said, "banner text was: $said")
        assertTrue(AccessTexts.brokerText("HTTP 500") in said, "banner text was: $said")
        assertTrue(AccessTexts.CONNECTION_LOST_DETAIL in said, "banner text was: $said")
        onNodeWithTag("accessUser").assertExists()
        onNodeWithTag("accessVhostsTable").assertExists()
        onNodeWithTag("accessContent").assertExists()
        absent("accessConnectionBanner", "accessRefusalBanner")
    }

    @Test
    fun `the read-error banner has a Retry that sends retry`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(readError)
        onNodeWithTag("accessReadErrorBanner").assertExists()
        // The banner's own Retry button (same shape as the other banners, which tag the banner, not the button); the only Retry on this screen.
        onNodeWithText(AccessTexts.RETRY).performClick()
        assertEquals(1, e.retries, "Retry on the read-error banner must call retry once")
    }

    @Test
    fun `refresh is disabled while the read-error banner shows`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(readError)
        onNodeWithTag("refreshIcon").assertIsNotEnabled()
        assertEquals(0, e.refreshes)
    }

    @Test
    fun `without a read error there is no read-error banner`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        absent("accessReadErrorBanner")
    }

    @Test
    fun `the vhost list error does not draw a second Retry while the read-error banner shows`() = runDesktopComposeUiTest(1280, 800) {
        show(readError.copy(vhostsStatus = VhostsStatus.Unreadable, vhosts = emptyList(), vhostsMessage = AccessTexts.VHOSTS_ERROR, vhostsReason = "HTTP 500"))
        absent("accessVhostsRetry")
    }

    @Test
    fun `two hidden virtual host names are both drawn, each with its own row tag and its own note tag`() = runDesktopComposeUiTest(1280, 800) {
        val hidden = SecretGuard.HIDDEN
        show(loaded.copy(vhosts = listOf(VhostRowView("orders", null), VhostRowView(hidden, null), VhostRowView(hidden, null))))
        val rows = onAllNodesWithTag("accessVhostRow:$hidden:0", useUnmergedTree = true).fetchSemanticsNodes().size +
            onAllNodesWithTag("accessVhostRow:$hidden:1", useUnmergedTree = true).fetchSemanticsNodes().size +
            onAllNodesWithTag("accessVhostRow:$hidden:2", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(2, rows, "two hidden rows, each with a distinct tag accessVhostRow:hidden:<n> (n is the position)")
        onAllNodesWithTag("accessVhostRow:$hidden", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag("accessVhostNote:$hidden", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("accessVhostRow:orders").assertExists()
        onNodeWithTag("accessVhostNote:orders", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a plain profile shows the Not encrypted banner with its detail, a TLS one does not`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded.copy(notEncrypted = ConnectionNotes.NOT_ENCRYPTED))
        val node = onNodeWithTag("accessNotEncrypted", useUnmergedTree = true).fetchSemanticsNode()
        val said = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        assertTrue(ConnectionNotes.NOT_ENCRYPTED in said && AccessTexts.NOT_ENCRYPTED_DETAIL in said, "banner text was: $said")
    }

    @Test
    fun `with TLS there is no Not encrypted banner`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        absent("accessNotEncrypted")
    }

    @Test
    fun `refresh is enabled with data, sends refreshNow, and the age shows`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(loaded, updatedAt = 4_000L, now = 10_000L)
        onNodeWithTag("refreshIcon").assertIsEnabled().performClick()
        assertEquals(1, e.refreshes)
        onNodeWithTag("updatedAge").assertExists()
    }

    @Test
    fun `refresh is disabled while not loaded`() = runDesktopComposeUiTest(1280, 800) {
        show(AccessState(screen = Screen.Loading, message = AccessTexts.LOADING, readOnlyNote = AccessTexts.READ_ONLY_NOTE))
        onNodeWithTag("refreshIcon").assertIsNotEnabled()
    }

    @Test
    fun `the screen reports shown on entering and not shown on leaving`() = runDesktopComposeUiTest(1280, 800) {
        val e = Events()
        val on = androidx.compose.runtime.mutableStateOf(true)
        setContent { if (on.value) AccessScreen(loaded, null, 0L, e, control, noop) }
        waitForIdle()
        assertEquals(listOf(true), e.shown)
        on.value = false
        waitForIdle()
        assertEquals(listOf(true, false), e.shown)
    }

    @Test
    fun `the screen has no control that changes anything, only refresh and retry`() = runDesktopComposeUiTest(1280, 800) {
        show(loaded)
        absent("accessCreate", "accessDelete", "accessEdit", "accessSave")
    }
}
