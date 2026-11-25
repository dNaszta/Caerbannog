package dev.caerbannog.datarabbit

import com.rabbitmq.client.Connection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

/**
 * M10-00 spike (docs/tasks/M10-00-spike-token-access-data.md): what a 4.x broker with the OAuth 2 backend shows about token
 * users on the management API, and how the permission patterns really match. One test per item S-A to S-P (S-K is skipped:
 * owner answer AQ11 is open, working assumption skip). Results: docs/tasks/M10-00-spike-results.md; raw answers (sanitised):
 * `fixtures/m10-00/` (written when `RECORD_M10_00_FIXTURES` is set); facts: `build/m10-00-facts.txt`.
 *
 * Four throwaway brokers (same image as every live test, `BrokerImages.CURRENT_4X`):
 * A = base (oauth2 then internal), B = A + `management.rates_mode = detailed` + `preferred_username_claims`,
 * C = A + management over TLS (S-P), D = A + `management.oauth_enabled` with the default basic-auth setting (S-A).
 * Tokens are minted by the test with the JDK; the signing key and the tokens never leave this JVM and the container.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerAccessSpikeTest {
    private val orders = "/orders"
    private val other = "/other"
    private lateinit var a: AccessEnv
    private lateinit var b: AccessEnv
    private lateinit var c: AccessEnv
    private lateinit var d: AccessEnv
    private lateinit var scopeForm: String
    private lateinit var personas: Map<String, Persona>

    private fun p(label: String): Persona = personas.getValue(label)

    @BeforeAll
    fun start() {
        val ca = accessBroker().also { it.start() }
        a = AccessEnv("A", ca)
        val cb = accessBroker("management.rates_mode = detailed\nauth_oauth2.preferred_username_claims.1 = preferred_username\n").also { it.start() }
        b = AccessEnv("B", cb)
        val cc = accessBroker(tls = true).also { it.start() }
        c = AccessEnv("C", cc, tlsManagement = true)
        val cd = accessBroker("management.oauth_enabled = true\nmanagement.oauth_client_id = caerbannog-spike\nmanagement.oauth_provider_url = http://127.0.0.1:9\n").also { it.start() }
        d = AccessEnv("D", cd)
        a.addVhost(orders); a.addVhost(other)
        scopeForm = discoverScopeForm()
        personas = buildPersonas(scopeForm)
        listOf(a, b, c).forEach { seed(it) }
        val version = a.http.get("/api/overview", a.guest).obj
        AccessFacts.note("setup", "image=${BrokerImages.CURRENT_4X} rabbitmq_version=${version?.field("rabbitmq_version")} erlang=${version?.field("erlang_version")} scopeVhostForm=$scopeForm")
    }

    @AfterAll
    fun stop() {
        listOf(a, b, c, d).forEach { runCatching { it.container.stop() } }
    }

    /** The leading-slash vhost in a scope: tries the candidate forms and takes the first the broker honours (S-J, brief item 3). */
    private fun discoverScopeForm(): String {
        var winner: String? = null
        for (form in listOf("/orders", "%2Forders", "orders", "\\/orders")) {
            val token = accessToken("probe", listOf("$ACCESS_RESOURCE_SERVER.configure:$form/*", "$ACCESS_RESOURCE_SERVER.write:$form/*", "$ACCESS_RESOURCE_SERVER.read:$form/*"))
            val result = amqpOp(a, "probe", token, orders) { it.queueDeclare("scope-probe", true, false, false, null) }
            AccessFacts.note("S-J", "scope vhost form [$form] for vhost [$orders]: connect+declare = $result")
            if (winner == null && result == "allowed") winner = form
        }
        return checkNotNull(winner) { "no scope form for a vhost with a leading slash worked; see the S-J lines in build/m10-00-facts.txt" }
    }

    private fun buildPersonas(form: String): Map<String, Persona> {
        fun rights(vhostPattern: String) = listOf("configure", "write", "read").map { "$ACCESS_RESOURCE_SERVER.$it:$vhostPattern/*" }
        fun tok(label: String, tag: String?, scopes: List<String>, vhost: String, asString: Boolean = false) =
            Persona(label, label, accessToken(label, listOfNotNull(tag?.let { "$ACCESS_RESOURCE_SERVER.tag:$it" }) + scopes, asString), true, vhost)
        fun internal(label: String, vhost: String) = Persona(label, label, ACCESS_PASSWORD, false, vhost)
        return listOf(
            internal("i-admin", "/"), internal("i-mon", "/"), internal("i-mgmt", orders), internal("i-none", orders),
            tok("t-admin", "administrator", rights("*"), "/"),
            tok("t-mon", "monitoring", listOf("$ACCESS_RESOURCE_SERVER.read:*/*"), "/"),
            tok("t-mgmt", "management", rights(form), orders),
            tok("t-none", null, rights(form), orders),
            tok("t-narrow", null, listOf("$ACCESS_RESOURCE_SERVER.read:$form/queue.*", "$ACCESS_RESOURCE_SERVER.write:$form/ex.*"), orders, asString = true),
            tok("t-topic", null, listOf("$ACCESS_RESOURCE_SERVER.write:*/amq.topic/a.*"), "/"),
            tok("t-topic-plus", null, listOf("$ACCESS_RESOURCE_SERVER.write:*/amq.topic/a.*", "$ACCESS_RESOURCE_SERVER.write:*/amq.topic", "$ACCESS_RESOURCE_SERVER.read:*/amq.topic/a.*", "$ACCESS_RESOURCE_SERVER.read:*/amq.topic"), "/"),
            tok("t-multi", null, listOf("$ACCESS_RESOURCE_SERVER.read:$form/*", "$ACCESS_RESOURCE_SERVER.read:%2Fother/*"), orders),
        ).associateBy { it.label }
    }

    private fun seed(env: AccessEnv) {
        if (env !== a) { env.addVhost(orders); env.addVhost(other) }
        env.addUser("i-admin", "administrator", "/")
        env.setPermissions("i-admin", orders, ".*", ".*", ".*"); env.setPermissions("i-admin", other, ".*", ".*", ".*")
        env.addUser("i-mon", "monitoring", "/", configure = "", write = "", read = ".*")
        env.addUser("i-mgmt", "management", orders)
        env.addUser("i-none", "", orders)
        // a queue every monitoring persona can consume from; the others declare their own
        amqpOp(env, "guest", "guest", "/") { it.queueDeclare("q.shared", true, false, false, null) }
    }

    private fun summary(reply: AccessReply): String {
        val json = reply.json
        val shape = when (json) {
            is JsonObject -> "object{" + json.keyList() + "}"
            is JsonArray -> "array[${json.size}]" + (json.firstOrNull() as? JsonObject)?.let { " of {" + it.keyList() + "}" }.orEmpty()
            else -> "no-json"
        }
        return "${reply.status} $shape"
    }

    // ---------------------------------------------------------------- S-A

    @Test @Order(1)
    fun `S-A whoami and vhosts for every persona with basic and bearer auth`() {
        for (persona in personas.values.filter { it.label in setOf("i-admin", "i-mon", "i-mgmt", "i-none", "t-admin", "t-mon", "t-mgmt", "t-none", "t-multi") }) {
            val variants: List<Pair<String, AccessAuth>> = if (persona.token) listOf(
                "basic user=sub" to AccessAuth.Basic(persona.user, persona.secret),
                "basic user=empty" to AccessAuth.Basic("", persona.secret),
                "basic user=arbitrary" to AccessAuth.Basic("someone-else", persona.secret),
                "bearer" to persona.bearer,
            ) else listOf("basic" to persona.basic)
            for ((variant, auth) in variants) {
                for ((env, tag) in listOf(a to "A", d to "D")) {
                    if (env === d && !persona.token) continue
                    val who = env.http.get("/api/whoami", auth)
                    val vhosts = env.http.get("/api/vhosts", auth)
                    val names = vhosts.arr?.map { it.jsonObject.field("name") }
                    AccessFacts.note("S-A", "$tag ${persona.label} [$variant] whoami=${summary(who)} name=${who.obj?.field("name")} tags=${who.obj?.field("tags")} | vhosts=${summary(vhosts)} names=$names")
                    if (env === a && variant != "basic user=empty" && variant != "basic user=arbitrary") {
                        AccessFacts.fixture("s-a-whoami-${persona.label}-${variant.replace(' ', '-').replace('=', '-')}", "GET /api/whoami as ${persona.label} ($variant)", who)
                        AccessFacts.fixture("s-a-vhosts-${persona.label}-${variant.replace(' ', '-').replace('=', '-')}", "GET /api/vhosts as ${persona.label} ($variant)", vhosts)
                    }
                }
            }
        }
        val guestD = d.http.get("/api/whoami", d.guest)
        AccessFacts.note("S-A", "D guest basic whoami=${summary(guestD)} body=${guestD.body.take(120)}")
        val anonA = a.http.get("/api/auth", AccessAuth.Anonymous)
        val anonD = d.http.get("/api/auth", AccessAuth.Anonymous)
        AccessFacts.note("S-A", "GET /api/auth anonymous: A=${anonA.status} ${anonA.body.take(200)} | D=${anonD.status} ${anonD.body.take(400)}")
        AccessFacts.fixture("s-a-auth-A", "GET /api/auth anonymous on A (no management.oauth_enabled)", anonA)
        AccessFacts.fixture("s-a-auth-D", "GET /api/auth anonymous on D (management.oauth_enabled = true)", anonD)
        assertEquals(200, a.http.get("/api/whoami", p("i-admin").basic).status, "positive control: internal admin")
        // pinned facts (RabbitMQ 4.4.1): the broker takes the user from the token, the name sent with basic auth is ignored
        val tAdmin = p("t-admin")
        for (sent in listOf(tAdmin.user, "", "someone-else")) {
            assertEquals("t-admin", a.http.get("/api/whoami", AccessAuth.Basic(sent, tAdmin.secret)).obj?.field("name"), "basic user name [$sent] is ignored")
        }
        assertEquals("false", a.http.get("/api/whoami", tAdmin.bearer).obj?.field("is_internal_user"), "token user")
        assertEquals("true", a.http.get("/api/whoami", p("i-admin").basic).obj?.field("is_internal_user"), "internal user")
        assertEquals(listOf("/orders"), a.http.get("/api/vhosts", p("t-mgmt").bearer).arr.orEmpty().map { it.jsonObject.field("name") }, "vhosts of a management token")
        assertEquals(401, a.http.get("/api/whoami", p("t-none").bearer).status, "a token without a tag cannot use the management API")
        assertEquals(200, d.http.get("/api/whoami", tAdmin.bearer).status, "bearer with management.oauth_enabled")
        assertEquals(200, d.http.get("/api/whoami", d.guest).status, "basic stays allowed with management.oauth_enabled and the default of disable_basic_auth")
    }

    // ---------------------------------------------------------------- S-B

    @Test @Order(2)
    fun `S-B permission endpoints for internal and token users`() {
        val callers = listOf("i-admin", "t-admin", "i-mon", "t-mon", "i-mgmt", "t-mgmt")
        val targets = listOf("i-admin", "t-admin", "t-none", "t-narrow")
        for (caller in callers) {
            val auth = p(caller).basic
            val lines = mutableListOf<String>()
            val all = a.http.get("/api/permissions", auth)
            lines += "/api/permissions=${summary(all)} users=${all.arr?.map { it.jsonObject.field("user") }?.distinct()}"
            val users = a.http.get("/api/users", auth)
            lines += "/api/users=${summary(users)} names=${users.arr?.map { it.jsonObject.field("name") }}"
            val topic = a.http.get("/api/topic-permissions", auth)
            lines += "/api/topic-permissions=${summary(topic)}"
            for (t in targets) {
                val perm = a.http.get("/api/permissions/%2F/$t", auth)
                val permOrders = a.http.get("/api/permissions/%2Forders/$t", auth)
                val userPerms = a.http.get("/api/users/$t/permissions", auth)
                val topicOne = a.http.get("/api/topic-permissions/%2F/$t", auth)
                lines += "target=$t perm(/)=${perm.status} perm(/orders)=${permOrders.status} users/$t/permissions=${summary(userPerms)} topic(/)=${topicOne.status}"
                if (caller == "t-admin" && t == "t-narrow") {
                    AccessFacts.fixture("s-b-t-admin-permission-of-t-narrow", "GET /api/permissions/%2F/t-narrow as t-admin", perm)
                    AccessFacts.fixture("s-b-t-admin-user-permissions-of-t-narrow", "GET /api/users/t-narrow/permissions as t-admin", userPerms)
                }
                if (caller == "i-admin" && t == "i-admin") {
                    AccessFacts.fixture("s-b-i-admin-permission-of-i-admin", "GET /api/permissions/%2F/i-admin as i-admin", perm)
                    AccessFacts.fixture("s-b-i-admin-user-permissions-of-i-admin", "GET /api/users/i-admin/permissions as i-admin", userPerms)
                }
                if (caller == "i-mon" && t == "i-mon") AccessFacts.fixture("s-b-i-mon-denied", "GET /api/permissions/%2F/i-mon as i-mon (known 401)", perm)
                if (caller == "t-mon" && t == "t-admin") AccessFacts.fixture("s-b-t-mon-denied", "GET /api/permissions/%2F/t-admin as t-mon", perm)
            }
            AccessFacts.note("S-B", "caller=$caller ${lines.joinToString(" | ")}")
        }
        val tokenNamesInList = a.http.get("/api/permissions", p("i-admin").basic).arr.orEmpty().map { it.jsonObject.field("user") }.filter { it.startsWith("t-") }
        AccessFacts.note("S-B", "token user names present in /api/permissions (as i-admin): $tokenNamesInList")
        assertTrue(tokenNamesInList.isEmpty(), "a token user appears in the internal permission list")
        // pinned facts: no endpoint resolves a token user's permissions; the administrator-only rule holds for tokens too
        for (caller in listOf("t-admin", "i-admin")) {
            for (target in listOf("t-none", "t-narrow", "t-admin")) {
                assertEquals(404, a.http.get("/api/permissions/%2F/$target", p(caller).basic).status, "$caller reads permissions of token user $target")
                assertEquals(404, a.http.get("/api/users/$target/permissions", p(caller).basic).status, "$caller reads users/$target/permissions")
            }
        }
        for (caller in listOf("i-mon", "t-mon", "i-mgmt", "t-mgmt")) {
            assertEquals(401, a.http.get("/api/permissions/%2F/i-admin", p(caller).basic).status, "$caller is not an administrator")
            assertEquals(401, a.http.get("/api/users", p(caller).basic).status, "$caller lists users")
        }
    }

    // ---------------------------------------------------------------- S-C

    @Test @Order(3)
    fun `S-C how the OAuth 2 backend can be detected`() {
        val paths = listOf("/api/auth", "/api/overview", "/api/nodes", "/api/extensions", "/api/rabbitmq/plugins", "/api/plugins", "/api/auth/attempts/rabbit@localhost")
        for ((env, envName) in listOf(a to "A", d to "D")) {
            for (path in paths) {
                for (caller in listOf("i-admin", "t-admin", "i-mon", "t-mon", "i-mgmt", "i-none")) {
                    if (env === d && caller != "t-admin") continue
                    val auth = if (path == "/api/auth") AccessAuth.Anonymous else if (caller.startsWith("t-")) AccessAuth.Bearer(p(caller).secret) else p(caller).basic
                    val reply = env.http.get(path, auth)
                    val mentions = if (reply.body.contains("oauth", ignoreCase = true)) " mentions-oauth" else ""
                    AccessFacts.note("S-C", "$envName $path as ${if (path == "/api/auth") "anonymous" else caller}: ${summary(reply)}$mentions")
                    if (path == "/api/auth") break
                }
            }
        }
        val nodes = a.http.get("/api/nodes", p("i-admin").basic).arr.orEmpty().firstOrNull()?.jsonObject
        AccessFacts.note("S-C", "A /api/nodes[0] keys=${nodes?.keyList()}")
        AccessFacts.note("S-C", "A /api/nodes[0] auth_mechanisms=${nodes?.get("auth_mechanisms")} applications=${nodes?.get("applications")?.jsonArray?.map { it.jsonObject.field("name") }?.filter { "auth" in it || "oauth" in it }}")
        val overview = a.http.get("/api/overview", p("i-admin").basic)
        AccessFacts.fixture("s-c-overview-A", "GET /api/overview as i-admin", overview)
        AccessFacts.fixture("s-c-nodes-A", "GET /api/nodes as i-admin", a.http.get("/api/nodes", p("i-admin").basic))
        AccessFacts.fixture("s-c-extensions-A", "GET /api/extensions as i-admin", a.http.get("/api/extensions", p("i-admin").basic))
        AccessFacts.fixture("s-c-overview-D-as-t-admin", "GET /api/overview as t-admin (bearer) on D", d.http.get("/api/overview", p("t-admin").bearer))
        val nodesD = d.http.get("/api/nodes", p("t-admin").bearer)
        AccessFacts.fixture("s-c-nodes-D-as-t-admin", "GET /api/nodes as t-admin (bearer) on D", nodesD)
        // "oauth" anywhere in the answers separates which setting? (UI setting on D versus the backend)
        val oauthLines = listOf(a to "A", d to "D").map { (env, n) ->
            val body = env.http.get("/api/nodes", if (env === a) p("i-admin").basic else p("t-admin").bearer).body
            val hits = Regex("[A-Za-z0-9_.@-]*oauth[A-Za-z0-9_.@-]*", RegexOption.IGNORE_CASE).findAll(body).map { it.value }.toSet()
            "$n /api/nodes oauth-words=$hits"
        }
        AccessFacts.note("S-C", oauthLines.joinToString(" | "))
        // is the management UI's OAuth setting visible anonymously? (the answer of /api/auth was 404 on both; try the neighbours)
        for ((env, n) in listOf(a to "A", d to "D")) {
            for (path in listOf("/auth", "/api/auth", "/js/oidc-oauth/helper.js")) {
                val r = env.http.get(path, AccessAuth.Anonymous)
                AccessFacts.note("S-C", "$n anonymous GET $path: ${r.status} body-start=[${r.body.take(80).replace(Regex("\\s+"), " ")}] length=${r.body.length}")
            }
        }
        var nodesShape = ""
        runCatching { awaitUntil("D /api/nodes shows the full shape", 40) { nodesShape = d.http.get("/api/nodes", p("t-admin").bearer).arr.orEmpty().firstOrNull()?.jsonObject?.keys?.size?.toString().orEmpty(); (nodesShape.toIntOrNull() ?: 0) > 30 } }
        AccessFacts.note("S-C", "D /api/nodes[0] number of keys after a bounded wait: $nodesShape (the first call on D, right after the start, returned 6 keys)")
        val nodesDFull = d.http.get("/api/nodes", p("t-admin").bearer)
        AccessFacts.note("S-C", "D /api/nodes (full shape) oauth-words=" + Regex("[A-Za-z0-9_.@-]*oauth[A-Za-z0-9_.@-]*", RegexOption.IGNORE_CASE).findAll(nodesDFull.body).map { it.value }.toSet() + " config_files=" + nodesDFull.arr?.firstOrNull()?.jsonObject?.get("config_files"))
        val appsA = a.http.get("/api/nodes", p("t-mon").bearer).arr.orEmpty().first().jsonObject["applications"]?.jsonArray?.map { it.jsonObject.field("name") }.orEmpty()
        assertTrue("rabbitmq_auth_backend_oauth2" in appsA, "monitoring token sees the loaded applications")
        val appsMgmt = a.http.get("/api/nodes", p("i-mgmt").basic)
        assertEquals(401, appsMgmt.status, "a management user cannot read /api/nodes")
    }

    // ---------------------------------------------------------------- S-D, S-E, S-F, S-G, S-H (live clients)

    private fun connectionLine(conn: JsonObject): String =
        "name=[${conn.field("name")}] type=${conn.field("type")} protocol=${conn.field("protocol")} user=${conn.field("user")} " +
            "user_who_performed_action=${conn.field("user_who_performed_action")} auth_mechanism=${conn.field("auth_mechanism")} ssl=${conn.field("ssl")} " +
            "connection_name=[${(conn["client_properties"] as? JsonObject)?.field("connection_name")}] vhost=${conn.field("vhost")}"

    @Test @Order(4)
    fun `S-D connection user of token users on AMQP stream and MQTT`() {
        class Shape(val label: String, val token: String, val note: String)
        val observedUsers = linkedMapOf<String, Set<String>>()
        val observedWhoami = linkedMapOf<String, String>()
        val shapes = listOf(
            Shape("sub-only", accessToken("d-sub", rightsAll()), "claims: sub"),
            Shape("client-id-only", accessToken(null, rightsAll(), extra = mapOf("client_id" to "d-client")), "claims: client_id, no sub"),
            Shape("sub-and-client-id", accessToken("d-sub2", rightsAll(), extra = mapOf("client_id" to "d-client2")), "claims: sub, client_id"),
            Shape("all-three", accessToken("d-sub3", rightsAll(), extra = mapOf("client_id" to "d-client3", "preferred_username" to "d-pref3")), "claims: sub, client_id, preferred_username"),
        )
        for ((env, envName) in listOf(a to "A (default claims)", b to "B (preferred_username_claims.1 = preferred_username)")) {
            for (shape in shapes) {
                val results = mutableListOf<String>()
                results += "amqp(user=empty)=" + amqpOp(env, "", shape.token, "/") { }
                results += "amqp(user=arbitrary)=" + amqpOp(env, "arbitrary-name", shape.token, "/") { }
                results += "stream(user=arbitrary)=" + streamOp(env, "arbitrary-name", shape.token) { }
                results += "mqtt(user=arbitrary)=" + mqttOp(env, "arbitrary-name", shape.token, "d-${shape.label}") { }
                results += "mqtt(user=empty)=" + mqttOp(env, "", shape.token, "d2-${shape.label}") { }
                AccessFacts.note("S-D", "$envName ${shape.note}: ${results.joinToString(" | ")}")
            }
            // keep one connection per protocol open and read what the management API says about its user
            for (shape in shapes) {
                val conns = mutableListOf<Connection>()
                val streamEnv = com.rabbitmq.stream.Environment.builder().host(env.host).port(env.streamPort()).username("arbitrary-name").password(shape.token)
                    .addressResolver { com.rabbitmq.stream.Address(env.host, env.streamPort()) }
                val mqtt = org.eclipse.paho.mqttv5.client.MqttClient("tcp://${env.host}:${env.mqttPort()}", "d-open-${shape.label}", org.eclipse.paho.mqttv5.client.persist.MemoryPersistence())
                var environment: com.rabbitmq.stream.Environment? = null
                try {
                    runCatching { conns += accessAmqp(env, "arbitrary-name", shape.token, "/", "d-amqp-${shape.label}") }
                    runCatching { environment = streamEnv.build().also { e -> e.streamCreator().stream("d-st-${shape.label}").create(); e.producerBuilder().stream("d-st-${shape.label}").build() } }
                    runCatching { mqtt.connect(org.eclipse.paho.mqttv5.client.MqttConnectionOptions().apply { isCleanStart = true; userName = "arbitrary-name"; password = shape.token.toByteArray() }) }
                    val expected = conns.size + (if (mqtt.isConnected) 1 else 0)
                    runCatching { env.awaitJson("${shape.label}: connections visible", "/api/connections", 40) { r -> (r.arr?.count { it.jsonObject.field("name").isNotEmpty() } ?: 0) >= expected } }
                    val all = env.http.get("/api/connections", env.guest)
                    val mine = all.arr.orEmpty().map { it.jsonObject }.filter { it.field("client_properties").contains("d-") || it.field("name").isNotBlank() }
                    val whoamiName = env.http.get("/api/whoami", AccessAuth.Bearer(shape.token)).obj?.field("name")
                    AccessFacts.note("S-D", "${envName.substringBefore(' ')} ${shape.label} whoami name=$whoamiName")
                    observedWhoami["${envName.substringBefore(' ')}/${shape.label}"] = whoamiName.orEmpty()
                    for (protocolName in listOf("AMQP 0-9-1", "MQTT 5-0", "stream")) {
                        observedUsers["${envName.substringBefore(' ')}/${shape.label}/$protocolName"] =
                            all.arr.orEmpty().map { it.jsonObject }.filter { it.field("protocol") == protocolName }.map { it.field("user") }.toSet()
                    }
                    for (conn in mine) AccessFacts.note("S-D", "${envName.substringBefore(' ')} ${shape.label} /api/connections: ${connectionLine(conn)}")
                    if (env === a && shape.label == "sub-and-client-id") AccessFacts.fixture("s-d-connections-A-${shape.label}", "GET /api/connections on A while a token AMQP, stream and MQTT connection are open", all)
                    val stream = env.http.get("/api/stream/connections", env.guest)
                    for (sc in stream.arr.orEmpty().map { it.jsonObject }) AccessFacts.note("S-D", "${envName.substringBefore(' ')} ${shape.label} /api/stream/connections: keys={${sc.keyList()}} user=${sc.field("user")} auth_mechanism=${sc.field("auth_mechanism")} client_properties=${sc["client_properties"]?.toString()?.take(120)}")
                    if (env === a && shape.label == "sub-and-client-id") AccessFacts.fixture("s-d-stream-connections-A", "GET /api/stream/connections on A", stream)
                } finally {
                    conns.forEach { runCatching { it.close() } }
                    runCatching { environment?.close() }
                    runCatching { if (mqtt.isConnected) mqtt.disconnect() }
                    runCatching { mqtt.close(true) }
                }
            }
        }
        // pinned facts: the connection user is the first present claim of preferred_username_claims; the name the client sends is ignored
        val expected = mapOf(
            "A/sub-only" to "d-sub", "A/client-id-only" to "d-client", "A/sub-and-client-id" to "d-sub2", "A/all-three" to "d-sub3",
            "B/all-three" to "d-pref3", "B/sub-only" to "unknown", "B/client-id-only" to "unknown", "B/sub-and-client-id" to "unknown",
        )
        for ((key, user) in expected) {
            assertEquals(user, observedWhoami[key], "$key: whoami names the same user as the connections")
            for (protocolName in listOf("AMQP 0-9-1", "MQTT 5-0", "stream")) {
                assertTrue(user in observedUsers.getValue("$key/$protocolName"), "$key $protocolName: expected user $user, saw ${observedUsers["$key/$protocolName"]}")
            }
        }
    }

    private fun rightsAll() = listOf("configure", "write", "read").map { "$ACCESS_RESOURCE_SERVER.$it:*/*" } + "$ACCESS_RESOURCE_SERVER.tag:administrator"

    /** Opens one set of live clients per persona on [env] and waits until the management API lists them all. */
    private fun withLive(env: AccessEnv, labels: List<String>, block: (Map<String, LiveClients>) -> Unit) {
        val opened = linkedMapOf<String, LiveClients>()
        try {
            for (label in labels) {
                val persona = p(label)
                val full = label in setOf("i-admin", "t-admin")
                opened[label] = LiveClients(env, persona, label.replace("-", ""), withStreamAndMqtt = full).open()
            }
            // the monitoring personas also consume from the shared queue so there is a consumer they may see
            env.awaitJson("all consumers listed", "/api/consumers", 60) { r -> (r.arr?.size ?: 0) >= labels.size }
            env.awaitJson("all channels listed", "/api/channels", 60) { r -> (r.arr?.size ?: 0) >= labels.size * 2 }
            block(opened)
        } finally {
            opened.values.forEach { runCatching { it.close() } }
        }
    }

    private val liveLabels = listOf("i-admin", "t-admin", "i-mon", "t-mon", "i-mgmt", "t-mgmt", "i-none", "t-none")

    @Test @Order(5)
    fun `S-E S-F S-G S-H channels consumers publishers and visibility by tag`() {
        for ((env, envName) in listOf(a to "A", b to "B (rates_mode detailed)")) {
            // monitoring personas have read only: they consume from q.shared, so give them their own live clients without declares
            withLive(env, liveLabels.filter { it !in setOf("i-mon", "t-mon") }) { live ->
                val shared = liveMonitors(env)
                try {
                    env.awaitJson("monitor consumers", "/api/consumers", 60) { r -> r.arr.orEmpty().any { it.jsonObject.field("queue").contains("q.shared") } }
                    val adminAuth = env.guest
                    val tagTag = envName.substringBefore(' ')
                    val channels = env.http.get("/api/channels", adminAuth)
                    AccessFacts.note("S-E", "$envName /api/channels ${summary(channels)}")
                    for (ch in channels.arr.orEmpty().map { it.jsonObject }.take(40)) {
                        AccessFacts.note("S-E", "$tagTag channel name=[${ch.field("name")}] user=${ch.field("user")} vhost=${ch.field("vhost")} consumer_count=${ch.field("consumer_count")} connection_details=${ch["connection_details"]?.jsonObject?.keyList()} message_stats=${(ch["message_stats"] as? JsonObject)?.keyList()} has_publishes=${ch.containsKey("publishes")} publishes=${ch["publishes"]?.toString()?.take(160)}")
                    }
                    AccessFacts.fixture("s-e-channels-$tagTag", "GET /api/channels on $envName as guest", channels)
                    val conns = env.http.get("/api/connections", adminAuth)
                    val oneConn = conns.arr.orEmpty().map { it.jsonObject }.firstOrNull { (it["client_properties"] as? JsonObject)?.field("connection_name") == "spike-amqp-tadmin" }
                    if (oneConn != null) {
                        val perConn = env.http.get("/api/connections/${java.net.URLEncoder.encode(oneConn.field("name"), Charsets.UTF_8).replace("+", "%20")}/channels", adminAuth)
                        AccessFacts.note("S-E", "$tagTag /api/connections/{name}/channels ${summary(perConn)}")
                        AccessFacts.fixture("s-e-connection-channels-$tagTag", "GET /api/connections/{name}/channels (t-admin connection) on $envName", perConn)
                    }
                    for (consumersPath in listOf("/api/consumers", "/api/consumers/%2F", "/api/consumers/%2Forders")) {
                        val consumers = env.http.get(consumersPath, adminAuth)
                        AccessFacts.note("S-F", "$envName $consumersPath ${summary(consumers)}")
                        for (cn in consumers.arr.orEmpty().map { it.jsonObject }.take(20)) {
                            val cd = cn["channel_details"] as? JsonObject
                            AccessFacts.note("S-F", "$tagTag consumer queue=${(cn["queue"] as? JsonObject)?.field("name")} consumer_tag=${cn.field("consumer_tag")} ack_required=${cn.field("ack_required")} prefetch_count=${cn.field("prefetch_count")} active=${cn.field("active")} channel_details={user=${cd?.field("user")} connection_name=${cd?.field("connection_name")} peer_host=${cd?.field("peer_host")} keys=${cd?.keyList()}}")
                        }
                        if (consumersPath == "/api/consumers") AccessFacts.fixture("s-f-consumers-$tagTag", "GET /api/consumers on $envName as guest", consumers)
                    }
                    val streamConsumers = env.http.get("/api/stream/consumers", adminAuth)
                    AccessFacts.note("S-F", "$envName /api/stream/consumers ${summary(streamConsumers)} users=${streamConsumers.arr.orEmpty().map { (it.jsonObject["connection_details"] as? JsonObject)?.field("user") }}")
                    AccessFacts.fixture("s-f-stream-consumers-$tagTag", "GET /api/stream/consumers on $envName", streamConsumers)
                    val streamPublishers = env.http.get("/api/stream/publishers", adminAuth)
                    AccessFacts.note("S-G", "$envName /api/stream/publishers ${summary(streamPublishers)} users=${streamPublishers.arr.orEmpty().map { (it.jsonObject["connection_details"] as? JsonObject)?.field("user") }}")
                    AccessFacts.fixture("s-g-stream-publishers-$tagTag", "GET /api/stream/publishers on $envName", streamPublishers)
                    // S-G: who publishes to a queue or an exchange
                    val queue = env.http.get("/api/queues/%2F/q.tadmin", adminAuth)
                    AccessFacts.note("S-G", "$envName /api/queues/%2F/q.tadmin keys={${queue.obj?.keyList()}} incoming=${queue.obj?.get("incoming")?.toString()?.take(200)} consumer_details=${queue.obj?.get("consumer_details")?.toString()?.take(160)}")
                    AccessFacts.fixture("s-g-queue-$tagTag", "GET /api/queues/%2F/q.tadmin on $envName", queue)
                    val exchange = env.http.get("/api/exchanges/%2F/x.tadmin", adminAuth)
                    AccessFacts.note("S-G", "$envName /api/exchanges/%2F/x.tadmin keys={${exchange.obj?.keyList()}} incoming=${exchange.obj?.get("incoming")?.toString()?.take(240)} message_stats=${exchange.obj?.get("message_stats")?.toString()?.take(160)}")
                    AccessFacts.fixture("s-g-exchange-$tagTag", "GET /api/exchanges/%2F/x.tadmin on $envName", exchange)
                    val publishingChannel = channels.arr.orEmpty().map { it.jsonObject }.firstOrNull { it.field("name").endsWith("(2)") && it["message_stats"]?.toString()?.contains("publish") == true }
                    if (publishingChannel != null) {
                        val detail = env.http.get("/api/channels/" + java.net.URLEncoder.encode(publishingChannel.field("name"), Charsets.UTF_8).replace("+", "%20"), adminAuth)
                        AccessFacts.note("S-G", "$envName /api/channels/{name} of a publishing channel: ${detail.status} has_publishes=${detail.obj?.containsKey("publishes")} publishes=${detail.obj?.get("publishes")?.toString()?.take(200)}")
                        AccessFacts.fixture("s-g-channel-detail-$tagTag", "GET /api/channels/{name} of a publishing channel on $envName", detail)
                    }
                    // S-H: what each persona sees
                    val total = mapOf("connections" to env.http.get("/api/connections", adminAuth).arr?.size, "channels" to channels.arr?.size, "consumers" to env.http.get("/api/consumers", adminAuth).arr?.size)
                    AccessFacts.note("S-H", "$envName whole broker (guest): $total")
                    for (label in liveLabels) {
                        val persona = p(label)
                        val auth = persona.basic
                        val line = listOf("/api/connections", "/api/channels", "/api/consumers", "/api/stream/connections", "/api/stream/consumers", "/api/stream/publishers", "/api/queues", "/api/exchanges").joinToString(" ") { path ->
                            val r = env.http.get(path, auth)
                            val users = r.arr?.map { it.jsonObject.let { o -> (o["connection_details"] as? JsonObject)?.field("user") ?: (o["channel_details"] as? JsonObject)?.field("user") ?: o.field("user") } }?.distinct()
                            "$path=${r.status}/${r.arr?.size}${if (path.startsWith("/api/q") || path.startsWith("/api/ex")) "" else users?.toString().orEmpty()}"
                        }
                        AccessFacts.note("S-H", "$tagTag $label: $line")
                        if (env === a && label in setOf("i-mon", "t-mon", "i-mgmt", "t-mgmt", "i-none", "t-none")) {
                            AccessFacts.fixture("s-h-connections-$label", "GET /api/connections as $label on A", env.http.get("/api/connections", auth))
                        }
                    }
                    assertTrue(live.isNotEmpty())
                    // pinned facts of S-G and S-H
                    val incoming = exchange.obj?.get("incoming")?.jsonArray?.size ?: -1
                    if (env === a) assertEquals(0, incoming, "exchange incoming is empty with the default rates_mode")
                    else assertTrue(incoming > 0, "exchange incoming lists the publishing channel with rates_mode detailed")
                    assertTrue(queue.obj?.get("incoming")?.jsonArray.orEmpty().all { it.jsonObject["channel_details"] == null }, "a queue's incoming names exchanges, not users")
                    if (env === a) {
                        val ownOnly = listOf("i-mgmt", "t-mgmt").map { env.http.get("/api/connections", p(it).basic).arr?.size }
                        assertEquals(listOf(1, 1), ownOnly, "management tag sees its own connections only")
                        assertEquals(total.getValue("connections"), env.http.get("/api/connections", p("t-mon").basic).arr?.size, "monitoring token sees the whole broker")
                        assertEquals(401, env.http.get("/api/connections", p("t-none").basic).status, "no tag, no access")
                    }
                } finally {
                    shared.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    /** Monitoring personas hold a consumer on q.shared in the default vhost (read permission only). */
    private fun liveMonitors(env: AccessEnv): List<Connection> = listOf("i-mon", "t-mon").mapNotNull { label ->
        val persona = p(label)
        runCatching {
            val conn = accessAmqp(env, persona.user, persona.secret, "/", "spike-amqp-$label")
            val ch = conn.createChannel()
            ch.basicConsume("q.shared", true, "consumer-$label", object : com.rabbitmq.client.DefaultConsumer(ch) {})
            conn
        }.onFailure { AccessFacts.note("S-H", "${env.name} $label could not hold a consumer: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- S-I

    private data class Row(val permission: String, val pattern: String, val name: String, val operation: String, val outcome: String) {
        val allowed: Boolean get() = outcome == "allowed"
    }

    private val rows = mutableListOf<Row>()

    private fun row(permission: String, pattern: String, name: String, operation: String, outcome: String): Row {
        val r = Row(permission, pattern, name, operation, outcome)
        rows += r
        AccessFacts.note("S-I", "row permission=$permission pattern=[$pattern] name=[$name] operation=$operation -> ${if (r.allowed) "allowed" else outcome.take(190)}")
        return r
    }

    @Test @Order(6)
    fun `S-I pattern matching table on an internal user`() {
        val user = "s-i-user"
        a.addUser(user, "", "/")
        // objects the operations act on
        amqpOp(a, "guest", "guest", "/") { ch ->
            listOf("ex", "ex.orders", "my-ex", "EX", "ex.").forEach { ch.exchangeDeclare(it, "direct", true) }
            listOf("queue", "queue.a", "my-queue", "Queue.a").forEach { ch.queueDeclare(it, true, false, false, null) }
        }
        fun with(configure: String = ".*", write: String = ".*", read: String = ".*", op: (com.rabbitmq.client.Channel) -> Unit): String {
            val set = a.setPermissions(user, "/", configure, write, read)
            check(set.ok) { "could not set permissions [$configure|$write|$read]: $set" }
            return amqpOp(a, user, ACCESS_PASSWORD, "/", op)
        }
        // write on an exchange
        for (pattern in listOf("ex", "^ex$", "ex.*", ".*ex", "^$", "", ".*", "(?i)ex", "EX", "ex\\.orders", "^ex\\.")) {
            for (name in listOf("ex", "ex.orders", "my-ex", "EX")) {
                row("write", pattern, name, "basic.publish to exchange", with(write = pattern) { it.publishConfirmed(name, "k") })
            }
        }
        // read on a queue
        for (pattern in listOf("queue", "^queue$", "queue.*", "^$", "", ".*")) {
            for (name in listOf("queue", "queue.a", "my-queue", "Queue.a")) {
                row("read", pattern, name, "basic.get on queue", with(read = pattern) { it.basicGet(name, false) })
            }
        }
        row("read", "queue", "queue", "basic.consume on queue", with(read = "queue") { it.basicConsume("queue", true, object : com.rabbitmq.client.DefaultConsumer(it) {}) })
        row("read", "^$", "queue", "basic.consume on queue", with(read = "^$") { it.basicConsume("queue", true, object : com.rabbitmq.client.DefaultConsumer(it) {}) })
        // configure
        for (pattern in listOf("new", "new.*", "^new$", "^$", "")) {
            for (name in listOf("new", "new.x")) {
                row("configure", pattern, name, "queue.declare", with(configure = pattern) { it.queueDeclare(name, true, false, false, null) })
                a.admin("DELETE", "/api/queues/%2F/$name")
            }
        }
        row("configure", "new", "new", "exchange.declare", with(configure = "new") { it.exchangeDeclare("new", "direct", false, true, null) }).also { a.admin("DELETE", "/api/exchanges/%2F/new") }
        // the default exchange: which resource name does a publish through it check
        for (pattern in listOf("^$", "", "amq\\.default", "^amq\\.default$", ".*", "queue", "^queue$")) {
            row("write", pattern, "(default exchange, routing key queue)", "basic.publish to \"\"", with(write = pattern) { it.publishConfirmed("", "queue") })
        }
        // queue.bind: write on the queue, read on the exchange
        for ((w, r) in listOf(".*" to ".*", "queue" to "ex", "" to ".*", ".*" to "", "queue" to "", "^$" to "^ex$")) {
            row("write+read", "write=[$w] read=[$r]", "bind queue to ex", "queue.bind", with(write = w, read = r) { it.queueBind("queue", "ex", "k") })
        }
        // an invalid regex: can it be set, and what happens on use
        val invalid = a.setPermissions(user, "/", ".*", "[", ".*")
        AccessFacts.note("S-I", "invalid regex [ via PUT /api/permissions: ${invalid.status} ${invalid.body.take(160)}")
        AccessFacts.fixture("s-i-invalid-regex", "PUT /api/permissions/%2F/s-i-user with write=[", invalid)
        if (invalid.ok) row("write", "[", "ex", "basic.publish to exchange", amqpOp(a, user, ACCESS_PASSWORD, "/") { it.publishConfirmed("ex", "k") })
        val invalidCtl = a.container.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", user, ".*", "[", ".*")
        AccessFacts.note("S-I", "invalid regex [ via rabbitmqctl: exit=${invalidCtl.exitCode} ${(invalidCtl.stdout + invalidCtl.stderr).replace(Regex("\\s+"), " ").take(200)}")
        // topic permissions on amq.topic, AMQP: the routing key pattern
        for (pattern in listOf("^a\\..*", "a", "a.*", "^a\\.b$", "", ".*")) {
            a.setPermissions(user, "/", ".*", ".*", ".*")
            val set = a.setTopicPermissions(user, "/", "amq.topic", pattern, ".*")
            check(set.ok) { "topic permission [$pattern]: $set" }
            for (key in listOf("a.b", "a.b.c", "b.c", "xa.b")) {
                row("topic write", pattern, "amq.topic rk=$key", "basic.publish", amqpOp(a, user, ACCESS_PASSWORD, "/") { it.publishConfirmed("amq.topic", key) })
            }
        }
        a.admin("DELETE", "/api/topic-permissions/%2F/$user")
        row("topic write", "(no topic permission)", "amq.topic rk=b.c", "basic.publish", amqpOp(a, user, ACCESS_PASSWORD, "/") { it.publishConfirmed("amq.topic", "b.c") })
        // MQTT topic to routing key (a/b to a.b) against a topic permission
        a.setPermissions(user, "/", ".*", ".*", ".*")
        a.setTopicPermissions(user, "/", "amq.topic", "^a\\..*", ".*")
        for (topic in listOf("a/b", "a/b/c", "b/c", "a.b")) {
            row("topic write (MQTT)", "^a\\..*", "mqtt topic $topic", "publish qos1", mqttOp(a, user, ACCESS_PASSWORD, "si-mqtt") { it.publish(topic, "spike".toByteArray(), 1, false) })
        }
        a.admin("DELETE", "/api/topic-permissions/%2F/$user")
        // stream permissions
        val streamName = "st.perm"
        AccessFacts.note("S-I", "stream create as guest: " + streamOp(a, "guest", "guest") { it.streamCreator().stream(streamName).create() })
        for ((w, r) in listOf(".*" to ".*", ".*" to "", "" to ".*", "^st\\.perm$" to "^st\\.perm$", "st" to "st", "st.*" to "st.*")) {
            a.setPermissions(user, "/", ".*", w, r)
            row("write", w, streamName, "stream declare publisher", streamOp(a, user, ACCESS_PASSWORD) { it.publishOne(streamName) })
            row("read", r, streamName, "stream subscribe", streamOp(a, user, ACCESS_PASSWORD) { it.consumeFirst(streamName) })
        }
        a.setPermissions(user, "/", "", ".*", ".*")
        row("configure", "", "st.new", "stream create", streamOp(a, user, ACCESS_PASSWORD) { it.streamCreator().stream("st.new").create() })
        a.setPermissions(user, "/", "st\\.new", ".*", ".*")
        row("configure", "st\\.new", "st.new", "stream create", streamOp(a, user, ACCESS_PASSWORD) { it.streamCreator().stream("st.new").create() })
        a.admin("DELETE", "/api/queues/%2F/st.new")
        AccessFacts.note("S-I", "row count=${rows.size}")
        assertTrue(rows.size >= 30, "the brief wants at least 30 rows")
        fun allowed(permission: String, pattern: String, name: String, operation: String): Boolean =
            rows.first { it.permission == permission && it.pattern == pattern && it.name == name && it.operation == operation }.allowed
        val publish = "basic.publish to exchange"
        assertTrue(allowed("write", "ex", "my-ex", publish), "a pattern is not anchored: ex matches my-ex")
        assertTrue(allowed("write", "ex", "ex.orders", publish), "a pattern is not anchored: ex matches ex.orders")
        assertTrue(!allowed("write", "^ex$", "ex.orders", publish), "^ex$ is exact")
        assertTrue(!allowed("write", "ex", "EX", publish), "case sensitive")
        assertTrue(allowed("write", "(?i)ex", "EX", publish), "(?i) switches case off")
        assertTrue(!allowed("write", "", "ex", publish) && !allowed("write", "^$", "ex", publish), "the empty pattern and ^$ allow no named object")
        assertTrue(allowed("write", "amq\\.default", "(default exchange, routing key queue)", "basic.publish to \"\""), "the default exchange is checked as amq.default")
        assertTrue(!allowed("write", "queue", "(default exchange, routing key queue)", "basic.publish to \"\""), "the target queue name is not what is checked")
        assertTrue(!allowed("write", "^$", "(default exchange, routing key queue)", "basic.publish to \"\""), "the default exchange is not the empty name")
        assertTrue(allowed("topic write", "a", "amq.topic rk=xa.b", "basic.publish"), "a topic pattern is not anchored either")
        assertTrue(!allowed("topic write", "^a\\..*", "amq.topic rk=xa.b", "basic.publish"), "an anchored topic pattern refuses xa.b")
        assertTrue(allowed("topic write", "(no topic permission)", "amq.topic rk=b.c", "basic.publish"), "without a topic permission nothing is limited")
        assertTrue(allowed("topic write (MQTT)", "^a\\..*", "mqtt topic a/b", "publish qos1") && !allowed("topic write (MQTT)", "^a\\..*", "mqtt topic b/c", "publish qos1"), "MQTT topic a/b is checked as a.b")
        assertTrue(allowed("write", "st", "st.perm", "stream declare publisher") && !allowed("read", "", "st.perm", "stream subscribe"), "stream write is publisher declare, read is subscribe")
        AccessFacts.fixtureText("s-i-pattern-rows.tsv", "permission\tpattern\tname\toperation\toutcome\n" +
            rows.joinToString("\n") { "${it.permission}\t${it.pattern}\t${it.name}\t${it.operation}\t${if (it.allowed) "allowed" else "refused"}" } + "\n")
    }

    // ---------------------------------------------------------------- S-J

    @Test @Order(7)
    fun `S-J scopes to permissions behaviourally`() {
        amqpOp(a, "guest", "guest", orders) { ch ->
            listOf("ex.a", "other-ex").forEach { ch.exchangeDeclare(it, "direct", true) }
            listOf("queue.a", "other").forEach { ch.queueDeclare(it, true, false, false, null) }
        }
        amqpOp(a, "guest", "guest", other) { it.queueDeclare("queue.a", true, false, false, null) }
        val narrow = p("t-narrow")
        fun narrowOp(what: String, vhost: String = orders, op: (com.rabbitmq.client.Channel) -> Unit) =
            AccessFacts.note("S-J", "t-narrow [read ${scopeForm}/queue.*, write ${scopeForm}/ex.* as ONE space-separated string] $what -> " + amqpOp(a, narrow.user, narrow.secret, vhost, op))
        narrowOp("basic.get queue.a (read queue.*)") { it.basicGet("queue.a", false) }
        narrowOp("basic.get other (not matching)") { it.basicGet("other", false) }
        narrowOp("publish to ex.a (write ex.*)") { it.publishConfirmed("ex.a", "k") }
        narrowOp("publish to other-ex (not matching)") { it.publishConfirmed("other-ex", "k") }
        narrowOp("queue.declare new-q (no configure scope)") { it.queueDeclare("new-q", true, false, false, null) }
        narrowOp("connect to vhost / (not in scope)", "/") { }
        val multi = p("t-multi")
        for (vhost in listOf(orders, other, "/")) {
            AccessFacts.note("S-J", "t-multi [read ${scopeForm}/* and %2Fother/*] basic.get queue.a in vhost [$vhost] -> " + amqpOp(a, multi.user, multi.secret, vhost) { it.basicGet("queue.a", false) })
        }
        for (label in listOf("t-topic", "t-topic-plus")) {
            val t = p(label)
            for (key in listOf("a.b", "b.c")) {
                AccessFacts.note("S-J", "$label publish amq.topic rk=$key -> " + amqpOp(a, t.user, t.secret, "/") { it.publishConfirmed("amq.topic", key) })
            }
            for (topic in listOf("a/b", "b/c")) {
                AccessFacts.note("S-J", "$label MQTT publish $topic -> " + mqttOp(a, t.user, t.secret, "j-$label") { it.publish(topic, "x".toByteArray(), 1, false) })
            }
        }
        // the same pairs of rights as an internal user, for the comparison of S-I
        for (label in listOf("t-mgmt", "t-none")) {
            val t = p(label)
            AccessFacts.note("S-J", "$label (rights $scopeForm/*) declare+publish+get in $orders -> " + amqpOp(a, t.user, t.secret, orders) {
                it.queueDeclare("j-${t.label}", true, false, false, null); it.publishConfirmed("", "j-${t.label}"); it.basicGet("j-${t.label}", false)
            } + " | in / -> " + amqpOp(a, t.user, t.secret, "/") { })
        }
        AccessFacts.note("S-J", "scope_aliases and extra_scopes_source: not measured")
        // pinned facts: the effect follows the documented scope syntax; a vhost with a leading slash is written %2F in the scope
        assertEquals("%2Forders", scopeForm)
        fun op(label: String, vhost: String, block: (com.rabbitmq.client.Channel) -> Unit) = amqpOp(a, p(label).user, p(label).secret, vhost, block)
        assertEquals("allowed", op("t-narrow", orders) { it.basicGet("queue.a", false) })
        assertTrue(op("t-narrow", orders) { it.basicGet("other", false) }.startsWith("refused"))
        assertEquals("allowed", op("t-narrow", orders) { it.publishConfirmed("ex.a", "k") })
        assertTrue(op("t-narrow", orders) { it.publishConfirmed("other-ex", "k") }.startsWith("refused"))
        assertTrue(op("t-narrow", orders) { it.queueDeclare("new-q", true, false, false, null) }.startsWith("refused"))
        assertTrue(op("t-narrow", "/") { }.startsWith("connect-failed"), "a vhost outside the scope cannot be opened")
        assertEquals("allowed", op("t-multi", orders) { it.basicGet("queue.a", false) })
        assertEquals("allowed", op("t-multi", other) { it.basicGet("queue.a", false) })
        assertTrue(op("t-multi", "/") { }.startsWith("connect-failed"))
        assertEquals("allowed", op("t-topic", "/") { it.publishConfirmed("amq.topic", "a.b") }, "a topic scope also gives write on the exchange")
        assertTrue(op("t-topic", "/") { it.publishConfirmed("amq.topic", "b.c") }.startsWith("refused"))
    }

    // ---------------------------------------------------------------- S-L, S-M

    @Test @Order(8)
    fun `S-L S-M refused tokens - expired bad signature no audience`() {
        val scopes = rightsAll()
        val bad = linkedMapOf(
            "t-expired" to accessToken("t-expired", scopes, expiresInSeconds = -120),
            "t-badsig" to accessToken("t-badsig", scopes, signWith = AccessKeys.other),
            "t-noaud" to accessToken("t-noaud", scopes, aud = null),
            "t-wrongaud" to accessToken("t-wrongaud", scopes, aud = JsonPrimitive("someone-else")),
            "t-unknown-kid" to accessToken("t-unknown-kid", scopes, kid = "no-such-key"),
            "t-no-kid" to accessToken("t-no-kid", scopes, kid = null),
            "t-garbage" to "not.a.jwt",
        )
        for ((label, token) in bad) {
            val basic = a.http.get("/api/whoami", AccessAuth.Basic(label, token))
            val bearer = a.http.get("/api/whoami", AccessAuth.Bearer(token))
            val vhosts = a.http.get("/api/vhosts", AccessAuth.Basic(label, token))
            AccessFacts.note("S-L", "$label whoami basic=${basic.status} ${basic.body.take(140)} | bearer=${bearer.status} ${bearer.body.take(140)} | vhosts=${vhosts.status}")
            AccessFacts.note("S-L", "$label amqp=${amqpOp(a, label, token, "/") { }} | stream=${streamOp(a, label, token) { }} | mqtt=${mqttOp(a, label, token, "l-$label") { }}")
            assertEquals(if (label == "t-no-kid") 200 else 401, basic.status, "$label over basic")
            assertEquals(if (label == "t-no-kid") 200 else 401, bearer.status, "$label over bearer")
            if (label == "t-expired") {
                AccessFacts.fixture("s-l-expired-whoami-basic", "GET /api/whoami with an expired token (basic)", basic)
                AccessFacts.fixture("s-l-expired-whoami-bearer", "GET /api/whoami with an expired token (bearer)", bearer)
            }
            if (label == "t-badsig") AccessFacts.fixture("s-m-badsig-whoami-basic", "GET /api/whoami with a bad signature (basic)", basic)
        }
        // a connection that outlives its token
        val shortLived = accessToken("t-shortlived", scopes, expiresInSeconds = 12)
        val conn = accessAmqp(a, "t-shortlived", shortLived, "/", "l-shortlived")
        try {
            a.awaitJson("short-lived connection listed", "/api/connections", 40) { r -> r.arr.orEmpty().any { it.jsonObject.field("user") == "t-shortlived" } }
            var closedAfter = -1L
            val start = System.nanoTime()
            try {
                awaitUntil("connection closed after token expiry", 90) { !conn.isOpen }
                closedAfter = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
            } catch (_: IllegalStateException) { /* still open: recorded below */ }
            val closeReason = conn.closeReason?.reason?.toString()?.replace(Regex("\\s+"), " ").orEmpty()
            AccessFacts.note("S-L", "connection of a token with exp=+12s: open after wait=${conn.isOpen} closedAfterSeconds=$closedAfter (bounded wait 90 s) closeReason=${closeReason.take(200)}")
            assertTrue(!conn.isOpen && "credential expired" in closeReason, "the broker closes a connection whose token expired: $closeReason")
        } finally {
            runCatching { conn.close() }
        }
        AccessFacts.note("S-M", "leaks found so far in any response body: ${AccessSecrets.leaks.distinct()}")
    }

    // ---------------------------------------------------------------- S-N

    @Test @Order(9)
    fun `S-N connection names that look like secrets`() {
        val fakeJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJmYWtlLXVzZXIifQ.ZmFrZS1zaWduYXR1cmU"
        val factory = com.rabbitmq.client.ConnectionFactory().apply {
            host = a.host; port = a.amqpPort(); username = "guest"; password = "guest"; isAutomaticRecoveryEnabled = false
        }
        val conns = mutableListOf<Connection>()
        try {
            conns += factory.newConnection(fakeJwt)
            conns += factory.newConnection("amqps://spike-user:spike-pw@broker.example:5671/vhost")
            factory.clientProperties = factory.clientProperties.toMutableMap().apply { put("password", "x"); put("note", "password=x") }
            conns += factory.newConnection("plain-name-with-password-property")
            val reply = a.awaitJson("S-N connections listed", "/api/connections", 45) { r -> r.arr.orEmpty().count { it.jsonObject.field("client_properties").contains("spike-user") || it.jsonObject.field("client_properties").contains("eyJ") } >= 1 && r.arr.orEmpty().any { it.jsonObject.field("client_properties").contains("\"password\"") } }
            for (conn in reply.arr.orEmpty().map { it.jsonObject }) AccessFacts.note("S-N", connectionLine(conn) + " client_properties_keys={" + (conn["client_properties"] as? JsonObject)?.keyList() + "}")
            assertTrue(reply.body.contains(fakeJwt) && reply.body.contains("amqps://spike-user:spike-pw@broker.example") && reply.body.contains("\"password\""), "client-set names and properties come back verbatim: redaction is the app's job")
            AccessFacts.note("S-N", "JWT-shaped connection_name returned verbatim: " + reply.body.contains(fakeJwt))
            AccessFacts.note("S-N", "amqps URI with credentials in name returned verbatim: " + reply.body.contains("amqps://spike-user:spike-pw@broker.example"))
            AccessFacts.note("S-N", "client property password=x returned: " + (reply.body.contains("\"password\"") && reply.body.contains("\"x\"")))
            AccessFacts.fixture("s-n-connections-secret-shaped-names", "GET /api/connections with a JWT-shaped name, a URI with credentials and a password client property", reply)
            val channels = a.http.get("/api/channels", a.guest)
            AccessFacts.note("S-N", "JWT-shaped name also in /api/channels (connection_details.name): " + channels.body.contains(fakeJwt))
            AccessFacts.fixture("s-n-channels-secret-shaped-names", "GET /api/channels with the same connections open", channels)
        } finally {
            conns.forEach { runCatching { it.close() } }
        }
    }

    // ---------------------------------------------------------------- S-P

    @Test @Order(10)
    fun `S-P management over TLS answers the same as plain`() {
        val https = checkNotNull(c.httpsOrNull)
        val plain = c.http
        val callers = listOf("i-admin", "t-admin", "i-mon", "t-mon", "i-mgmt")
        val differences = mutableListOf<String>()
        val clients = LiveClients(c, p("t-admin"), "tlsadmin").open()
        try {
            c.awaitJson("S-P connections", "/api/connections", 45) { (it.arr?.size ?: 0) >= 2 }
            for (caller in callers) {
                val persona = p(caller)
                for (path in listOf("/api/whoami", "/api/vhosts", "/api/permissions", "/api/users", "/api/topic-permissions", "/api/connections", "/api/channels", "/api/consumers", "/api/stream/connections", "/api/overview", "/api/auth")) {
                    val s = summary(https.get(path, persona.basic))
                    val pl = summary(plain.get(path, persona.basic))
                    val line = "$caller $path TLS=[$s] plain=[$pl]"
                    AccessFacts.note("S-P", line)
                    if (s != pl) differences += line
                }
            }
            val bearer = https.get("/api/whoami", p("t-admin").bearer)
            AccessFacts.note("S-P", "bearer over TLS whoami=${summary(bearer)}")
            val conn = https.get("/api/connections", p("t-admin").basic)
            AccessFacts.note("S-P", "connection fields over TLS (AMQP plain): ${conn.arr.orEmpty().map { it.jsonObject.field("ssl") }.distinct()}")
            AccessFacts.fixture("s-p-whoami-t-admin-tls", "GET https://:15671/api/whoami as t-admin (basic, token as password)", https.get("/api/whoami", p("t-admin").basic))
            AccessFacts.fixture("s-p-connections-tls", "GET https://:15671/api/connections as t-admin", conn)
            AccessFacts.note("S-P", "differences between TLS and plain (by status and shape): ${differences.size} ${differences}")
            assertTrue(differences.isEmpty(), "TLS on the management port changes an answer: $differences")
            assertEquals(200, bearer.status)
        } finally {
            clients.close()
        }
    }

    // ---------------------------------------------------------------- S-O (last: it opens 600 connections)

    @Test @Order(99)
    fun `S-O columns paging sorting and 600 connections`() {
        val admin = a.guest
        val demo = LiveClients(a, p("t-admin"), "sodemo").open()
        try {
            a.awaitJson("S-O demo clients listed", "/api/consumers", 45) { (it.arr?.size ?: 0) >= 2 }
            a.awaitJson("S-O demo channels listed", "/api/channels", 45) { (it.arr?.size ?: 0) >= 2 }
            columnsAndPaging(admin)
        } finally {
            demo.close()
        }
        bulkConnections(admin)
    }

    private fun columnsAndPaging(admin: AccessAuth) {
        for (path in listOf("/api/connections", "/api/channels", "/api/consumers")) {
            val plain = a.http.get(path, admin)
            val cols = when (path) { "/api/connections" -> "name,user"; "/api/channels" -> "name,user"; else -> "consumer_tag,queue" }
            val limited = a.http.get("$path?columns=$cols", admin)
            AccessFacts.note("S-O", "$path default: ${summary(plain)} | columns=$cols: ${summary(limited)}")
            val paged = a.http.get("$path?page=1&page_size=2", admin)
            AccessFacts.note("S-O", "$path page=1&page_size=2: ${summary(paged)} items=${paged.obj?.get("items")?.jsonArray?.size} total_count=${paged.obj?.field("total_count")} page_count=${paged.obj?.field("page_count")} page_size=${paged.obj?.field("page_size")}")
            AccessFacts.fixture("s-o-${path.substringAfterLast('/')}-columns", "GET $path?columns=$cols", limited)
            AccessFacts.fixture("s-o-${path.substringAfterLast('/')}-paged", "GET $path?page=1&page_size=2", paged)
        }
        val one = a.http.get("/api/connections?page=1&page_size=1&columns=name,user,client_properties", admin)
        AccessFacts.note("S-O", "columns=name,user,client_properties: item keys={${one.obj?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.keyList()}}")
        val someName = one.obj?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.field("name").orEmpty()
        val byName = a.http.get("/api/connections?page=1&page_size=10&columns=name&name=" + java.net.URLEncoder.encode(someName.substringBefore(" ->"), Charsets.UTF_8), admin)
        AccessFacts.note("S-O", "name filter with a real connection name prefix: items=${byName.obj?.get("items")?.jsonArray?.size} filtered_count=${byName.obj?.field("filtered_count")}")
    }

    private fun bulkConnections(admin: AccessAuth) {
        val factory = com.rabbitmq.client.ConnectionFactory().apply {
            host = a.host; port = a.amqpPort(); username = "guest"; password = "guest"; isAutomaticRecoveryEnabled = false
        }
        val conns = mutableListOf<Connection>()
        try {
            val opened = System.nanoTime()
            repeat(600) { conns += factory.newConnection("bulk-$it") }
            AccessFacts.note("S-O", "opened 600 AMQP connections in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - opened)} ms")
            var lastTotal = 0
            awaitUntil("600 connections listed by the management API", 150) {
                lastTotal = a.http.get("/api/connections?page=1&page_size=1&columns=name", admin).obj?.field("total_count")?.toIntOrNull() ?: 0
                lastTotal >= 600
            }
            AccessFacts.note("S-O", "total_count after wait: $lastTotal")
            for (size in listOf(500, 1000)) {
                val start = System.nanoTime()
                val page = a.http.get("/api/connections?page=1&page_size=$size", admin)
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                AccessFacts.note("S-O", "page_size=$size: status=${page.status} items=${page.obj?.get("items")?.jsonArray?.size} reported page_size=${page.obj?.field("page_size")} page_count=${page.obj?.field("page_count")} body=${page.body.length} bytes in $ms ms")
            }
            assertEquals(400, a.http.get("/api/connections?page=1&page_size=1000", admin).status, "page_size above 500 is refused")
            assertEquals(500, a.http.get("/api/connections?page=1&page_size=500", admin).obj?.get("items")?.jsonArray?.size, "a page of 500 of 600")
            val start = System.nanoTime()
            val slim =a.http.get("/api/connections?page=1&page_size=500&columns=name,user,vhost,protocol", admin)
            AccessFacts.note("S-O", "page_size=500 columns=name,user,vhost,protocol: items=${slim.obj?.get("items")?.jsonArray?.size} body=${slim.body.length} bytes in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)} ms")
            val unpaged = a.http.get("/api/connections", admin)
            AccessFacts.note("S-O", "without page: ${summary(unpaged)} body=${unpaged.body.length} bytes")
            val sorted = a.http.get("/api/connections?page=1&page_size=3&sort=name&sort_reverse=true&columns=name", admin)
            AccessFacts.note("S-O", "sort=name reverse: ${sorted.obj?.get("items")?.jsonArray?.map { it.jsonObject.field("name") }}")
            val filtered = a.http.get("/api/connections?page=1&page_size=500&name=bulk-1&columns=name", admin)
            AccessFacts.note("S-O", "name=bulk-1 (no regex): items=${filtered.obj?.get("items")?.jsonArray?.size} total=${filtered.obj?.field("filtered_count")}")
            val regex = a.http.get("/api/connections?page=1&page_size=500&name=%5Ebulk-1%24&use_regex=true&columns=name", admin)
            AccessFacts.note("S-O", "name=^bulk-1\$ use_regex=true: items=${regex.obj?.get("items")?.jsonArray?.size}")
            AccessFacts.fixture("s-o-connections-slim-page", "GET /api/connections?page=1&page_size=500&columns=name,user,vhost,protocol (first 3 items kept)",
                AccessReply(slim.status, slim.obj?.let { o -> JsonObject(o + ("items" to JsonArray(o["items"]?.jsonArray.orEmpty().take(3)))).toString() } ?: slim.body))
        } finally {
            conns.forEach { runCatching { it.close() } }
        }
    }

    // ---------------------------------------------------------------- S-D security row, S-M

    @Test @Order(100)
    fun `S-D S-M no response body of the whole run contains a token its payload or the password`() {
        AccessFacts.note("S-M", "final scan of every response body of every endpoint called in this run: leaks=${AccessSecrets.leaks.distinct()}")
        assertTrue(AccessSecrets.leaks.isEmpty(), "a secret came back in a response body: ${AccessSecrets.leaks.distinct()}")
    }
}
