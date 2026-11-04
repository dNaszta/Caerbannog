package dev.caerbannog.core.connections

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1-07: the exact pill and banner texts and tones from the table in docs/tasks/M1-07-permission-state-ui.md.
 * Pure core logic: `permissionPill(status)` and `permissionBanner(profile, status)`. Red state: the file
 * PermissionSummary.kt and `Unreadable.reason` do not exist yet.
 */
class PermissionSummaryTest {

    private val profile = ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/orders", username = "svc-explorer")
    private val withDeclaration = profile.copy(declaredPermissions = DeclaredPermissions(configure = true, write = true))

    private fun done(result: PermissionCheckResult) = PermissionCheckStatus.Done(result)

    private val unlockedWithPublishing = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    private val unlockedNoPublishing = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = false)
    private val locked = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)

    private val dimNote = "Your declaration is not used, because the broker answered."
    private val declaredDetailFirstLine = "The broker could not confirm it and still enforces its own permissions."

    // --- pill ---------------------------------------------------------------------------------

    @Test
    fun `not checked pill`() {
        assertEquals(PermissionPill("Not checked", PillTone.NEUTRAL), permissionPill(null))
    }

    @Test
    fun `checking pill`() {
        assertEquals(PermissionPill("Checking...", PillTone.NEUTRAL), permissionPill(PermissionCheckStatus.Checking))
    }

    @Test
    fun `verified unlocked pill is green`() {
        assertEquals(PermissionPill("Can change topology", PillTone.OK), permissionPill(done(unlockedWithPublishing)))
        assertEquals(PermissionPill("Can change topology", PillTone.OK), permissionPill(done(unlockedNoPublishing)))
    }

    @Test
    fun `verified locked pill is red`() {
        assertEquals(PermissionPill("Topology locked", PillTone.LOCKED), permissionPill(done(locked)))
    }

    @Test
    fun `declared unlocked pill is a warning, never green`() {
        val pill = permissionPill(done(PermissionCheckResult.DeclaredByUser(true, true, PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED))))
        assertEquals(PermissionPill("Can change topology (declared)", PillTone.WARNING), pill)
    }

    @Test
    fun `declared locked pill is a warning, not red`() {
        val pill = permissionPill(done(PermissionCheckResult.DeclaredByUser(false, true, PermissionCheckResult.Unreadable(UnreadableCause.TIMEOUT))))
        assertEquals(PermissionPill("Topology locked (declared)", PillTone.WARNING), pill)
    }

    @Test
    fun `unreadable pill is red for every cause`() {
        UnreadableCause.entries.forEach { cause ->
            assertEquals(
                PermissionPill("Topology locked", PillTone.LOCKED),
                permissionPill(done(PermissionCheckResult.Unreadable(cause))),
                "cause $cause",
            )
        }
    }

    // --- banner -------------------------------------------------------------------------------

    @Test
    fun `not checked banner`() {
        val banner = permissionBanner(profile, null)
        assertEquals(PillTone.NEUTRAL, banner.tone)
        assertEquals("Permission check has not run yet.", banner.headline)
        assertNull(banner.dimNote)
    }

    @Test
    fun `checking banner`() {
        val banner = permissionBanner(profile, PermissionCheckStatus.Checking)
        assertEquals(PillTone.NEUTRAL, banner.tone)
        assertEquals("Checking permissions...", banner.headline)
        assertNull(banner.dimNote)
    }

    @Test
    fun `verified unlocked banner with publishing enabled`() {
        val banner = permissionBanner(profile, done(unlockedWithPublishing))
        assertEquals(PillTone.OK, banner.tone)
        assertEquals("svc-explorer can change topology on virtual host /orders.", banner.headline)
        assertEquals(
            "The broker confirmed it, so topology screens and migrations are unlocked for this connection. " +
                "Test publishing is enabled.",
            banner.detail,
        )
        assertNull(banner.dimNote)
    }

    @Test
    fun `verified unlocked banner with publishing disabled`() {
        val banner = permissionBanner(profile, done(unlockedNoPublishing))
        assertEquals(PillTone.OK, banner.tone)
        assertEquals("svc-explorer can change topology on virtual host /orders.", banner.headline)
        assertEquals(
            "The broker confirmed it, so topology screens and migrations are unlocked for this connection. " +
                "Test publishing is disabled: no write permission.",
            banner.detail,
        )
    }

    @Test
    fun `verified locked banner`() {
        val banner = permissionBanner(profile, done(locked))
        assertEquals(PillTone.LOCKED, banner.tone)
        assertEquals("svc-explorer cannot change topology on virtual host /orders.", banner.headline)
        assertEquals(
            "The broker reports no configure permission, so topology screens open read-only. " +
                "Streams can still be browsed.",
            banner.detail,
        )
    }

    private fun declared(configure: Boolean, write: Boolean, cause: UnreadableCause = UnreadableCause.NOT_AUTHORIZED) =
        done(PermissionCheckResult.DeclaredByUser(configure, write, PermissionCheckResult.Unreadable(cause)))

    @Test
    fun `declared banner names the claim for each combination`() {
        val expected = listOf(
            Triple(true, true, "can change topology and can publish test messages"),
            Triple(true, false, "can change topology"),
            Triple(false, true, "can publish test messages"),
            Triple(false, false, "has no topology or publishing rights"),
        )
        expected.forEach { (configure, write, claim) ->
            val banner = permissionBanner(withDeclaration, declared(configure, write))
            assertEquals(PillTone.WARNING, banner.tone, "configure=$configure write=$write")
            assertEquals("You declared that svc-explorer $claim.", banner.headline)
        }
    }

    @Test
    fun `declared banner says the broker could not confirm it and gives the reason on a second line`() {
        UnreadableCause.entries.forEach { cause ->
            val unreadable = PermissionCheckResult.Unreadable(cause)
            val banner = permissionBanner(withDeclaration, done(PermissionCheckResult.DeclaredByUser(true, true, unreadable)))
            val lines = checkNotNull(banner.detail) { "declared banner needs a detail ($cause)" }.lines()
            assertEquals(2, lines.size, "detail must be two lines ($cause), got ${banner.detail}")
            assertEquals(declaredDetailFirstLine, lines[0])
            assertEquals(unreadable.reason, lines[1], "second line is Unreadable.reason ($cause)")
            assertNull(banner.dimNote, "a declared result never shows the 'not used' note")
        }
    }

    @Test
    fun `unreadable banner headline is the user message for every cause including UNEXPECTED`() {
        UnreadableCause.entries.forEach { cause ->
            val unreadable = PermissionCheckResult.Unreadable(cause)
            val banner = permissionBanner(profile, done(unreadable))
            assertEquals(PillTone.LOCKED, banner.tone, "cause $cause")
            assertEquals(unreadable.userMessage, banner.headline, "cause $cause")
            assertNull(banner.detail, "the unreadable banner has no detail ($cause)")
            assertNull(banner.dimNote, "the unreadable banner has no dim note ($cause)")
        }
    }

    @Test
    fun `the dim note shows only when the broker answered and the profile declares something`() {
        assertEquals(dimNote, permissionBanner(withDeclaration, done(locked)).dimNote)
        assertEquals(dimNote, permissionBanner(withDeclaration, done(unlockedWithPublishing)).dimNote)
        assertNull(permissionBanner(profile, done(locked)).dimNote, "no declaration, no note")
        assertNull(permissionBanner(withDeclaration, null).dimNote)
        assertNull(permissionBanner(withDeclaration, PermissionCheckStatus.Checking).dimNote)
        assertNull(permissionBanner(withDeclaration, done(PermissionCheckResult.Unreadable(UnreadableCause.TIMEOUT))).dimNote)
    }

    @Test
    fun `a declaration of no and no still counts as a declaration for the dim note`() {
        val noNo = profile.copy(declaredPermissions = DeclaredPermissions(configure = false, write = false))
        assertEquals(dimNote, permissionBanner(noNo, done(unlockedWithPublishing)).dimNote)
    }

    // --- Unreadable.reason --------------------------------------------------------------------

    private val suffix = " Topology stays locked and test publishing disabled; streams can still be browsed."

    @Test
    fun `reason is the user message without the trailing locked sentence`() {
        UnreadableCause.entries.forEach { cause ->
            val unreadable = PermissionCheckResult.Unreadable(cause)
            assertTrue(unreadable.reason.startsWith("Permissions could not be read"), "cause $cause: ${unreadable.reason}")
            assertFalse(unreadable.reason.contains("Topology stays locked"), "cause $cause: ${unreadable.reason}")
            assertEquals(unreadable.reason + suffix, unreadable.userMessage, "userMessage must stay reason + suffix ($cause)")
        }
    }

    @Test
    fun `reason keeps the existing wording per cause`() {
        assertEquals(
            "Permissions could not be read: the user has no permissions on this virtual host.",
            PermissionCheckResult.Unreadable(UnreadableCause.NOT_FOUND).reason,
        )
        assertEquals(
            "Permissions could not be read: an unexpected error occurred while checking.",
            PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED).reason,
        )
        assertEquals(
            "Permissions could not be read: the management API did not answer in time.",
            PermissionCheckResult.Unreadable(UnreadableCause.TIMEOUT).reason,
        )
    }
}
