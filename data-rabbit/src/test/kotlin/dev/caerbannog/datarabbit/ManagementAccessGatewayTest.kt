package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.WhoAmI
import dev.caerbannog.core.connections.ConnectionProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * M10-02 step 2 (test developer), no network: the access gateway turns the recorded answers of the spike M10-00 (`fixtures/m10-00/s-a-*`, `s-l-*`)
 * into typed results, sends GET requests only to the profile's own scheme and port, refuses a known 3.x broker without sending anything, and lets
 * nothing leave that the `SecretGuard` would hide. The fixtures are broker bodies recorded on RabbitMQ 4.4.1: `{request, status, body}`.
 * Red until `ManagementAccessGateway` is built (today it is a stub that answers Unreachable).
 */
class ManagementAccessGatewayTest {

    private class Seen {
        val methods: MutableList<HttpMethod> = Collections.synchronizedList(ArrayList())
        val urls: MutableList<String> = Collections.synchronizedList(ArrayList())
    }

    private val tlsProfile = ConnectionProfile(
        name = "prod", host = "broker.example", virtualHost = "/orders", username = "svc", managementPort = 15671, streamPort = 5551, tls = true,
    )
    private val plainProfile = tlsProfile.copy(name = "plain", managementPort = 15672, tls = false)

    private class Recorded(val status: Int, val body: String)

    private fun fixture(name: String): Recorded {
        val root = Json.parseToJsonElement(FixtureFiles.text("m10-00/$name.json")).jsonObject
        return Recorded(root.getValue("status").jsonPrimitive.int, root.getValue("body").toString())
    }

    private fun engine(seen: Seen = Seen(), answer: (String) -> Recorded): MockEngine = MockEngine { request ->
        seen.methods += request.method
        seen.urls += request.url.toString()
        val reply = answer(request.url.encodedPath)
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
    }

    private fun <T> withGateway(
        engine: MockEngine,
        profile: ConnectionProfile = tlsProfile,
        version: String? = "4.4.1",
        secret: String? = null,
        block: suspend (ManagementAccessGateway) -> T,
    ): T = ManagementHttp(HttpClient(engine), "svc", "pw").use { http ->
        runBlocking { block(ManagementAccessGateway(http, ManagementRequests.forProfile(profile), version) { secret }) }
    }

    private fun whoami(name: String): AccessResult<WhoAmI> = withGateway(engine { fixture(name) }) { it.whoAmI() }

    private fun vhosts(name: String): AccessResult<List<String>> = withGateway(engine { fixture(name) }) { it.virtualHosts() }

    // ---------------------------------------------------------------- whoami on the recorded answers

    @Test
    fun `whoami of an internal administrator is Loaded with name, tags and is_internal_user true`() {
        assertEquals(AccessResult.Loaded(WhoAmI("i-admin", listOf("administrator"), true)), whoami("s-a-whoami-i-admin-basic"))
    }

    @Test
    fun `whoami of a token user is Loaded with is_internal_user false`() {
        assertEquals(AccessResult.Loaded(WhoAmI("t-mgmt", listOf("management"), false)), whoami("s-a-whoami-t-mgmt-bearer"))
        assertEquals(AccessResult.Loaded(WhoAmI("t-mon", listOf("monitoring"), false)), whoami("s-a-whoami-t-mon-bearer"))
    }

    @Test
    fun `a user without a tag gets 401 Not management user and that is NoManagementTag, internal or token`() {
        assertEquals(AccessResult.NoManagementTag, whoami("s-a-whoami-i-none-basic"))
        assertEquals(AccessResult.NoManagementTag, whoami("s-a-whoami-t-none-bearer"))
        assertEquals(AccessResult.NoManagementTag, whoami("s-a-whoami-t-multi-bearer"))
        assertEquals(AccessResult.NoManagementTag, vhosts("s-a-vhosts-i-none-basic"))
        assertEquals(AccessResult.NoManagementTag, vhosts("s-a-vhosts-t-none-bearer"))
    }

    @Test
    fun `a 401 with the generic Not_Authorized body (wrong or expired credentials) is NotAuthorised, not NoManagementTag`() {
        assertEquals(AccessResult.NotAuthorised, whoami("s-l-expired-whoami-basic"))
        assertEquals(AccessResult.NotAuthorised, whoami("s-l-expired-whoami-bearer"))
        assertEquals(AccessResult.NotAuthorised, whoami("s-m-badsig-whoami-basic"))
    }

