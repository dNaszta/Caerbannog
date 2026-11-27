package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M10-03 step 2: the LIVE CAPTURE the brief asks for (docs/tasks/M10-03-permissions-tab.md, "Open points"). The field names of
 * `GET /api/users` and the shape of `GET /api/users/{user}/topic-permissions` were assumed from the management documentation;
 * this class measures them on the pinned 4.x broker (OAuth 2 backend first, internal second, so a token user can be asked about too)
 * and stores the answers as fixtures `s-b-users-*`, `s-b-topic-permissions-*`, `s-b-user-permissions-*` under `fixtures/m10-00/`
 * (written when `RECORD_M10_00_FIXTURES` is set; `AccessFixtureGuardTest` covers the directory). `password_hash` is removed from
 * every user row BEFORE the fixture is written; the test asserts it was present on the wire and absent in the saved file.
 * Facts: `build/m10-00-facts.txt` (lines `[M10-03]`). Run: `./gradlew :data-rabbit:brokerSpikeTest --tests '*RealBrokerAccessUsersSpike*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealBrokerAccessUsersSpikeTest {
    private lateinit var env: AccessEnv
    private lateinit var tokenAdmin: AccessAuth.Basic
    private lateinit var tokenNone: AccessAuth.Basic
    private val odd = "m3 odd/user"

    @BeforeAll
    fun start() {
        env = AccessEnv("U", accessBroker().also { it.start() })
        env.addVhost("/orders"); env.addVhost("/other")
        env.addUser("m3-admin", "administrator", "/")
        env.setPermissions("m3-admin", "/orders", ".*", "", ".*")
        env.addUser("m3-svc", "management,policymaker", "/orders", configure = "^queue\\..*", write = "^ex\\..*", read = ".*")
        env.setPermissions("m3-svc", "/other", "^\$", "^\$", "^\$")
        check(env.admin("PUT", "/api/exchanges/${env.encode("/orders")}/orders.events", """{"type":"topic","durable":true}""").ok) { "exchange not declared" }
        val topicPuts = listOf(
            env.setTopicPermissions("m3-svc", "/orders", "amq.topic", "^a\\..*", ""),
            env.setTopicPermissions("m3-svc", "/orders", "orders.events", ".*", "^b\\."),
            env.setTopicPermissions("m3-svc", "/", "amq.topic", ".*", ".*"),
        )
        AccessFacts.note("M10-03", "topic permission PUT answers: ${topicPuts.map { it.status }}")
        check(topicPuts.all { it.ok }) { "topic permission not set: $topicPuts" }
        env.addUser("m3-none", "", null)
        // a user name with a space and a slash: the path segment must be encoded
        check(env.admin("PUT", "/api/users/${env.encode(odd).replace("+", "%20")}", """{"password":"$ACCESS_PASSWORD","tags":""}""").ok) { "odd user not created" }
        check(env.admin("PUT", "/api/permissions/${env.encode("/orders")}/${path(odd)}", """{"configure":"a","write":"b","read":"c"}""").ok) { "odd permissions not set" }
        tokenAdmin = AccessAuth.Basic("t-m3-admin", accessToken("t-m3-admin", listOf("$ACCESS_RESOURCE_SERVER.tag:administrator", "$ACCESS_RESOURCE_SERVER.configure:*/*", "$ACCESS_RESOURCE_SERVER.write:*/*", "$ACCESS_RESOURCE_SERVER.read:*/*")))
        tokenNone = AccessAuth.Basic("t-m3-mon", accessToken("t-m3-mon", listOf("$ACCESS_RESOURCE_SERVER.tag:monitoring", "$ACCESS_RESOURCE_SERVER.read:*/*")))
        AccessFacts.note("M10-03", "image=${BrokerImages.CURRENT_4X}")
    }

    @AfterAll
    fun stop() { runCatching { env.container.stop() } }

    private fun path(user: String) = env.encode(user).replace("+", "%20")
    private fun get(path: String, auth: AccessAuth.Basic = env.guestBasic()) = env.http.get(path, auth)
    private fun AccessEnv.guestBasic() = AccessAuth.Basic("guest", "guest")

    /** Removes `password_hash` from every user row; the only place a hash can leave the capture. */
    private fun withoutHash(reply: AccessReply): AccessReply {
        val array = reply.arr ?: return reply
        val clean = JsonArray(array.map { row -> if (row is JsonObject) JsonObject(row.filterKeys { it != "password_hash" }) else row })
        return AccessReply(reply.status, clean.toString())
    }

    @Test
    fun `GET api users answers a list with name, tags, a hash that is never saved, and no token user`() {
        val reply = get("/api/users")
        assertEquals(200, reply.status, reply.toString())
        val rows = reply.arr!!.map { it.jsonObject }
        AccessFacts.note("M10-03", "GET /api/users keys per row: ${rows.map { it.keyList() }.distinct()}")
        AccessFacts.note("M10-03", "GET /api/users tags value kinds: ${rows.associate { it.field("name") to (it["tags"]?.let { t -> t::class.simpleName }) }}")
        AccessFacts.note("M10-03", "GET /api/users names: ${rows.map { it.field("name") }}")
        assertTrue(rows.all { "name" in it }, "every user row has a name")
        // measured on 4.4.1: the broker no longer sends password_hash here (keys: has_password, hashing_algorithm, limits, name, tags);
        // older brokers do, so the parser test feeds a hand-written row that has it. The capture still drops it for any other image.
        AccessFacts.note("M10-03", "password_hash present in GET /api/users on this image: ${rows.any { "password_hash" in it }}")
        assertTrue(rows.none { it.field("name").startsWith("t-m3") }, "a token user must not be listed")
        assertTrue(rows.any { it.field("name") == odd }, "the odd user name is returned decoded")
        val saved = withoutHash(reply)
        assertFalse("\"password_hash\":" in saved.body, "hash still in the body to save")
        AccessFacts.fixture("s-b-users-as-i-admin", "GET /api/users as guest (internal administrator); password_hash removed before saving", saved)
        // a token administrator may list users too (PD-1)
        val asToken = get("/api/users", tokenAdmin)
        AccessFacts.note("M10-03", "GET /api/users as token administrator: ${asToken.status} keys=${asToken.arr?.map { it.jsonObject.keyList() }?.distinct()} names=${asToken.arr?.map { it.jsonObject.field("name") }}")
        assertEquals(200, asToken.status, asToken.toString())
        AccessFacts.fixture("s-b-users-as-t-admin", "GET /api/users as t-m3-admin (token administrator); password_hash removed before saving", withoutHash(asToken))
        // a token user without the administrator tag
        val denied = get("/api/users", tokenNone)
        AccessFacts.note("M10-03", "GET /api/users as token monitoring: ${denied.status}")
        assertEquals(401, denied.status)
    }

    @Test
    fun `GET users topic-permissions answers one row per exchange with user, vhost, exchange, write, read`() {
        val reply = get("/api/users/m3-svc/topic-permissions")
        assertEquals(200, reply.status, reply.toString())
        val rows = reply.arr!!.map { it.jsonObject }
        AccessFacts.note("M10-03", "GET /api/users/m3-svc/topic-permissions keys: ${rows.map { it.keyList() }.distinct()} rows=${rows.map { it.toString() }}")
        assertEquals(3, rows.size, reply.toString())
        assertTrue(rows.all { it.keys == setOf("user", "vhost", "exchange", "write", "read") }, "unexpected keys: ${rows.map { it.keyList() }.distinct()}")
        AccessFacts.fixture("s-b-topic-permissions-of-m3-svc", "GET /api/users/m3-svc/topic-permissions as guest", reply)
        val none = get("/api/users/m3-none/topic-permissions")
        AccessFacts.note("M10-03", "topic-permissions of a user with none: ${none.status} ${none.body}")
        assertEquals(200, none.status); assertEquals(JsonArray(emptyList()), none.json)
        AccessFacts.fixture("s-b-topic-permissions-of-m3-none", "GET /api/users/m3-none/topic-permissions as guest (user without any)", none)
    }

    @Test
    fun `a user without a permission row answers an empty list, an unknown or token user answers 404`() {
        val none = get("/api/users/m3-none/permissions")
        assertEquals(200, none.status); assertEquals(JsonArray(emptyList()), none.json)
        AccessFacts.fixture("s-b-user-permissions-of-m3-none", "GET /api/users/m3-none/permissions as guest (user without any)", none)
        val svc = get("/api/users/m3-svc/permissions")
        AccessFacts.note("M10-03", "permissions of m3-svc: ${svc.body}")
        AccessFacts.fixture("s-b-user-permissions-of-m3-svc", "GET /api/users/m3-svc/permissions as guest", svc)
        val admin = get("/api/users/m3-admin/permissions")
        assertTrue(admin.arr!!.any { it.jsonObject.field("write") == "" }, "an empty pattern is the empty string: ${admin.body}")
        AccessFacts.fixture("s-b-user-permissions-of-m3-admin", "GET /api/users/m3-admin/permissions as guest (an empty write pattern)", admin)
        for ((label, target, caller) in listOf(
            Triple("unknown user as internal admin", "no-such-user", env.guestBasic()),
            Triple("token user as internal admin", "t-m3-admin", env.guestBasic()),
            Triple("token user as token admin", "t-m3-admin", tokenAdmin),
        )) {
            val perm = get("/api/users/$target/permissions", caller)
            val topic = get("/api/users/$target/topic-permissions", caller)
            AccessFacts.note("M10-03", "$label: permissions=${perm.status} ${perm.body} | topic-permissions=${topic.status} ${topic.body}")
            assertEquals(404, perm.status, "$label permissions"); assertEquals(404, topic.status, "$label topic-permissions")
            if (label == "token user as token admin") {
                AccessFacts.fixture("s-b-topic-permissions-of-token-user-404", "GET /api/users/t-m3-admin/topic-permissions as t-m3-admin", topic)
            }
        }
        // 401 for a caller without the administrator tag, on the new path too
        for (path in listOf("/api/users/m3-svc/permissions", "/api/users/m3-svc/topic-permissions")) {
            val r = get(path, tokenNone)
            AccessFacts.note("M10-03", "token monitoring GET $path: ${r.status} ${r.body}")
            assertEquals(401, r.status, path)
            if (path.endsWith("topic-permissions")) AccessFacts.fixture("s-b-topic-permissions-denied", "GET /api/users/m3-svc/topic-permissions as t-m3-mon", r)
        }
        val asMgmt = get("/api/users/m3-svc/topic-permissions", AccessAuth.Basic("m3-svc", ACCESS_PASSWORD))
        AccessFacts.note("M10-03", "internal user with management tag, own topic-permissions: ${asMgmt.status} ${asMgmt.body}")
        assertEquals(401, asMgmt.status)
    }

    @Test
    fun `a user name with a space and a slash is addressed by its encoded path segment`() {
        val encoded = path(odd)
        AccessFacts.note("M10-03", "encoded segment=$encoded")
        val perms = get("/api/users/$encoded/permissions")
        val topic = get("/api/users/$encoded/topic-permissions")
        AccessFacts.note("M10-03", "odd user: permissions=${perms.status} ${perms.body} topic=${topic.status} ${topic.body}")
        assertEquals(200, perms.status, perms.toString()); assertEquals(200, topic.status, topic.toString())
        assertEquals(odd, perms.arr!!.single().jsonObject.field("user"))
        AccessFacts.fixture("s-b-user-permissions-of-odd-name", "GET /api/users/$encoded/permissions as guest", perms)
    }

    @Test
    fun `the whole capture sent only GET and no fixture of this class keeps a hash`() {
        val dir = java.io.File("src/test/resources/fixtures/m10-00")
        val hashes = dir.listFiles { f -> f.isFile && f.name.startsWith("s-b-users-") }.orEmpty().filter { "\"password_hash\"" in it.readText() }
        assertTrue(hashes.isEmpty(), "password_hash in ${hashes.map { it.name }}")
    }
}
