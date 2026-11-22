package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.util.Base64

/**
 * M8-00 spike, part 1 (S-A to S-E, S-G, S-H, S-K, S-L, S-M): what a real RabbitMQ 3.13 exports and what a real RabbitMQ 4.x accepts.
 * Findings and their meaning for the M8 rules are written in docs/tasks/M8-00-spike-results.md; every assertion here pins one of them.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeDefinitionsTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeDefinitionsTest {

    companion object {
        private val old: RabbitMQContainer = broker(BrokerImages.LEGACY_3X)
        private val new: RabbitMQContainer = broker(BrokerImages.CURRENT_4X)
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi

        @JvmStatic @BeforeAll fun start() {
            old.start(); new.start()
            o = MgmtApi(old); n = MgmtApi(new)
            seedOld()
        }

        @JvmStatic @AfterAll fun stop() { old.stop(); new.stop() }

        /** A 3.x topology with everything 4.x dislikes: mirrored/lazy/priority/locator queues, transient queues, ha policies, users, perms. */
        private fun seedOld() {
            o.put("/api/vhosts/v1", "{}")
            o.declareQueue("classic-plain")
            o.declareQueue("classic-lazy", """{"x-queue-mode":"lazy"}""")
            o.declareQueue("classic-prio", """{"x-max-priority":5}""")
            o.declareQueue("classic-locator", """{"x-queue-master-locator":"min-masters"}""")
            o.declareQueue("classic-ttl", """{"x-message-ttl":60000,"x-expires":3600000,"x-max-length":1000,"x-overflow":"reject-publish"}""")
            o.declareQueue("classic-transient", durable = false)
            o.declareQueue("classic-autodel", durable = false, autoDelete = true)
            o.declareQueue("quorum-q", """{"x-queue-type":"quorum"}""")
            o.declareQueue("v1-q", vhost = "v1")
            o.put("/api/exchanges/%2F/dlx", """{"type":"direct","durable":true,"arguments":{}}""")
            o.put("/api/exchanges/%2F/ex-e2e", """{"type":"direct","durable":true,"arguments":{}}""")
            o.post("/api/bindings/%2F/e/dlx/q/classic-plain", """{"routing_key":"dead"}""")
            o.post("/api/bindings/%2F/e/ex-e2e/e/dlx", """{"routing_key":"e2e"}""")
            o.put("/api/policies/%2F/ha-all", """{"pattern":"^classic-plain$","apply-to":"queues","priority":1,"definition":{"ha-mode":"all","ha-sync-mode":"automatic"}}""")
            o.put("/api/policies/%2F/limits", """{"pattern":"^classic-ttl$","apply-to":"all","priority":0,"definition":{"max-length":500,"message-ttl":1000}}""")
            o.put("/api/operator-policies/%2F/op-limit", """{"pattern":".*","apply-to":"queues","priority":0,"definition":{"max-length":100000}}""")
            o.put("/api/users/svc", """{"password":"svc-pass","tags":"management,policymaker"}""")
            o.put("/api/users/mon", """{"password":"mon-pass","tags":"monitoring"}""")
            o.put("/api/users/adm", """{"password":"adm-pass","tags":"administrator"}""")
            o.put("/api/permissions/%2F/svc", """{"configure":"^svc-.*","write":".*","read":".*"}""")
            o.put("/api/topic-permissions/%2F/svc", """{"exchange":"ex-e2e","write":"^a.*","read":"^b.*"}""")
        }

        private fun sha256Hash(password: String): String {
            val salt = byteArrayOf(1, 2, 3, 4)
            return Base64.getEncoder().encodeToString(salt + MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray()))
        }
    }

    private fun definitions() = o.get("/api/definitions").obj
    private fun names(arr: JsonArray) = arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    // ---- S-A: the shape of a 3.13 export
    @Test
    fun `S-A the 3_13 export has no queue type key, carries operator policies as parameters and per-vhost exports have no users`() {
        val d = definitions()
        assertTrue(d["rabbitmq_version"]!!.jsonPrimitive.content.startsWith("3.13"))
        assertEquals(
            setOf("rabbit_version", "rabbitmq_version", "product_name", "product_version", "users", "vhosts", "permissions", "topic_permissions", "parameters", "global_parameters", "policies", "queues", "exchanges", "bindings"),
            d.keys,
        )
        val queue = d["queues"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "classic-plain" }
        assertEquals(setOf("name", "vhost", "durable", "auto_delete", "arguments"), queue.keys, "no `type` key on 3.13; the type lives in arguments.x-queue-type")
        val quorum = d["queues"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "quorum-q" }
        assertEquals("quorum", quorum["arguments"]!!.jsonObject["x-queue-type"]!!.jsonPrimitive.content)
        assertTrue(d["parameters"]!!.jsonArray.any { it.jsonObject["component"]!!.jsonPrimitive.content == "operator_policy" }, "operator policies appear under parameters")
        val perVhost = o.get("/api/definitions/%2F").obj
        assertEquals(setOf("rabbit_version", "parameters", "policies", "queues", "exchanges", "bindings"), perVhost.keys)
        val policy = d["policies"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "ha-all" }
        assertEquals("queues", policy["apply-to"]!!.jsonPrimitive.content)
        val user = d["users"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "svc" }
        assertEquals(setOf("name", "password_hash", "hashing_algorithm", "tags", "limits"), user.keys)
        assertEquals("rabbit_password_hashing_sha256", user["hashing_algorithm"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S-A only an administrator may export definitions, even for one vhost`() {
        for (user in listOf("svc" to "svc-pass", "mon" to "mon-pass")) {
            assertEquals(401, o.get("/api/definitions", user.first, user.second).status, user.first)
            assertEquals(401, o.get("/api/definitions/%2F", user.first, user.second).status, user.first)
        }
        assertEquals(200, o.get("/api/definitions", "adm", "adm-pass").status)
    }

    @Test
    fun `S-A federation and shovel URIs would be exported with their password in clear text`() {
        // The federation plugin is off in this image, so use the shovel-independent fact: a stored parameter is exported as is.
        val withParam = o.put("/api/global-parameters/m8-spike-secret", """{"value":{"uri":"amqp://user:s3cr3t-in-export@other"}}""")
        assertTrue(withParam.ok, withParam.toString())
        try {
            assertTrue(o.get("/api/definitions").body.contains("s3cr3t-in-export"), "a secret stored in a parameter appears in the export")
        } finally {
            o.delete("/api/global-parameters/m8-spike-secret")
        }
    }

    // ---- S-B: feeding the 3.13 export to 4.x as is
    @Test
    fun `S-B importing the 3_13 export into 4_x is not atomic, rejects ha policies and silently skips deprecated-feature queues`() {
        val export = definitions().toString()
        val first = n.post("/api/definitions", export)
        assertEquals(400, first.status, first.toString())
        assertTrue(first.reason().contains("are not recognised policy settings") && first.reason().contains("ha-mode"), first.reason())
        // Before the failing policy, the vhosts, users and permissions were already created (the import is not atomic).
        assertTrue(names(n.get("/api/definitions").obj["users"]!!.jsonArray).containsAll(setOf("svc", "mon", "adm")), "users were created before the failure")
        assertTrue(n.get("/api/vhosts").arr.any { it.jsonObject["name"]!!.jsonPrimitive.content == "v1" })

        // Remove the policies 4.x rejects and import again.
        val cleaned = definitions()
        val fixed = JsonObject(
            cleaned + ("policies" to JsonArray(cleaned["policies"]!!.jsonArray.filter { it.jsonObject["name"]!!.jsonPrimitive.content != "ha-all" })) +
                ("parameters" to JsonArray(cleaned["parameters"]!!.jsonArray)),
        )
        val second = n.post("/api/definitions", fixed.toString())
        assertEquals(204, second.status, "the retry is accepted: $second")
        val present = names(n.get("/api/definitions").obj["queues"]!!.jsonArray)
        assertTrue(present.containsAll(setOf("classic-plain", "classic-lazy", "classic-prio", "classic-ttl", "quorum-q", "v1-q")), "kept: $present")
        assertFalse("classic-transient" in present, "a transient (non-durable) queue is silently NOT created, the import still answers 204")
        assertFalse("classic-autodel" in present, "same for a non-durable auto-delete queue")
        assertFalse("classic-locator" in present, "a queue with x-queue-master-locator is silently NOT created either")
        // The 4.x export names the type of every queue (3.13 did not).
        val q = n.get("/api/definitions").obj["queues"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "classic-lazy" }
        assertEquals("classic", q["type"]!!.jsonPrimitive.content)
        assertEquals("lazy", q["arguments"]!!.jsonObject["x-queue-mode"]!!.jsonPrimitive.content, "x-queue-mode lazy is kept on a classic queue, not an error")
    }

    @Test
    fun `S-B validation errors on import answer 400 with one reason, the invalid queue is not created and which valid siblings exist is not deterministic`() {
        val valid = """{"name":"m8iso-ok","vhost":"/","durable":true,"auto_delete":false,"arguments":{}},{"name":"m8iso-after","vhost":"/","durable":true,"auto_delete":false,"arguments":{}}"""
        val invalid = """{"name":"m8iso-quorum-prio","vhost":"/","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum","x-max-priority":5}}"""
        val r = n.post("/api/definitions", """{"queues":[$valid,$invalid]}""")
        assertEquals(400, r.status, r.toString())
        assertTrue(r.reason().contains("x-max-priority"), r.reason())
        assertFalse("m8iso-quorum-prio" in names(n.get("/api/definitions").obj["queues"]!!.jsonArray))
        // Queues are imported in parallel and the first failure stops the rest: valid siblings may or may not exist (measured both ways).
        // What is stable: the same import without the invalid queue is accepted and completes the set, so a rerun is safe.
        assertEquals(204, n.post("/api/definitions", """{"queues":[$valid]}""").status)
        val present = names(n.get("/api/definitions").obj["queues"]!!.jsonArray)
        assertTrue("m8iso-ok" in present && "m8iso-after" in present, present.toString())
    }

    // ---- S-C / S-D: queue declaration matrix on 4.x
    private fun declareOnNew(type: String, args: String = "", durable: Boolean = true, autoDelete: Boolean = false): MgmtApi.Reply {
        val name = "mx-${System.nanoTime()}"
        val arguments = """{"x-queue-type":"$type"${if (args.isEmpty()) "" else ",$args"}}"""
        val reply = n.declareQueue(name, arguments, durable, autoDelete)
        if (reply.ok) n.delete("/api/queues/%2F/$name")
        return reply
    }

    @Test
    fun `S-C quorum queues on 4_x reject x-max-priority, x-queue-mode, x-queue-master-locator, x-queue-version and durable auto-delete`() {
        for (arg in listOf(""""x-max-priority":5""", """"x-queue-mode":"lazy"""", """"x-queue-master-locator":"min-masters"""", """"x-queue-version":2""")) {
            val r = declareOnNew("quorum", arg)
            assertEquals(400, r.status, "$arg -> $r")
            assertTrue(r.reason().contains("invalid arg"), r.reason())
        }
        val auto = declareOnNew("quorum", autoDelete = true)
        assertEquals(400, auto.status)
        assertTrue(auto.reason().contains("invalid property 'auto-delete'"), auto.reason())
    }

    @Test
    fun `S-C quorum queues on 4_x accept TTL, max-length, overflow, dead lettering, single active consumer and the quorum-only arguments`() {
        for (arg in listOf(
            """"x-message-ttl":60000""", """"x-expires":3600000""", """"x-max-length":1000""", """"x-max-length-bytes":1048576""",
            """"x-overflow":"reject-publish"""", """"x-overflow":"drop-head"""", """"x-dead-letter-exchange":"amq.direct","x-dead-letter-routing-key":"k"""",
            """"x-single-active-consumer":true""", """"x-delivery-limit":5""", """"x-quorum-initial-group-size":1""", """"x-dead-letter-strategy":"at-least-once","x-dead-letter-exchange":"amq.direct","x-overflow":"reject-publish"""",
        )) {
            val r = declareOnNew("quorum", arg)
            assertTrue(r.ok, "$arg -> $r")
        }
    }

    @Test
    fun `S-D classic queues on 4_x still accept lazy mode, priorities, single active consumer and the usual limits, and reject the deprecated and quorum-only ones`() {
        for (arg in listOf(""""x-queue-mode":"lazy"""", """"x-max-priority":5""", """"x-max-priority":255""", """"x-single-active-consumer":true""", """"x-message-ttl":60000""", """"x-max-length":1000""", """"x-queue-version":2""")) {
            assertTrue(declareOnNew("classic", arg).ok, arg)
        }
        val locator = declareOnNew("classic", """"x-queue-master-locator":"min-masters"""")
        assertEquals(400, locator.status)
        assertTrue(locator.reason().contains("queue_master_locator"), locator.reason())
        for (arg in listOf(""""x-delivery-limit":5""", """"x-quorum-initial-group-size":1""", """"x-dead-letter-strategy":"at-least-once"""", """"x-queue-version":1""")) {
            assertEquals(400, declareOnNew("classic", arg).status, arg)
        }
        assertTrue(declareOnNew("classic", autoDelete = true).ok, "a durable auto-delete classic queue is still allowed")
    }

    @Test
    fun `S-D transient (non-durable) queues are refused on 4_x for every queue type`() {
        for (type in listOf("classic", "quorum", "stream")) {
            val r = declareOnNew(type, durable = false)
            assertEquals(400, r.status, type)
            assertTrue(r.reason().contains("transient_nonexcl_queues"), r.reason())
        }
    }

    // ---- S-E: policy keys
    private fun policyReply(definition: String, operator: Boolean = false): MgmtApi.Reply {
        val path = if (operator) "/api/operator-policies/%2F/m8p" else "/api/policies/%2F/m8p"
        val r = n.put(path, """{"pattern":"^never-matches$","apply-to":"queues","priority":0,"definition":$definition}""")
        if (r.ok) n.delete(path)
        return r
    }

    @Test
    fun `S-E every ha-star policy key and queue-master-locator are rejected by 4_x, queue-mode is accepted only as a normal policy`() {
        for (def in listOf(
            """{"ha-mode":"all"}""", """{"ha-mode":"exactly","ha-params":2}""", """{"ha-sync-mode":"automatic"}""", """{"ha-sync-batch-size":100}""",
            """{"ha-promote-on-shutdown":"always"}""", """{"ha-promote-on-failure":"when-synced"}""",
        )) {
            val r = policyReply(def)
            assertEquals(400, r.status, def)
            assertTrue(r.reason().contains("are not recognised policy settings"), r.reason())
        }
        val locator = policyReply("""{"queue-master-locator":"client-local"}""")
        assertEquals(400, locator.status)
        assertTrue(locator.reason().contains("queue-master-locator"), locator.reason())
        assertTrue(policyReply("""{"queue-mode":"lazy"}""").ok, "queue-mode lazy as a normal policy is still accepted")
        assertEquals(400, policyReply("""{"queue-mode":"lazy"}""", operator = true).status, "but not as an operator policy")
        for (def in listOf("""{"max-length":100}""", """{"message-ttl":1000}""", """{"dead-letter-exchange":"amq.direct"}""", """{"delivery-limit":5}""", """{"expires":100000}""", """{"overflow":"reject-publish"}""")) {
            assertTrue(policyReply(def).ok, def)
        }
    }

    // ---- S-H: detection and permission facts against 3.13
    @Test
    fun `S-H a 3_13 broker is recognised by overview, answers whoami and permissions like 4_x, and lists deprecated features and their use`() {
        assertTrue(o.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content.startsWith("3.13"))
        assertTrue(n.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content.startsWith("4."))
        for (api in listOf(o, n)) {
            assertEquals(200, api.get("/api/whoami").status)
            assertEquals(200, api.get("/api/permissions/%2F/guest").status)
        }
        assertEquals(401, o.get("/api/permissions/%2F/svc", "svc", "svc-pass").status, "a non-administrator cannot read its own permissions, on 3.13 as on 4.x")
        assertEquals(200, o.get("/api/vhosts", "svc", "svc-pass").status)
        assertEquals(404, o.get("/api/stream/publishers").status, "no stream plugin on this 3.13: the stream calls answer 404")
        val phases3 = o.get("/api/deprecated-features").arr.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["deprecation_phase"]!!.jsonPrimitive.content }
        assertEquals("permitted_by_default", phases3["classic_queue_mirroring"])
        assertEquals("permitted_by_default", phases3["transient_nonexcl_queues"])
        val phases4 = n.get("/api/deprecated-features").arr.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["deprecation_phase"]!!.jsonPrimitive.content }
        assertEquals("removed", phases4["classic_queue_mirroring"])
        assertEquals("denied_by_default", phases4["transient_nonexcl_queues"])
        assertEquals("denied_by_default", phases4["queue_master_locator"])
        assertEquals("denied_by_default", phases4["global_qos"])
        // A 3.13 broker reports what it still uses: here the ha policy.
        val used = o.get("/api/deprecated-features/used").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("classic_queue_mirroring" in used, used.toString())
        assertEquals(emptyList<Any>(), n.get("/api/deprecated-features/used").arr.toList())
    }

    // ---- S-M: users and password hashes
    @Test
    fun `S-M 4_x accepts sha256, sha512 and md5 hashes, rejects bcrypt and unknown algorithms, and an imported hash keeps the old password working`() {
        for ((algo, name) in listOf("SHA-256" to "rabbit_password_hashing_sha256", "SHA-512" to "rabbit_password_hashing_sha512", "MD5" to "rabbit_password_hashing_md5")) {
            val salt = byteArrayOf(1, 2, 3, 4)
            val hash = Base64.getEncoder().encodeToString(salt + MessageDigest.getInstance(algo).digest(salt + "pw-$name".toByteArray()))
            val user = "hu-${name.substringAfterLast('_')}"
            val r = n.put("/api/users/$user", """{"password_hash":"$hash","hashing_algorithm":"$name","tags":"management"}""")
            assertTrue(r.ok, "$name -> $r")
            assertEquals(200, n.get("/api/whoami", user, "pw-$name").status, "the original password logs in with the imported $name hash")
            n.delete("/api/users/$user")
        }
        for (name in listOf("rabbit_password_hashing_bcrypt", "nonsense_algo")) {
            val r = n.put("/api/users/hu-bad", """{"password_hash":"AAAA","hashing_algorithm":"$name","tags":""}""")
            assertEquals(400, r.status, name)
            assertTrue(r.body.contains("unsupported_hashing_algorithm"), r.body)
        }
        assertTrue(o.put("/api/users/hu-old-bad", """{"password_hash":"AAAA","hashing_algorithm":"nonsense_algo","tags":""}""").ok, "3.13 accepted any algorithm name")
        o.delete("/api/users/hu-old-bad")
    }

    @Test
    fun `S-M a user from the 3_13 export logs in on 4_x with its old password, the export still carries the hash, GET users hides it, and a different hash silently overwrites`() {
        val export = definitions()
        val users = JsonArray(export["users"]!!.jsonArray.filter { it.jsonObject["name"]!!.jsonPrimitive.content in setOf("svc", "mon", "adm") })
        assertEquals(204, n.post("/api/definitions", JsonObject(mapOf("users" to users)).toString()).status)
        assertEquals(200, n.get("/api/whoami", "svc", "svc-pass").status, "old password works on 4.x")
        assertEquals(200, n.get("/api/whoami", "mon", "mon-pass").status)
        val onNew = n.get("/api/definitions").obj["users"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "svc" }
        assertTrue("password_hash" in onNew, "4.x /api/definitions still exports the hash (admin), so an idempotency compare is possible")
        assertFalse("password_hash" in n.get("/api/users/svc").obj, "GET /api/users/{name} on 4.x hides the hash")
        assertTrue(n.get("/api/users/svc").obj["has_password"]!!.jsonPrimitive.content.toBoolean())
        // A different hash for an existing user is applied without any warning.
        val other = """{"users":[{"name":"mon","password_hash":"${sha256Hash("other")}","hashing_algorithm":"rabbit_password_hashing_sha256","tags":["monitoring"]}]}"""
        assertEquals(204, n.post("/api/definitions", other).status)
        assertEquals(401, n.get("/api/whoami", "mon", "mon-pass").status, "the old password no longer works: the user was overwritten silently")
        assertEquals(200, n.get("/api/whoami", "mon", "other").status)
    }

    // ---- S-K: vhost default queue type
    @Test
    fun `S-K a vhost default queue type applies to type-less queues on both versions and 4_x exports the type of every queue`() {
        for (api in listOf(o, n)) {
            assertTrue(api.put("/api/vhosts/vq", """{"default_queue_type":"quorum","description":"d","tags":"a,b"}""").ok)
            api.put("/api/permissions/vq/guest", """{"configure":".*","write":".*","read":".*"}""")
            assertTrue(api.declareQueue("plainq", vhost = "vq").ok)
            assertEquals("quorum", api.get("/api/queues/vq/plainq").obj["type"]!!.jsonPrimitive.content)
            api.delete("/api/vhosts/vq")
        }
        assertTrue(n.put("/api/vhosts/vq2", """{"default_queue_type":"quorum"}""").ok)
        n.put("/api/permissions/vq2/guest", """{"configure":".*","write":".*","read":".*"}""")
        assertEquals(204, n.post("/api/definitions/vq2", """{"queues":[{"name":"typeless","vhost":"vq2","durable":true,"auto_delete":false,"arguments":{}}]}""").status)
        assertEquals("quorum", n.get("/api/queues/vq2/typeless").obj["type"]!!.jsonPrimitive.content, "an imported type-less queue takes the vhost default type")
        n.delete("/api/vhosts/vq2")
    }

    // ---- S-L: size and timing
    @Test
    fun `S-L a 3_13 vhost with 1000 queues exports in well under a second and imports into 4_x`() {
        o.put("/api/vhosts/many", "{}")
        o.put("/api/permissions/many/guest", """{"configure":".*","write":".*","read":".*"}""")
        repeat(1000) { o.declareQueue("q-%04d".format(it), vhost = "many") }
        val t0 = System.nanoTime()
        val export = o.get("/api/definitions/many")
        val millis = (System.nanoTime() - t0) / 1_000_000
        assertEquals(200, export.status)
        assertEquals(1000, export.obj["queues"]!!.jsonArray.size)
        assertTrue(export.body.length < 500_000, "export of 1000 queues is ${export.body.length} bytes")
        assertTrue(millis < 5_000, "export took $millis ms")
        n.put("/api/vhosts/many", "{}")
        n.put("/api/permissions/many/guest", """{"configure":".*","write":".*","read":".*"}""")
        assertEquals(204, n.post("/api/definitions/many", export.body).status)
        awaitUntil("1000 queues on 4.x") { n.get("/api/queues/many?page=1&page_size=1&columns=name").obj["total_count"]!!.jsonPrimitive.content.toInt() == 1000 }
    }

    // ---- S-G: what could be measured through the management API
    @Test
    fun `S-G on 4_x quorum and classic queues agree on priority order, per-message TTL and x-max-length drop-head`() {
        val results = mutableMapOf<String, List<String>>()
        for (type in listOf("classic", "quorum")) {
            val q = "g-prio-$type"
            n.declareQueue(q, """{"x-queue-type":"$type"${if (type == "classic") ""","x-max-priority":10""" else ""}}""")
            for (p in listOf(1, 9, 2, 8, 0, 5)) n.publish(q, "p$p", """{"delivery_mode":2,"priority":$p}""")
            awaitUntil("6 messages in $q") { n.get("/api/queues/%2F/$q").obj["messages"]?.jsonPrimitive?.content == "6" }
            results[type] = n.drain(q)
            n.delete("/api/queues/%2F/$q")
        }
        assertEquals(listOf("p9", "p8", "p5", "p2", "p1", "p0"), results["classic"])
        assertEquals(results["classic"], results["quorum"], "observed on 4.4: a quorum queue delivered the same priority order")
        for (type in listOf("classic", "quorum")) {
            val q = "g-ttl-$type"
            n.declareQueue(q, """{"x-queue-type":"$type"}""")
            n.publish(q, "expires-soon", """{"delivery_mode":2,"expiration":"1000"}""")
            n.publish(q, "stays")
            awaitUntil("per-message TTL expired on $type") { n.peek(q).sorted() == listOf("stays") }
            n.delete("/api/queues/%2F/$q")
            val l = "g-len-$type"
            n.declareQueue(l, """{"x-queue-type":"$type","x-max-length":3}""")
            repeat(5) { n.publish(l, "m$it") }
            awaitUntil("max-length trimmed on $type") { n.peek(l).sorted() == listOf("m2", "m3", "m4") }
            n.delete("/api/queues/%2F/$l")
        }
    }

    @Test
    fun `S-G a quorum queue on 4_x always orders by message priority and ranks messages without a priority above priority 3, unlike a classic queue`() {
        fun order(type: String, args: String, messages: List<Pair<String, Int?>>): List<String> {
            val q = "g-order-$type-${System.nanoTime()}"
            n.declareQueue(q, """{"x-queue-type":"$type"$args}""")
            for ((name, priority) in messages) n.publish(q, name, if (priority == null) """{"delivery_mode":2}""" else """{"delivery_mode":2,"priority":$priority}""")
            awaitUntil("${messages.size} messages in $q") { n.get("/api/queues/%2F/$q").obj["messages"]?.jsonPrimitive?.content == messages.size.toString() }
            return n.drain(q).also { n.delete("/api/queues/%2F/$q") }
        }
        val mixed = listOf("a3" to 3, "b3" to 3, "c-none" to null, "d-none" to null)
        assertEquals(listOf("c-none", "d-none", "a3", "b3"), order("quorum", "", mixed), "quorum: no priority sorts ahead of priority 3")
        assertEquals(listOf("a3", "b3", "c-none", "d-none"), order("classic", ",\"x-max-priority\":10", mixed), "classic with x-max-priority: no priority is the lowest")
        assertEquals(listOf("a3", "b3", "c-none", "d-none"), order("classic", "", mixed), "classic without x-max-priority ignores priority: FIFO")
        val spread = listOf("p1" to 1, "p9" to 9, "p2" to 2, "p8" to 8, "p0" to 0, "p5" to 5, "p4" to 4, "p6" to 6)
        assertEquals(listOf("p9", "p8", "p6", "p5", "p4", "p2", "p1", "p0"), order("quorum", "", spread), "quorum orders by priority with no x-max-priority argument")
        assertEquals(listOf("p1", "p9", "p2", "p8", "p0", "p5", "p4", "p6"), order("classic", "", spread), "classic without x-max-priority keeps arrival order")
    }
}