    // ---------------------------------------------------------------- vhosts on the recorded answers

    @Test
    fun `the virtual host list of an administrator is the broker's names in the broker's order, names only`() {
        assertEquals(AccessResult.Loaded(listOf("/", "/orders", "/other")), vhosts("s-a-vhosts-i-admin-basic"))
    }

    @Test
    fun `a management token or user lists only the virtual hosts the broker lists for it`() {
        assertEquals(AccessResult.Loaded(listOf("/orders")), vhosts("s-a-vhosts-t-mgmt-bearer"))
        assertEquals(AccessResult.Loaded(listOf("/orders")), vhosts("s-a-vhosts-i-mgmt-basic"))
    }

    @Test
    fun `an empty list is Loaded with no names`() {
        val empty = withGateway(engine { Recorded(200, "[]") }) { it.virtualHosts() }
        assertEquals(AccessResult.Loaded(emptyList<String>()), empty)
    }

    // ---------------------------------------------------------------- status mapping, never a body

    @Test
    fun `403 is NotAuthorised and any other status is Unexpected with a status reason only`() {
        assertEquals(AccessResult.NotAuthorised, withGateway(engine { Recorded(403, """{"error":"not_authorised","reason":"x"}""") }) { it.whoAmI() })
        assertEquals(AccessResult.Unexpected("HTTP 500"), withGateway(engine { Recorded(500, "boom") }) { it.virtualHosts() })
        assertEquals(AccessResult.Unexpected("HTTP 404"), withGateway(engine { Recorded(404, "{}") }) { it.virtualHosts() })
        assertEquals(AccessResult.Unexpected("HTTP 503"), withGateway(engine { Recorded(503, "") }) { it.whoAmI() })
    }

    @Test
    fun `a 200 that is not the expected shape is Unexpected and nothing of the body is in the reason`() {
        val junk = "<html>eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln password=hunter2</html>"
        for (call in listOf<suspend (ManagementAccessGateway) -> AccessResult<*>>({ it.whoAmI() }, { it.virtualHosts() })) {
            val result = withGateway(engine { Recorded(200, junk) }) { call(it) }
            val unexpected = assertInstanceOf(AccessResult.Unexpected::class.java, result)
            assertTrue(unexpected.reason.isNotBlank())
            assertFalse(
                unexpected.reason.contains("hunter2") || unexpected.reason.contains("eyJ") || unexpected.reason.contains("html"),
                "reason echoes the body: ${unexpected.reason}",
            )
        }
        // A whoami without a name, and a vhost list that is an object, are not understood either.
        val noName = withGateway(engine { Recorded(200, """{"tags":["monitoring"],"is_internal_user":true}""") }) { it.whoAmI() }
        assertInstanceOf(AccessResult.Unexpected::class.java, noName)
        val notAList = withGateway(engine { Recorded(200, """{"name":"/"}""") }) { it.virtualHosts() }
        assertInstanceOf(AccessResult.Unexpected::class.java, notAList)
    }

    @Test
    fun `an error body with secrets is never echoed, whatever the status`() {
        val body = """{"error":"x","reason":"token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln password=hunter2"}"""
        for (status in listOf(401, 403, 500)) {
            val shown = withGateway(engine { Recorded(status, body) }) { it.whoAmI() }.toString()
            assertFalse(shown.contains("hunter2") || shown.contains("eyJ"), "status $status echoed the body: $shown")
        }
    }

    @Test
    fun `a connection that cannot be made, a TLS failure and a closed holder are Unreachable`() {
        assertEquals(AccessResult.Unreachable, withGateway(MockEngine { throw ConnectException("refused") }) { it.whoAmI() })
        assertEquals(AccessResult.Unreachable, withGateway(MockEngine { throw SSLException("Unrecognized SSL message, plaintext connection?") }) { it.virtualHosts() })
        val http = ManagementHttp(HttpClient(engine { Recorded(200, "[]") }), "svc", "pw")
        val gateway = ManagementAccessGateway(http, ManagementRequests.forProfile(tlsProfile), "4.4.1")
        http.close()
        assertEquals(AccessResult.Unreachable, runBlocking { gateway.virtualHosts() })
    }

