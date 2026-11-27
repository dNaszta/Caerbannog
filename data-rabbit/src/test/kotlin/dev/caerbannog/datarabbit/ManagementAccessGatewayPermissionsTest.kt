package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserNames
import dev.caerbannog.core.access.UserPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.util.Collections

/**
 * M10-03 step 2 (test developer), no network: `users()` and `permissionsOf()` of the access gateway on the answers CAPTURED from a real 4.4.1 broker by
 * `RealBrokerAccessUsersSpikeTest` (fixtures `s-b-users-*`, `s-b-topic-permissions-*`, `s-b-user-permissions-*`, `s-b-i-admin-user-permissions-of-i-admin`;
 * the answers of the broker, not hand-written, except where a test says "hand-written": the hash row that 4.4.1 no longer sends, and junk).
 * Red until `ManagementAccessGateway.users()` and `permissionsOf()` are built (today a stub that answers Unreachable).
 */
class ManagementAccessGatewayPermissionsTest {

    private class Seen {
        val methods: MutableList<HttpMethod> = Collections.synchronizedList(ArrayList())
        val urls: MutableList<String> = Collections.synchronizedList(ArrayList())
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    }

    private class Recorded(val status: Int, val body: String)

    private val profile = ConnectionProfile(name = "prod", host = "broker.example", virtualHost = "/orders", username = "svc", managementPort = 15671, streamPort = 5551, tls = true)

    private fun fixture(name: String): Recorded {
        val root = Json.parseToJsonElement(FixtureFiles.text("m10-00/$name.json")).jsonObject
        return Recorded(root.getValue("status").jsonPrimitive.int, root.getValue("body").toString())
    }

    private fun engine(seen: Seen = Seen(), answer: (String) -> Recorded): MockEngine = MockEngine { request ->
        seen.methods += request.method
        seen.urls += request.url.toString()
        seen.bodies += String(request.body.toByteArray())
        val reply = answer(request.url.encodedPath)
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
    }

    private fun <T> withGateway(engine: MockEngine, version: String? = "4.4.1", secret: String? = null, block: suspend (ManagementAccessGateway) -> T): T =
        ManagementHttp(HttpClient(engine), "svc", "pw").use { http ->
            runBlocking { block(ManagementAccessGateway(http, ManagementRequests.forProfile(profile), version) { secret }) }
        }

