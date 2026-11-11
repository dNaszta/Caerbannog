package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.UnreadableCause
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-12c item 6: the permission check also reads `GET /api/whoami` and hands the tags of the user to `Checked.userTags`; an unreadable whoami is an empty set. */
class PermissionCheckerTagsTest {
    private val permissions = "/api/permissions/%2F/svc"
    private val okBody = """{"user":"svc","vhost":"/","configure":".*","write":".*","read":".*"}"""

    private fun check(whoami: Pair<Int, String>?): PermissionCheckResult {
        val routes = mutableMapOf(permissions to (200 to okBody))
        whoami?.let { routes["/api/whoami"] = it }
        PlainManagementServer(routes).use { server ->
            val profile = ConnectionProfile(name = "p", host = "127.0.0.1", virtualHost = "/", username = "svc", managementPort = server.port, tls = false)
            return runBlocking { ManagementApiPermissionChecker(connectTimeoutMillis = 2_000).check(profile, "pw") }
        }
    }

    private fun tags(r: PermissionCheckResult) = (r as? PermissionCheckResult.Checked ?: throw AssertionError("expected Checked but got $r")).userTags

    @Test
    fun `tags as a JSON array`() = assertEquals(setOf("administrator", "management"), tags(check(200 to """{"name":"svc","tags":["administrator","management"]}""")))

    @Test
    fun `tags as a comma separated string, as older brokers send them`() =
        assertEquals(setOf("administrator", "monitoring"), tags(check(200 to """{"name":"svc","tags":"administrator, monitoring"}""")))

    @Test
    fun `an empty tag list is an empty set`() {
        assertEquals(emptySet<String>(), tags(check(200 to """{"name":"svc","tags":[]}""")))
        assertEquals(emptySet<String>(), tags(check(200 to """{"name":"svc","tags":""}""")))
    }

    @Test
    fun `a missing, refused, broken or odd whoami gives an empty set, never a guess, and the permissions result stays`() {
        for (whoami in listOf<Pair<Int, String>?>(null, 401 to "{}", 500 to "boom", 200 to "not json", 200 to """{"tags":[1,2]}""", 200 to """{"name":"svc"}""", 200 to """{"tags":{"a":"administrator"}}""")) {
            val r = check(whoami)
            assertTrue(r is PermissionCheckResult.Checked, "$whoami gave $r")
            assertEquals(emptySet<String>(), tags(r), "$whoami")
            assertTrue((r as PermissionCheckResult.Checked).topologyUnlocked)
        }
    }

    @Test
    fun `the check asks the permissions and then whoami, two requests with the same credentials`() {
        PlainManagementServer(mapOf(permissions to (200 to okBody), "/api/whoami" to (200 to """{"tags":["administrator"]}"""))).use { server ->
            val profile = ConnectionProfile(name = "p", host = "127.0.0.1", virtualHost = "/", username = "svc", managementPort = server.port, tls = false)
            runBlocking { ManagementApiPermissionChecker(connectTimeoutMillis = 2_000).check(profile, "pw") }
            assertEquals(listOf(permissions, "/api/whoami"), server.seen.map { it.rawPath })
            assertEquals(server.seen[0].authorization, server.seen[1].authorization)
        }
    }

    @Test
    fun `unreadable permissions stay unreadable and carry no tags`() {
        PlainManagementServer(mapOf(permissions to (401 to "{}"), "/api/whoami" to (200 to """{"tags":["administrator"]}"""))).use { server ->
            val profile = ConnectionProfile(name = "p", host = "127.0.0.1", virtualHost = "/", username = "svc", managementPort = server.port, tls = false)
            val r = runBlocking { ManagementApiPermissionChecker(connectTimeoutMillis = 2_000).check(profile, "pw") }
            assertEquals(UnreadableCause.NOT_AUTHORIZED, (r as PermissionCheckResult.Unreadable).cause)
            assertEquals(emptySet<String>(), r.userTags)
        }
    }
}
