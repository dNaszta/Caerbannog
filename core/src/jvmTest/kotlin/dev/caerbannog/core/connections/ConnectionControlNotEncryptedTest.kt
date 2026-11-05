package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.RefreshInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-14a step 2: "Not encrypted" in the app bar chip, its accessible label and the popup rows (decision O1, 2026-11-05: the chip shows it
 * in EVERY state of a plain profile, so the user sees it before pressing Connect). Contract (compile stub already there):
 * `ConnectionChipState.notEncrypted` and `ConnectionRowState.notEncrypted`, derived by `connectionControlState` from the `tls` of the
 * profile with the current connection's name (chip) or of the row's own profile (rows). The accessible label puts ", Not encrypted"
 * after the state and the permission part, before the full stop: `Connection dev-local, Connected, can change topology, Not encrypted. Open connection menu`.
 * Pinned where the brief is silent: with NO current connection (chip "No connection") there is no profile, so no warning on the chip;
 * the popup rows still show it for plain profiles.
 */
class ConnectionControlNotEncryptedTest {

    private val dev = ConnectionProfile(name = "dev-local", host = "localhost", virtualHost = "/", username = "admin", streamPort = 5552, managementPort = 15672, tls = false)
    private val uat = ConnectionProfile(name = "uat-eu", host = "u.example", virtualHost = "/", username = "svc")
    private val unlocked = PermissionCheckStatus.Done(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true))

    private fun chip(link: LinkState, current: CurrentConnection? = link.name?.let { CurrentConnection(it, unlocked) }) =
        connectionControlState(listOf(dev, uat), current, link, RefreshInterval.Every5, null, 100_000).chip

    private val plainStates: List<Pair<String, LinkState>> = listOf(
        "Connecting" to LinkState.Connecting("dev-local"),
        "Connected" to LinkState.Connected("dev-local"),
        "Failed" to LinkState.Failed("dev-local", "The broker could not be reached"),
        "Disconnected" to LinkState.Disconnected("dev-local"),
        "Disconnected with the settings note" to LinkState.Disconnected("dev-local", ConnectionNotes.SETTINGS_CHANGED),
    )

    @Test
    fun `the chip of a plain profile says Not encrypted in every state`() {
        val missing = plainStates.filter { (_, link) -> !chip(link).notEncrypted }.map { it.first }
        assertEquals(emptyList(), missing, "states whose chip did not say Not encrypted")
    }

    @Test
    fun `the accessible label of a plain chip ends with Not encrypted in every state`() {
        val wrong = plainStates.filter { (_, link) -> !chip(link).accessibleLabel.contains(", Not encrypted. Open connection menu") }
            .map { (n, link) -> "$n: ${chip(link).accessibleLabel}" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `the exact label of a connected plain profile with its permission`() {
        assertEquals(
            "Connection dev-local, Connected, can change topology, Not encrypted. Open connection menu",
            chip(LinkState.Connected("dev-local")).accessibleLabel,
        )
    }

    @Test
    fun `the exact label while the permission check has not answered`() {
        assertEquals(
            "Connection dev-local, Connecting, Not encrypted. Open connection menu",
            chip(LinkState.Connecting("dev-local"), CurrentConnection("dev-local", PermissionCheckStatus.Checking)).accessibleLabel,
        )
    }

    @Test
    fun `a TLS profile never says Not encrypted, in any state`() {
        listOf(
            LinkState.Connecting("uat-eu"), LinkState.Connected("uat-eu"), LinkState.Failed("uat-eu", "x"), LinkState.Disconnected("uat-eu"),
        ).forEach { link ->
            val chip = chip(link)
            assertFalse(chip.notEncrypted, "$link")
            assertFalse(chip.accessibleLabel.contains("Not encrypted", ignoreCase = true), chip.accessibleLabel)
        }
    }

    @Test
    fun `with no current connection the chip has no warning`() {
        val chip = chip(LinkState.Disconnected(), current = null)
        assertFalse(chip.notEncrypted)
        assertEquals("No connection. Open connection menu", chip.accessibleLabel)
    }

    @Test
    fun `a link state about another profile than the current one does not carry its warning over`() {
        // The held connection is uat-eu (TLS), the current one is dev-local (plain): the chip describes the current profile.
        val chip = chip(LinkState.Connected("uat-eu"), current = CurrentConnection("dev-local", unlocked))
        assertTrue(chip.notEncrypted, "the chip is about dev-local, a plain profile")
    }

    @Test
    fun `popup rows show Not encrypted for plain profiles in every state, also when not current`() {
        val rows = connectionControlState(listOf(dev, uat), current = null, link = LinkState.Disconnected(), refreshInterval = RefreshInterval.Every5, updatedAtMillis = null, nowMillis = 0).rows
        assertTrue(rows.first { it.name == "dev-local" }.notEncrypted, "a plain profile that is not current")
        assertFalse(rows.first { it.name == "uat-eu" }.notEncrypted)

        plainStates.forEach { (label, link) ->
            val row = connectionControlState(listOf(dev, uat), CurrentConnection("dev-local", unlocked), link, RefreshInterval.Every5, null, 0)
                .rows.first { it.name == "dev-local" }
            assertTrue(row.notEncrypted, "row in state $label")
        }
    }

    @Test
    fun `the warning text is one constant shared with the edit panel`() {
        assertEquals("Not encrypted", ConnectionNotes.NOT_ENCRYPTED)
    }
}