    private fun cell(text: String) = PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))

    /** The captured answers of one user, routed by path. */
    private fun capturedFor(user: String, permissions: String, topic: String): (String) -> Recorded = { path ->
        when {
            path.endsWith("/topic-permissions") -> fixture(topic)
            path.endsWith("/permissions") -> fixture(permissions)
            else -> Recorded(404, """{"error":"not_found","reason":"Not Found"}""")
        }
    }

    // ---------------------------------------------------------------- users(): names only

    @Test
    fun `the user list is the names of the captured answer in the broker's order and nothing else`() {
        val result = withGateway(engine { fixture("s-b-users-as-i-admin") }) { it.users() }
        assertEquals(AccessResult.Loaded(UserNames(listOf("guest", "m3 odd/user", "m3-admin", "m3-none", "m3-svc"))), result)
    }

    @Test
    fun `a token administrator reads the same list`() {
        val result = withGateway(engine { fixture("s-b-users-as-t-admin") }) { it.users() }
        assertEquals(AccessResult.Loaded(UserNames(listOf("guest", "m3 odd/user", "m3-admin", "m3-none", "m3-svc"))), result)
    }

    @Test
    fun `password_hash, tags and every other field are dropped, in the model, in toString and in the output (hand-written row of an older broker)`() {
        val body = """[{"name":"svc-a","password_hash":"HASHVALUE0123456789abcdef=","hashing_algorithm":"rabbit_password_hashing_sha256","tags":["administrator","impersonator"],"has_password":true,"limits":{}}]"""
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        val result = try {
            withGateway(engine { Recorded(200, body) }) { it.users() }
        } finally {
            System.setOut(original)
        }
        assertEquals(AccessResult.Loaded(UserNames(listOf("svc-a"))), result)
        val shown = result.toString()
        for (leaked in listOf("HASHVALUE", "sha256", "impersonator", "administrator", "has_password", "limits")) {
            assertFalse(shown.contains(leaked), "'$leaked' is in $shown")
            assertFalse(out.toString().contains(leaked), "'$leaked' was printed")
        }
        // the model has no field that could hold more than names
        assertEquals(listOf("names"), UserNames::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name })
    }

    @Test
    fun `a name that looks like a token or holds the password is replaced whole by the hidden marker, the real name is gone`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl"
        val secret = "S3cret-PW-4711-xyz"
        val body = """[{"name":"svc-a"},{"name":"$jwt"},{"name":"u-$secret"},{"name":"Bearer abcdef"}]"""
        val result = withGateway(engine { Recorded(200, body) }, secret = secret) { it.users() }
        assertEquals(AccessResult.Loaded(UserNames(listOf("svc-a", SecretGuard.HIDDEN, SecretGuard.HIDDEN, SecretGuard.HIDDEN))), result)
        assertFalse(result.toString().contains("eyJ") || result.toString().contains("S3cret"))
    }

    @Test
    fun `an empty list is Loaded with no names, a row without a name or a list that is an object is not understood`() {
        assertEquals(AccessResult.Loaded(UserNames(emptyList())), withGateway(engine { Recorded(200, "[]") }) { it.users() })
        assertInstanceOf(AccessResult.Unexpected::class.java, withGateway(engine { Recorded(200, """[{"tags":[]}]""") }) { it.users() })
        assertInstanceOf(AccessResult.Unexpected::class.java, withGateway(engine { Recorded(200, """{"name":"x"}""") }) { it.users() })
    }

    // ---------------------------------------------------------------- permissionsOf(): two GETs, one result

    @Test
    fun `permissionsOf of the captured user is both lists as one value, patterns exactly as returned, the broker's order`() {
        val result = withGateway(engine(answer = capturedFor("m3-svc", "s-b-user-permissions-of-m3-svc", "s-b-topic-permissions-of-m3-svc"))) { it.permissionsOf("m3-svc") }
        assertEquals(
            AccessResult.Loaded(
                UserPermissions(
                    user = "m3-svc",
                    rows = listOf(
                        PermissionRow("/orders", cell("^queue\\..*"), cell("^ex\\..*"), cell(".*")),
                        PermissionRow("/other", cell("^$"), cell("^$"), cell("^$")),
                    ),
                    topicRows = listOf(
                        TopicPermissionRow("/", "amq.topic", cell(".*"), cell(".*")),
                        TopicPermissionRow("/orders", "amq.topic", cell("^a\\..*"), cell("")),
                        TopicPermissionRow("/orders", "orders.events", cell(".*"), cell("^b\\.")),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `an empty pattern is the empty text and reads as no permission, while caret-dollar is a pattern`() {
        val admin = withGateway(engine(answer = capturedFor("m3-admin", "s-b-user-permissions-of-m3-admin", "s-b-topic-permissions-of-m3-none"))) { it.permissionsOf("m3-admin") }
        val rows = ((admin as AccessResult.Loaded).value).rows
        val orders = rows.single { it.vhost == "/orders" }
        assertEquals(PatternCell("", PatternReading.NoPermission, hidden = false), orders.write)
        assertEquals(PatternReading.HasPattern(".*"), orders.configure.reading)
        val svc = withGateway(engine(answer = capturedFor("m3-svc", "s-b-user-permissions-of-m3-svc", "s-b-topic-permissions-of-m3-none"))) { it.permissionsOf("m3-svc") }
        assertEquals(PatternReading.HasPattern("^$"), (svc as AccessResult.Loaded).value.rows.single { it.vhost == "/other" }.configure.reading)
    }

    @Test
    fun `a user with no row on either list is Loaded and empty, not an error`() {
        val result = withGateway(engine(answer = capturedFor("m3-none", "s-b-user-permissions-of-m3-none", "s-b-topic-permissions-of-m3-none"))) { it.permissionsOf("m3-none") }
        assertEquals(AccessResult.Loaded(UserPermissions("m3-none", emptyList(), emptyList())), result)
    }

    @Test
    fun `the S-B fixture of an internal administrator parses`() {
        val result = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) Recorded(200, "[]") else fixture("s-b-i-admin-user-permissions-of-i-admin") }) { it.permissionsOf("i-admin") }
        val rows = (result as AccessResult.Loaded).value.rows
        assertEquals(listOf("/", "/orders", "/other"), rows.map { it.vhost })
        assertTrue(rows.all { r -> listOf(r.configure, r.write, r.read).all { it == cell(".*") } })
    }

    @Test
    fun `the token administrator's own permissions answer 404 and that is NotFound, never an error`() {
        val result = withGateway(engine { fixture("s-b-topic-permissions-of-token-user-404") }) { it.permissionsOf("t-m3-admin") }
        assertEquals(AccessResult.NotFound, result)
        val first404 = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) Recorded(200, "[]") else Recorded(404, """{"error":"not_found","reason":"Not Found"}""") }) { it.permissionsOf("t-narrow") }
        assertEquals(AccessResult.NotFound, first404)
    }

    @Test
    fun `one list failing means no result, no half table`() {
        val topicFails = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) Recorded(500, "boom") else fixture("s-b-user-permissions-of-m3-svc") }) { it.permissionsOf("m3-svc") }
        assertEquals(AccessResult.Unexpected("HTTP 500"), topicFails)
        val permissionsFail = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) fixture("s-b-topic-permissions-of-m3-svc") else Recorded(503, "") }) { it.permissionsOf("m3-svc") }
        assertEquals(AccessResult.Unexpected("HTTP 503"), permissionsFail)
        val topicBroken = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) Recorded(200, """{"x":1}""") else fixture("s-b-user-permissions-of-m3-svc") }) { it.permissionsOf("m3-svc") }
        assertInstanceOf(AccessResult.Unexpected::class.java, topicBroken)
    }

    @Test
    fun `a caller without the administrator tag gets 401 Not administrator user, which is NotAuthorised and not NoManagementTag`() {
        assertEquals(AccessResult.NotAuthorised, withGateway(engine { fixture("s-b-topic-permissions-denied") }) { it.permissionsOf("m3-svc") })
        assertEquals(AccessResult.NotAuthorised, withGateway(engine { fixture("s-b-t-mon-denied") }) { it.users() })
        assertEquals(AccessResult.NotAuthorised, withGateway(engine { Recorded(403, """{"error":"not_authorised","reason":"x"}""") }) { it.permissionsOf("u") })
    }

    @Test
    fun `an unreachable broker is Unreachable on both calls`() {
        assertEquals(AccessResult.Unreachable, withGateway(MockEngine { throw ConnectException("refused") }) { it.users() })
        assertEquals(AccessResult.Unreachable, withGateway(MockEngine { throw ConnectException("refused") }) { it.permissionsOf("u") })
    }

    @Test
    fun `a 200 that is not the expected shape is Unexpected with a fixed text and nothing of the body`() {
        val junk = "<html>eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln password=hunter2</html>"
        for (result in listOf(withGateway(engine { Recorded(200, junk) }) { it.users() }, withGateway(engine { Recorded(200, junk) }) { it.permissionsOf("u") })) {
            val unexpected = assertInstanceOf(AccessResult.Unexpected::class.java, result)
            assertFalse(unexpected.reason.contains("hunter2") || unexpected.reason.contains("eyJ") || unexpected.reason.contains("html"), unexpected.reason)
        }
        // a row without a vhost, a pattern that is null or a number
        for (row in listOf("""{"user":"u","configure":".*","write":".*","read":".*"}""", """{"user":"u","vhost":"/","configure":null,"write":".*","read":".*"}""", """{"user":"u","vhost":"/","configure":1,"write":".*","read":".*"}""")) {
            val r = withGateway(engine { path -> if (path.endsWith("/topic-permissions")) Recorded(200, "[]") else Recorded(200, "[$row]") }) { it.permissionsOf("u") }
            assertInstanceOf(AccessResult.Unexpected::class.java, r, row)
        }
    }

    @Test
    fun `an error body with secrets is never echoed, whatever the status`() {
        val body = """{"error":"x","reason":"token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln password=hunter2"}"""
        for (status in listOf(401, 403, 404, 500)) {
            val shown = withGateway(engine { Recorded(status, body) }) { it.permissionsOf("u") }.toString() + withGateway(engine { Recorded(status, body) }) { it.users() }
            assertFalse(shown.contains("hunter2") || shown.contains("eyJ"), "status $status echoed the body: $shown")
        }
    }

    // ---------------------------------------------------------------- the guard at the boundary

    @Test
    fun `a pattern or a virtual host or an exchange that looks like a secret is hidden, with hidden true on a pattern`() {
        val secret = "S3cret-PW-4711-xyz"
        val permissions = """[{"user":"u","vhost":"v-$secret","configure":"token=abc","write":".*","read":"^$secret$"}]"""
        val topics = """[{"user":"u","vhost":"/","exchange":"Bearer abcdef","write":"password=x","read":""}]"""
        val result = withGateway(engine { path -> Recorded(200, if (path.endsWith("/topic-permissions")) topics else permissions) }, secret = secret) { it.permissionsOf("u") }
        val value = (result as AccessResult.Loaded).value
        val row = value.rows.single()
        assertEquals(SecretGuard.HIDDEN, row.vhost)
        assertEquals(SecretGuard.HIDDEN, row.configure.text); assertTrue(row.configure.hidden)
        assertEquals(PatternCell(".*", PatternReading.HasPattern(".*"), hidden = false), row.write)
        assertEquals(SecretGuard.HIDDEN, row.read.text); assertTrue(row.read.hidden)
        val topic = value.topicRows.single()
        assertEquals(SecretGuard.HIDDEN, topic.exchange)
        assertTrue(topic.write.hidden)
        assertEquals(PatternCell("", PatternReading.NoPermission, hidden = false), topic.read)
        assertFalse(result.toString().contains("S3cret") || result.toString().contains("token=abc"))
    }

    // ---------------------------------------------------------------- GET only, exact paths, encoding, 3.x

    @Test
    fun `users is one GET on api users and nothing else`() {
        val seen = Seen()
        withGateway(engine(seen) { fixture("s-b-users-as-i-admin") }) { it.users() }
        assertEquals(listOf(HttpMethod.Get), seen.methods.toList())
        assertEquals(listOf("https://broker.example:15671/api/users"), seen.urls.toList())
    }

    @Test
    fun `permissionsOf is two GETs of the one user and never the list of every user's rows, and no request has a body`() {
        val seen = Seen()
        withGateway(engine(seen, capturedFor("m3-svc", "s-b-user-permissions-of-m3-svc", "s-b-topic-permissions-of-m3-svc"))) { it.permissionsOf("m3-svc") }
        assertTrue(seen.methods.all { it == HttpMethod.Get }, "a non-GET request was sent: ${seen.methods}")
        assertEquals(
            setOf("https://broker.example:15671/api/users/m3-svc/permissions", "https://broker.example:15671/api/users/m3-svc/topic-permissions"),
            seen.urls.toSet(),
        )
        assertEquals(2, seen.urls.size)
        assertTrue(seen.bodies.all { it.isEmpty() }, "a request carried a body: ${seen.bodies}")
        assertTrue(seen.urls.none { it.contains("/api/permissions") || it.contains("/api/topic-permissions") }, "the call that returns every user's rows was used: ${seen.urls}")
    }

    @Test
    fun `a user name with a space and a slash is one encoded path segment, as the live broker answered it`() {
        val seen = Seen()
        withGateway(engine(seen, capturedFor("odd", "s-b-user-permissions-of-odd-name", "s-b-topic-permissions-of-m3-none"))) { it.permissionsOf("m3 odd/user") }
        assertEquals(2, seen.urls.size)
        assertTrue(seen.urls.all { it.startsWith("https://broker.example:15671/api/users/m3%20odd%2Fuser/") }, "urls: ${seen.urls}")
    }

    @Test
    fun `a user name made of dots or slashes cannot escape its path segment`() {
        val seen = Seen()
        withGateway(engine(seen) { Recorded(404, "{}") }) { it.permissionsOf("..") ; it.permissionsOf("a/../b") }
        assertTrue(seen.urls.size >= 2, "the gateway must have asked: ${seen.urls}")
        assertTrue(seen.urls.none { it.contains("/../") || it.endsWith("/api/users/../permissions") }, "urls: ${seen.urls}")
        assertTrue(seen.urls.all { it.startsWith("https://broker.example:15671/api/users/") })
    }

    @Test
    fun `a known 3-x broker is refused on both new calls and nothing is sent`() {
        for (version in listOf("3.13.7", "3.12.0")) {
            val seen = Seen()
            val results = withGateway(engine(seen) { fixture("s-b-users-as-i-admin") }, version = version) { listOf(it.users(), it.permissionsOf("m3-svc")) }
            results.forEach { assertEquals(AccessResult.NotOffered, it, "version $version") }
            assertEquals(emptyList<String>(), seen.urls.toList(), "a request reached the 3.x broker (version $version)")
        }
    }

    @Test
    fun `the gateway has no method that is not a read`() {
        val names = dev.caerbannog.core.access.AccessGateway::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("whoAmI", "virtualHosts", "users", "permissionsOf"), names)
    }
}
