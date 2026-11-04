package dev.caerbannog.core.connections

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1-03 (docs/tasks/M1-03-permission-check.md, Acceptance "Unit (core)"): the pure decision function
 * `decidePermissions(BrokerPermissions): PermissionCheckResult.Checked`. Contract proposed by the test
 * developer: top-level function in `dev.caerbannog.core.connections`. Red state: types do not exist yet.
 *
 * Rule: an empty pattern means no permission; any non-empty pattern counts as present, because the broker
 * has the final say and the app never evaluates the regex.
 */
class PermissionDecisionTest {

    private fun decide(configure: String, write: String, read: String = ".*") =
        decidePermissions(BrokerPermissions(configure = configure, write = write, read = read))

    @Test
    fun `all three patterns present unlocks topology and enables publishing`() {
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true), decide(".*", ".*"))
    }

    @Test
    fun `no configure locks topology but keeps publishing`() {
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true), decide("", ".*"))
    }

    @Test
    fun `no write disables publishing but keeps topology`() {
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = false), decide(".*", ""))
    }

    @Test
    fun `no configure and no write locks and disables`() {
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false), decide("", ""))
    }

    @Test
    fun `a narrow configure pattern counts as configure present`() {
        val result = decide("^orders\\..*", "^orders\\..*")
        assertTrue(result.topologyUnlocked, "a non-.* pattern is still a permission; the broker decides per name")
        assertTrue(result.publishingEnabled)
    }

    @Test
    fun `read pattern does not influence topology or publishing`() {
        assertEquals(decide("", "", ""), decide("", "", ".*"))
        assertEquals(decide(".*", ".*", ""), decide(".*", ".*", ".*"))
    }

    @Test
    fun `a pattern that matches nothing useful is still non-empty and so allowed`() {
        // "^$" only matches the empty name. Documented consequence of "non-empty means allowed": the app
        // does not interpret regexes. Raise with the architect if this is not wanted.
        assertTrue(decide("^$", "^$").topologyUnlocked)
    }

    @Test
    fun `unreadable permissions are never unlocked and never enabled`() {
        val unreadable: PermissionCheckResult = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        assertFalse(unreadable.topologyUnlocked)
        assertFalse(unreadable.publishingEnabled)
        assertTrue(unreadable.canBrowseStreams, "reading streams never needs configure or write")
    }

    @Test
    fun `an exactly empty string means none and anything else means present`() {
        assertFalse(decide("", "").topologyUnlocked)
        assertTrue(decide("^$", "").topologyUnlocked)
        assertTrue(decide(" ", " ").publishingEnabled, "not exactly empty: present, the broker has the final say")
    }

    private val unreadable = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED, "HTTP 401")

    private fun declared(configure: Boolean, write: Boolean) =
        decideDeclared(DeclaredPermissions(configure = configure, write = write), unreadable)

    @Test
    fun `declared permissions map to the four combinations and carry the unreadable result`() {
        assertEquals(PermissionCheckResult.DeclaredByUser(true, true, unreadable), declared(true, true))
        assertEquals(PermissionCheckResult.DeclaredByUser(false, true, unreadable), declared(false, true))
        assertEquals(PermissionCheckResult.DeclaredByUser(true, false, unreadable), declared(true, false))
        assertEquals(PermissionCheckResult.DeclaredByUser(false, false, unreadable), declared(false, false))
    }

    @Test
    fun `the unreadable message starts with the fixed wording for every cause`() {
        for (cause in UnreadableCause.values()) {
            val message = PermissionCheckResult.Unreadable(cause).userMessage
            assertTrue(message.startsWith("Permissions could not be read"), "$cause: '$message'")
        }
    }

    @Test
    fun `a declaration never lets browsing depend on it`() {
        assertTrue(declared(false, false).canBrowseStreams)
    }
}
