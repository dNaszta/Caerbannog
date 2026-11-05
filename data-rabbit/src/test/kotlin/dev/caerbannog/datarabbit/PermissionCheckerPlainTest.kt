package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.UnreadableCause
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** M2-14a step 2 (no Docker): `ManagementApiPermissionChecker` over plain http. The topology rules are the same as over TLS (`decidePermissions`). */
class PermissionCheckerPlainTest {

    private val password = "s3cr3t-pw-Zq9"
    private val path = "/api/permissions/%2F/svc"

    private fun body(configure: String, write: String, read: String = ".*") =
        """{"user":"svc","vhost":"/","configure":"$configure","write":"$write","read":"$read"}"""

    private fun profile(port: Int, tls: Boolean, ca: String? = null) = ConnectionProfile(
        name = "p", host = "127.0.0.1", virtualHost = "/", username = "svc", managementPort = port, tls = tls, caCertificatePath = ca,
    )

    private fun check(profile: ConnectionProfile): PermissionCheckResult =
        runBlocking { ManagementApiPermissionChecker(connectTimeoutMillis = 2_000).check(profile, password) }

    @Test
    fun `plain asks the permissions over http with basic auth and unlocks topology and publishing for full permissions`() {
        PlainManagementServer(mapOf(path to (200 to body(".*", ".*")))).use { server ->
            val result = check(profile(server.port, tls = false))

            assertEquals(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true), result)
            val request = server.seen.single()
            assertEquals(path, request.rawPath)
            assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:$password".toByteArray()), request.authorization)
        }
    }

    @Test
    fun `plain gives the same locked and unlocked answers as over TLS`() {
        listOf(
            Triple(".*", ".*", true to true),
            Triple("", ".*", false to true),
            Triple(".*", "", true to false),
            Triple("", "", false to false),
        ).forEach { (configure, write, expected) ->
            PlainManagementServer(mapOf(path to (200 to body(configure, write)))).use { server ->
                val result = check(profile(server.port, tls = false))
                assertEquals(
                    PermissionCheckResult.Checked(topologyUnlocked = expected.first, publishingEnabled = expected.second), result,
                    "configure='$configure' write='$write'",
                )
            }
        }
    }

    @Test
    fun `plain ignores a CA path that does not exist`() {
        PlainManagementServer(mapOf(path to (200 to body(".*", ".*")))).use { server ->
            assertTrue(check(profile(server.port, tls = false, ca = "/no/such/ca.pem")) is PermissionCheckResult.Checked)
        }
    }

    @Test
    fun `plain with a refusing broker is Unreadable NOT_AUTHORIZED like over TLS`() {
        PlainManagementServer(mapOf(path to (401 to """{"error":"not_authorised"}"""))).use { server ->
            val result = check(profile(server.port, tls = false))
            assertEquals(UnreadableCause.NOT_AUTHORIZED, (result as PermissionCheckResult.Unreadable).cause)
            assertFalse(result.detail.orEmpty().contains(password))
        }
    }

    @Test
    fun `plain against a closed port is Unreadable UNREACHABLE with no password in it`() {
        val result = check(profile(closedLocalPort(), tls = false))
        val unreadable = result as PermissionCheckResult.Unreadable
        assertEquals(UnreadableCause.UNREACHABLE, unreadable.cause)
        assertFalse(unreadable.detail.orEmpty().contains(password) || unreadable.userMessage.contains(password))
    }

    @Test
    fun `TLS on against a plain management port never sends the password`() {
        PlainManagementServer(mapOf(path to (200 to body(".*", ".*")))).use { server ->
            val result = check(profile(server.port, tls = true))

            assertEquals(UnreadableCause.UNREACHABLE, (result as PermissionCheckResult.Unreadable).cause, "got $result")
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "no http request, so no Authorization header, reached the plain port")
            assertFalse(result.detail.orEmpty().contains(password))
        }
    }
}
