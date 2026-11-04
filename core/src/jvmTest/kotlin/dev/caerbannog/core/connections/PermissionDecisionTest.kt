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
        val unreadable: PermissionCheckResult = PermissionCheckResult.Unreadable("permissions could not be read")
        assertFalse(unreadable.topologyUnlocked)
        assertFalse(unreadable.publishingEnabled)
        assertTrue(unreadable.canBrowseStreams, "reading streams never needs configure or write")
    }
}