    // ---------------------------------------------------------------- GET only, the profile's own scheme and port

    @Test
    fun `both calls are plain GETs, to the profile's own https port`() {
        val seen = Seen()
        withGateway(engine(seen) { path -> if (path.endsWith("whoami")) fixture("s-a-whoami-i-admin-basic") else fixture("s-a-vhosts-i-admin-basic") }) {
            it.whoAmI()
            it.virtualHosts()
        }
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Get), seen.methods.toList(), "the Access view sends GET only")
        assertEquals(listOf("https://broker.example:15671/api/whoami", "https://broker.example:15671/api/vhosts"), seen.urls.toList())
    }

    @Test
    fun `a profile without TLS is asked on its own plain port, with no fallback to https`() {
        val seen = Seen()
        withGateway(engine(seen) { fixture("s-a-whoami-i-admin-basic") }, profile = plainProfile) { it.whoAmI() }
        assertEquals(listOf("http://broker.example:15672/api/whoami"), seen.urls.toList())
    }

    // ---------------------------------------------------------------- RabbitMQ 3.x: refuses, sends nothing

    @Test
    fun `a known 3-x broker is refused on both calls and nothing is sent`() {
        for (version in listOf("3.13.7", "3.12.0", "3.8.35-rc.1")) {
            val seen = Seen()
            val results = withGateway(engine(seen) { fixture("s-a-whoami-i-admin-basic") }, version = version) { listOf(it.whoAmI(), it.virtualHosts()) }
            results.forEach { r -> assertEquals(AccessResult.Unexpected(AccessTexts.NEEDS_4X), r, "version $version") }
            assertEquals(emptyList<String>(), seen.urls.toList(), "a request reached the 3.x broker (version $version)")
        }
    }

    @Test
    fun `a 4-x or an unknown version is asked`() {
        for (version in listOf("4.0.0", "4.4.1", null)) {
            val seen = Seen()
            withGateway(engine(seen) { fixture("s-a-whoami-i-admin-basic") }, version = version) { it.whoAmI() }
            assertEquals(1, seen.urls.size, "version $version")
        }
    }

    // ---------------------------------------------------------------- SecretGuard at the boundary

    @Test
    fun `data leaves the gateway already through the guard, with the held password passed in per call`() {
        val secret = "S3cret-PW-4711-xyz"
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl"
        val who = withGateway(
            engine { Recorded(200, """{"name":"$jwt","tags":["Bearer abc","monitoring"],"is_internal_user":false}""") },
            secret = secret,
        ) { it.whoAmI() }
        assertEquals(AccessResult.Loaded(WhoAmI(SecretGuard.HIDDEN, listOf(SecretGuard.HIDDEN, "monitoring"), false)), who)

        val list = withGateway(
            engine { Recorded(200, """[{"name":"/"},{"name":"v-$secret"},{"name":"password=x"}]""") },
            secret = secret,
        ) { it.virtualHosts() }
        assertEquals(AccessResult.Loaded(listOf("/", SecretGuard.HIDDEN, SecretGuard.HIDDEN)), list)
    }

    @Test
    fun `the held secret is read per call and never kept by the gateway`() {
        val reads = AtomicInteger()
        val secret = "S3cret-PW-4711-xyz"
        ManagementHttp(HttpClient(engine { fixture("s-a-whoami-i-admin-basic") }), "svc", "pw").use { http ->
            val gateway = ManagementAccessGateway(http, ManagementRequests.forProfile(tlsProfile), "4.4.1") {
                reads.incrementAndGet()
                secret
            }
            runBlocking {
                gateway.whoAmI()
                gateway.whoAmI()
            }
            // The holder is the one place that may keep the secret: a lambda that returns it is allowed, a copy of the text is not.
            for (field in ManagementAccessGateway::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                field.isAccessible = true
                val value = field.get(gateway)
                if (value is String) assertFalse(value.contains(secret), "field ${field.name} holds a copy of the secret")
            }
        }
        assertTrue(reads.get() >= 2, "the gateway must ask the holder for the secret on each call (read ${reads.get()} times in 2 calls)")
    }
}
