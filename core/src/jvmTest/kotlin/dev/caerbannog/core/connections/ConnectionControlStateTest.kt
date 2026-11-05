package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.RefreshInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-05b step 2: the pure rule of the app bar chip and its popup, the table of "Connection control" in
 * docs/tasks/M2-05-stream-list-screen.md, one test per row. Contract: `connectionControlState(...)` and the types in
 * ConnectionControl.kt. Texts are asserted as LITERALS (not through the constants), so a rewording is a visible change.
 *
 * Decisions the test developer had to make where the brief is silent (listed in "Step 2 report, 05b" for the architect):
 *  - the accessible label is `Connection <name>, <state>, <permission>. Open connection menu`, the permission being the pill text
 *    with a lower-case first letter, as docs/ux/Streams.dc.html draws it ("Connection prod-eu, Connected, topology locked. ...");
 *    when the pill is hidden the permission part is left out; with no connection it is `No connection. Open connection menu`.
 *  - Connecting shows the pill as soon as the permission check has answered (the table says "hidden until the permission check answered").
 */
class ConnectionControlStateTest {

    private val uat = ConnectionProfile(name = "uat-eu", host = "u.example", virtualHost = "/", username = "svc")
    private val prod = ConnectionProfile(name = "prod-eu", host = "p.example", virtualHost = "/", username = "svc")
    private val staging = ConnectionProfile(name = "staging-eu", host = "s.example", virtualHost = "/", username = "svc")
    private val unlocked = PermissionCheckStatus.Done(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true))
    private val locked = PermissionCheckStatus.Done(PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false))

    private fun state(
        link: LinkState,
        current: CurrentConnection? = link.name?.let { CurrentConnection(it, unlocked) },
        profiles: List<ConnectionProfile> = listOf(uat, prod, staging),
        interval: RefreshInterval = RefreshInterval.Every5,
        updatedAt: Long? = null,
        now: Long = 100_000,
    ) = connectionControlState(profiles, current, link, interval, updatedAt, now)

    // --- the chip, one test per row of the table ---

    @Test
    fun `no connection - link_off, No connection, no pill, no error border`() {
        val chip = state(LinkState.Disconnected(), current = null).chip

        assertEquals(ChipState.NoConnection, chip.kind)
        assertEquals(ChipIcon.LinkOff, chip.icon)
        assertEquals("No connection", chip.text)
        assertNull(chip.pill)
        assertNull(chip.stateText)
        assertFalse(chip.errorBorder)
        assertEquals("No connection. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `connecting - sync icon, the name, no pill while the permission check has not answered`() {
        val chip = state(LinkState.Connecting("uat-eu"), current = CurrentConnection("uat-eu", PermissionCheckStatus.Checking)).chip

        assertEquals(ChipState.Connecting, chip.kind)
        assertEquals(ChipIcon.Sync, chip.icon)
        assertEquals("uat-eu", chip.text)
        assertNull(chip.pill, "the pill stays hidden until the permission check answered")
        assertFalse(chip.errorBorder)
        assertEquals("Connection uat-eu, Connecting. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `connecting - the pill appears as soon as the permission check answered`() {
        val chip = state(LinkState.Connecting("uat-eu"), current = CurrentConnection("uat-eu", locked)).chip

        assertEquals(PermissionPill("Topology locked", PillTone.LOCKED), chip.pill)
        assertEquals("Connection uat-eu, Connecting, topology locked. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `connected - link icon, the name and the permission pill`() {
        val chip = state(LinkState.Connected("uat-eu")).chip

        assertEquals(ChipState.Connected, chip.kind)
        assertEquals(ChipIcon.Link, chip.icon)
        assertEquals("uat-eu", chip.text)
        assertEquals(PermissionPill("Can change topology", PillTone.OK), chip.pill)
        assertFalse(chip.errorBorder)
        assertEquals("Connection uat-eu, Connected, can change topology. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `connected with a locked connection says Topology locked in the pill and in the label`() {
        val chip = state(LinkState.Connected("prod-eu"), current = CurrentConnection("prod-eu", locked)).chip

        assertEquals("Topology locked", chip.pill?.text)
        assertEquals("Connection prod-eu, Connected, topology locked. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `connected with a declared result keeps the declared wording`() {
        val declared = PermissionCheckStatus.Done(
            PermissionCheckResult.DeclaredByUser(
                topologyUnlocked = true, publishingEnabled = true, unreadable = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED),
            ),
        )
        val chip = state(LinkState.Connected("prod-eu"), current = CurrentConnection("prod-eu", declared)).chip

        assertEquals(PermissionPill("Can change topology (declared)", PillTone.WARNING), chip.pill)
    }

    @Test
    fun `failed - error icon and border, the name, no pill, the reason as tooltip`() {
        val chip = state(LinkState.Failed("uat-eu", "Credentials were refused")).chip

        assertEquals(ChipState.Failed, chip.kind)
        assertEquals(ChipIcon.Error, chip.icon)
        assertTrue(chip.errorBorder)
        assertEquals("uat-eu", chip.text)
        assertNull(chip.pill, "a failed connection shows no pill, even when its check answered")
        assertEquals("Credentials were refused", chip.tooltip)
        assertEquals("Connection uat-eu, Failed. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `disconnected current connection - link_off, the name and the word Disconnected, no pill`() {
        val chip = state(LinkState.Disconnected("uat-eu")).chip

        assertEquals(ChipState.Disconnected, chip.kind)
        assertEquals(ChipIcon.LinkOff, chip.icon)
        assertEquals("uat-eu", chip.text)
        assertEquals("Disconnected", chip.stateText)
        assertNull(chip.pill)
        assertFalse(chip.errorBorder)
        assertEquals("Connection uat-eu, Disconnected. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `a connection closed because its settings changed is Disconnected and offers Connect`() {
        val s = state(LinkState.Disconnected("uat-eu", ConnectionNotes.SETTINGS_CHANGED))

        assertEquals(ChipState.Disconnected, s.chip.kind)
        assertEquals(RowAction.Connect, s.rows.first { it.name == "uat-eu" }.action)
        assertEquals(ConnectionNotes.SETTINGS_CHANGED, s.chip.tooltip, "the note is the chip tooltip")
        assertEquals(ConnectionNotes.SETTINGS_CHANGED, s.rows.first { it.name == "uat-eu" }.note, "the note is also a line of the popup row")
    }

    @Test
    fun `a Disconnected link without a note has no tooltip and no row note`() {
        val s = state(LinkState.Disconnected("uat-eu"))

        assertNull(s.chip.tooltip)
        assertNull(s.rows.first { it.name == "uat-eu" }.note)
    }

    @Test
    fun `no current connection wins over a stale link name - after a delete the chip says No connection`() {
        // Delete: the session forgets the current connection, the link is Disconnected(name) without a note.
        val chip = state(LinkState.Disconnected("uat-eu"), current = null).chip

        assertEquals(ChipState.NoConnection, chip.kind)
        assertEquals("No connection", chip.text)
    }

    @Test
    fun `the chip texts are never empty and carry the name in the label, also for an unusual name`() {
        val odd = ConnectionProfile(name = "prod \"eu\" / ä 日本", host = "h", virtualHost = "/", username = "u")
        val chip = state(LinkState.Connected(odd.name), profiles = listOf(odd)).chip

        assertEquals(odd.name, chip.text)
        assertTrue(chip.accessibleLabel.startsWith("Connection ${odd.name}, Connected"), chip.accessibleLabel)
    }

    // --- the popup rows ---

    @Test
    fun `one row per saved profile in the list order, each with its state, icon and action`() {
        val rows = state(LinkState.Connected("prod-eu")).rows

        assertEquals(listOf("uat-eu", "prod-eu", "staging-eu"), rows.map { it.name })
        val byName = rows.associateBy { it.name }
        assertEquals(ConnectionRowState("prod-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true), byName.getValue("prod-eu"))
        assertEquals(ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false), byName.getValue("uat-eu"))
        assertEquals(ConnectionRowState("staging-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false), byName.getValue("staging-eu"))
    }

    @Test
    fun `a failed current row says Failed with the reason and offers Retry`() {
        val row = state(LinkState.Failed("staging-eu", "Credentials were refused")).rows.first { it.name == "staging-eu" }

        assertEquals(ConnectionRowState("staging-eu", ChipIcon.Error, "Failed: Credentials were refused", RowAction.Retry, current = true), row)
    }

    @Test
    fun `a connecting row says Connecting and offers no action`() {
        val row = state(LinkState.Connecting("uat-eu")).rows.first { it.name == "uat-eu" }

        assertEquals(ConnectionRowState("uat-eu", ChipIcon.Sync, "Connecting", RowAction.None, current = true), row)
    }

    @Test
    fun `only the current row is highlighted`() {
        val rows = state(LinkState.Connected("uat-eu")).rows

        assertEquals(listOf("uat-eu"), rows.filter { it.current }.map { it.name })
    }

    @Test
    fun `with nothing current every row is Disconnected with a Connect action and none is highlighted`() {
        val rows = state(LinkState.Disconnected(), current = null).rows

        assertEquals(listOf("uat-eu", "prod-eu", "staging-eu"), rows.map { it.name })
        assertTrue(rows.all { it.action == RowAction.Connect && it.stateText == "Disconnected" && !it.current })
    }

    @Test
    fun `a profile that failed earlier shows Disconnected once another one is current (no storage for old failures)`() {
        // Brief, Design notes: only the held (current) connection has a state other than Disconnected.
        val s = state(LinkState.Connected("prod-eu"))

        assertEquals("Disconnected", s.rows.first { it.name == "staging-eu" }.stateText)
    }

    @Test
    fun `no saved profile gives No saved connections and no rows`() {
        val s = state(LinkState.Disconnected(), current = null, profiles = emptyList())

        assertEquals(emptyList(), s.rows)
        assertEquals("No saved connections", s.emptyText)
        assertEquals("No connection", s.chip.text)
    }

    @Test
    fun `with saved profiles there is no empty text`() {
        assertNull(state(LinkState.Connected("uat-eu")).emptyText)
    }

    // --- the refresh section ---

    @Test
    fun `connected - the section is enabled, shows the selected interval and Updated N s ago`() {
        val r = state(LinkState.Connected("uat-eu"), interval = RefreshInterval.Every30, updatedAt = 88_000, now = 100_000).refresh

        assertEquals(RefreshSectionState(RefreshInterval.Every30, enabled = true, note = "Updated 12 s ago"), r)
    }

    @Test
    fun `connected but nothing loaded yet says Not loaded yet`() {
        val r = state(LinkState.Connected("uat-eu"), updatedAt = null).refresh

        assertTrue(r.enabled)
        assertEquals("Not loaded yet", r.note)
    }

    @Test
    fun `the age counts whole seconds down, and never goes negative`() {
        assertEquals("Updated 0 s ago", state(LinkState.Connected("uat-eu"), updatedAt = 100_000, now = 100_999).refresh.note)
        assertEquals("Updated 1 s ago", state(LinkState.Connected("uat-eu"), updatedAt = 100_000, now = 101_000).refresh.note)
        assertEquals("Updated 0 s ago", state(LinkState.Connected("uat-eu"), updatedAt = 100_500, now = 100_000).refresh.note, "a clock that went back")
    }

    @Test
    fun `every state but Connected disables the section with the note Connect to refresh and keeps the selected interval`() {
        val cases = listOf(
            LinkState.Disconnected() to null,
            LinkState.Disconnected("uat-eu") to CurrentConnection("uat-eu", unlocked),
            LinkState.Connecting("uat-eu") to CurrentConnection("uat-eu", unlocked),
            LinkState.Failed("uat-eu", "Connection lost") to CurrentConnection("uat-eu", unlocked),
        )
        for ((link, current) in cases) {
            val r = state(link, current = current, interval = RefreshInterval.Every10, updatedAt = 99_000).refresh
            assertEquals(RefreshSectionState(RefreshInterval.Every10, enabled = false, note = "Connect to refresh"), r, "link $link")
        }
    }

    @Test
    fun `the interval Off is a selection too`() {
        assertEquals(RefreshInterval.Off, state(LinkState.Connected("uat-eu"), interval = RefreshInterval.Off).refresh.selected)
    }

    @Test
    fun `the popup texts are the literals of the brief`() {
        assertEquals("No connection", ConnectionControlTexts.NO_CONNECTION)
        assertEquals("No saved connections", ConnectionControlTexts.NO_SAVED_CONNECTIONS)
        assertEquals("New connection", ConnectionControlTexts.NEW_CONNECTION)
        assertEquals("Connections", ConnectionControlTexts.SECTION_CONNECTIONS)
        assertEquals("Stream list refresh", ConnectionControlTexts.SECTION_REFRESH)
        assertEquals("Refresh every", ConnectionControlTexts.REFRESH_EVERY)
        assertEquals("Refresh now", ConnectionControlTexts.REFRESH_NOW)
        assertEquals("Connect to refresh", ConnectionControlTexts.CONNECT_TO_REFRESH)
        assertEquals("Not loaded yet", ConnectionControlTexts.NOT_LOADED_YET)
        assertEquals("Manage connections", ConnectionControlTexts.MANAGE_CONNECTIONS)
        assertEquals("Open connection menu", ConnectionControlTexts.OPEN_MENU)
    }
}
