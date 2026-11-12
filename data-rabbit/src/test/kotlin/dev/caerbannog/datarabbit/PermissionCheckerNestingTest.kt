package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.UnreadableCause
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-07 call site #6: a permissions response nested ten thousand levels deep is `Unreadable(BAD_RESPONSE, "malformed JSON")`, never a
 * StackOverflowError (not caught here on purpose). Plain localhost server, no Docker, no real credentials.
 */
class PermissionCheckerNestingTest {

    private val password = "s3cr3t-pw-Zq9"
    private val path = "/api/permissions/%2F/svc"

    private fun check(body: String): PermissionCheckResult = PlainManagementServer(mapOf(path to (200 to body))).use { server ->
        val profile = ConnectionProfile(name = "p", host = "127.0.0.1", virtualHost = "/", username = "svc", managementPort = server.port, tls = false)
        runBlocking { ManagementApiPermissionChecker(connectTimeoutMillis = 2_000, requestTimeoutMillis = 10_000).check(profile, password) }
    }

    @Test
    fun `a deep array body is Unreadable BAD_RESPONSE with the malformed JSON detail`() {
        val result = check(DeepJson.arrays())
        assertTrue(result is PermissionCheckResult.Unreadable, "got $result")
        assertEquals(UnreadableCause.BAD_RESPONSE, (result as PermissionCheckResult.Unreadable).cause)
        assertEquals("malformed JSON", result.detail)
        assertFalse(result.topologyUnlocked)
        assertFalse(result.publishingEnabled)
    }

    @Test
    fun `valid permission fields next to a deep value are still not trusted`() {
        val result = check("""{"user":"svc","vhost":"/","configure":".*","write":".*","read":".*","extra":${DeepJson.arrays()}}""")
        assertTrue(result is PermissionCheckResult.Unreadable, "got $result")
        assertEquals(UnreadableCause.BAD_RESPONSE, (result as PermissionCheckResult.Unreadable).cause)
        assertFalse(result.topologyUnlocked)
    }

    @Test
    fun `a normal permissions body still unlocks`() {
        val result = check("""{"user":"svc","vhost":"/","configure":".*","write":".*","read":".*"}""")
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true), result)
    }
}
