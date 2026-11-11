package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-12c: the real executor applies the four access actions on the held 4.x connection: bodies, idempotency, compare-before-write, conflicts, fixed texts. */
class AccessExecutorTest {
    private val sha = "rabbit_password_hashing_sha256"
    private val hash = "SENTINEL-HASH-aGFzaA=="
    private fun user(h: String = hash, tags: List<String> = listOf("management", "policymaker")) = MigrationAction.DeclareUser("svc", SecretValue.of(h), sha, tags)

    private fun definitions(userHash: String, tags: String = "management,policymaker", algorithm: String = sha) =
        """{"rabbit_version":"4.1.0","users":[{"name":"svc","password_hash":"$userHash","hashing_algorithm":"$algorithm","tags":"$tags"}],""" +
            """"parameters":[{"component":"shovel","name":"s","vhost":"/","value":{"src-uri":"amqp://u:SHOVEL-PASSWORD-SENTINEL@old:5672"}}]}"""

    private fun existingUserScript(defs: String): (String, String) -> Pair<Int, String>? = { m, p ->
        when {
            m == "GET" && p == "/api/users/svc" -> 200 to """{"name":"svc","tags":["management"]}"""
            m == "GET" && p == "/api/definitions" -> 200 to defs
            m == "PUT" -> 201 to ""
            else -> null
        }
    }

    private fun body(seen: M508a.Seen): JsonObject = Json.parseToJsonElement(seen.body).jsonObject

    // ---- users ----

    @Test
    fun `a missing user is created with the hash, the algorithm always and the tags as a comma separated string`() {
        val rig = AccessRig { m, p -> if (m == "GET" && p == "/api/users/svc") 404 to "" else if (m == "PUT") 201 to "" else null }
        assertEquals(ActionResult.Success, rig.run(user()))
        val put = rig.writes.single { it.path == "/api/users/svc" }
        assertEquals("PUT", put.method)
        val b = body(put)
        assertEquals(hash, b["password_hash"]?.jsonPrimitive?.content)
        assertEquals(sha, b["hashing_algorithm"]?.jsonPrimitive?.content)
        assertEquals("management,policymaker", b["tags"]?.jsonPrimitive?.content)
        assertTrue(rig.calls.none { it.startsWith("DELETE") }, rig.calls.toString())
        assertTrue("/api/definitions" !in rig.calls.map { it.substringAfter(' ') }, "the definitions are read only when the user exists")
    }

    @Test
    fun `an existing user with the same hash and tags is a no-op read through the target definitions, nothing is written`() {
        val rig = AccessRig(existingUserScript(definitions(hash, tags = "management,policymaker")))
        assertEquals(ActionResult.Success, rig.run(user()))
        assertTrue(rig.writes.isEmpty(), "a no-op wrote: ${rig.calls}")
        assertTrue("GET /api/definitions" in rig.calls, "the hash was not compared: ${rig.calls}")
    }

    @Test
    fun `tags are compared as a set`() {
        val rig = AccessRig(existingUserScript(definitions(hash, tags = "policymaker,management")))
        assertEquals(ActionResult.Success, rig.run(user()))
    }

    @Test
    fun `scenario - a different hash is never overwritten, the comparison reads the target definitions first`() {
        val rig = AccessRig(existingUserScript(definitions("OTHER-HASH-xyz")))
        val result = rig.run(user())
        val reason = AccessRig.conflictReason(result)
        assertEquals("The user svc exists with a different password hash", reason)
        assertTrue(rig.writes.isEmpty(), "a differing hash was overwritten: ${rig.calls}")
        assertTrue(rig.calls.indexOf("GET /api/definitions") >= 0)
        assertFalse(reason.contains(hash) || reason.contains("OTHER-HASH"))
    }

    @Test
    fun `different tags are a conflict that names the tags`() {
        val rig = AccessRig(existingUserScript(definitions(hash, tags = "administrator")))
        assertEquals("The user svc exists with different tags", AccessRig.conflictReason(rig.run(user())))
        assertTrue(rig.writes.isEmpty())
    }

    @Test
    fun `the shovel password and every hash of the target definitions appear in no result, call record or log`() {
        val rig = AccessRig(existingUserScript(definitions("OTHER-HASH-xyz")))
        val lines = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { lines += "${record.message} ${record.parameters?.joinToString()} ${record.thrown}" }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val root = Logger.getLogger(""); val old = root.level
        root.level = Level.ALL; root.addHandler(handler)
        val out = System.out; val err = System.err; val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true)); System.setErr(PrintStream(buffer, true))
        val result = try { rig.run(user()) } finally { System.setOut(out); System.setErr(err); root.removeHandler(handler); root.level = old }
        val everything = listOf(result.toString(), lines.joinToString("\n"), buffer.toString())
        everything.forEach { assertFalse(it.contains("SHOVEL-PASSWORD-SENTINEL") || it.contains("OTHER-HASH") || it.contains(hash), "a secret is in: $it") }
        assertTrue(result is ActionResult.Failed, "the premise: the action reached the comparison, got $result")
    }

    // ---- virtual hosts ----

    @Test
    fun `declareVhost of the default virtual host sends no request at all`() {
        val rig = AccessRig { _, _ -> 200 to "{}" }
        assertEquals(ActionResult.Success, rig.run(MigrationAction.DeclareVhost("/", "Production", listOf("prod"), "quorum")))
        assertEquals(emptyList(), rig.calls)
    }

    @Test
    fun `a missing vhost is created with only the settings the action carries`() {
        val rig = AccessRig { m, p -> if (m == "GET" && p == "/api/vhosts/%2Forders") 404 to "" else if (m == "PUT") 201 to "" else null }
        assertEquals(ActionResult.Success, rig.run(MigrationAction.DeclareVhost("/orders", "Orders", emptyList(), null)))
        val put = rig.writes.single { it.path == "/api/vhosts/%2Forders" }
        val b = body(put)
        assertEquals("Orders", b["description"]?.jsonPrimitive?.content)
        assertTrue("tags" !in b.keys && "default_queue_type" !in b.keys, "an uncarried setting was sent: $b")
        val bare = AccessRig { m, p -> if (m == "GET") 404 to "" else if (m == "PUT") 201 to "" else null }
        bare.run(MigrationAction.DeclareVhost("x"))
        assertEquals(emptySet(), body(bare.writes.single()).keys)
    }

    private fun vhostReply(description: String = "Orders", tags: String = "[\"prod\"]", dqt: String = "quorum", viaMetadata: Boolean = true) =
        if (viaMetadata) """{"name":"/orders","metadata":{"description":"$description","tags":$tags,"default_queue_type":"$dqt"}}"""
        else """{"name":"/orders","description":"$description","tags":$tags,"default_queue_type":"$dqt"}"""

    private fun existingVhost(reply: String) = AccessRig { m, p -> if (m == "GET" && p == "/api/vhosts/%2Forders") 200 to reply else if (m == "PUT") 201 to "" else null }

    @Test
    fun `an existing vhost with the same carried settings is a no-op, read from metadata or from the top level`() {
        for (viaMetadata in listOf(true, false)) {
            val rig = existingVhost(vhostReply(viaMetadata = viaMetadata))
            assertEquals(ActionResult.Success, rig.run(MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "quorum")), "metadata=$viaMetadata")
            assertTrue(rig.writes.isEmpty())
        }
    }

    @Test
    fun `a carried setting that differs is a conflict naming the setting, values not echoed, nothing written`() {
        val rig = existingVhost(vhostReply())
        assertEquals("The virtual host /orders exists with a different description", AccessRig.conflictReason(rig.run(MigrationAction.DeclareVhost("/orders", "Other", listOf("prod"), "quorum"))))
        assertEquals("The virtual host /orders exists with different tags", AccessRig.conflictReason(rig.run(MigrationAction.DeclareVhost("/orders", "Orders", listOf("dev"), "quorum"))))
        assertEquals("The virtual host /orders exists with a different default queue type", AccessRig.conflictReason(rig.run(MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "classic"))))
        assertTrue(rig.writes.isEmpty())
    }

    @Test
    fun `settings the action does not carry are not compared`() {
        val rig = existingVhost(vhostReply())
        assertEquals(ActionResult.Success, rig.run(MigrationAction.DeclareVhost("/orders")))
        assertTrue(rig.writes.isEmpty())
    }

    // ---- permissions ----

    private val perm = MigrationAction.SetPermissions("/orders", "svc", "^a", "^b", "^c")

    @Test
    fun `permissions are created when missing, a no-op when equal, a conflict naming the patterns when different`() {
        val missing = AccessRig { m, p -> if (m == "GET") 404 to "" else if (m == "PUT") 201 to "" else null }
        assertEquals(ActionResult.Success, missing.run(perm))
        val put = missing.writes.single { it.path == "/api/permissions/%2Forders/svc" }
        assertEquals(listOf("^a", "^b", "^c"), listOf("configure", "write", "read").map { body(put)[it]?.jsonPrimitive?.content })
        fun existing(c: String) = AccessRig { m, p -> if (m == "GET" && p == "/api/permissions/%2Forders/svc") 200 to """{"user":"svc","vhost":"/orders","configure":"$c","write":"^b","read":"^c"}""" else if (m == "PUT") 201 to "" else null }
        val same = existing("^a"); assertEquals(ActionResult.Success, same.run(perm)); assertTrue(same.writes.isEmpty())
        val other = existing("^zzz")
        assertTrue(AccessRig.conflictReason(other.run(perm)).contains("patterns"))
        assertTrue(other.writes.isEmpty())
    }

    @Test
    fun `topic permissions are compared per exchange`() {
        val t = MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic", "^w", "^r")
        fun existing(write: String, exchange: String = "amq.topic") = AccessRig { m, p ->
            if (m == "GET" && p == "/api/topic-permissions/%2Forders/svc") 200 to """[{"user":"svc","vhost":"/orders","exchange":"$exchange","write":"$write","read":"^r"}]"""
            else if (m == "PUT") 201 to "" else null
        }
        val same = existing("^w"); assertEquals(ActionResult.Success, same.run(t)); assertTrue(same.writes.isEmpty())
        val diff = existing("^other"); assertTrue(AccessRig.conflictReason(diff.run(t)).contains("patterns")); assertTrue(diff.writes.isEmpty())
        val otherExchange = existing("^w", exchange = "other.topic")
        assertEquals(ActionResult.Success, otherExchange.run(t))
        val put = otherExchange.writes.single { it.path == "/api/topic-permissions/%2Forders/svc" }
        assertEquals(listOf("amq.topic", "^w", "^r"), listOf("exchange", "write", "read").map { body(put)[it]?.jsonPrimitive?.content })
    }

    // ---- failures ----

    @Test
    fun `a refused or silent broker gives a fixed text and never the body`() {
        for ((status, text) in listOf(401 to ExecutorReasons.NOT_ALLOWED, 403 to ExecutorReasons.NOT_ALLOWED, 500 to ExecutorReasons.NO_ANSWER, 400 to ExecutorReasons.REFUSED)) {
            val rig = AccessRig { m, _ -> if (m == "GET") 404 to "" else status to """{"reason":"BODY-SECRET amqp://u:pw@h"}""" }
            val failed = rig.run(MigrationAction.SetPermissions("/orders", "svc"))
            assertEquals(ActionResult.Failed(text), failed, "status $status")
        }
        val silent = AccessRig { _, _ -> -1 to "" }
        assertEquals(ActionResult.Failed(ExecutorReasons.NO_ANSWER), silent.run(user()))
    }

    @Test
    fun `the access actions never send a delete`() {
        val rig = AccessRig { m, _ -> if (m == "GET") 404 to "" else 201 to "" }
        listOf(user(), MigrationAction.DeclareVhost("/orders", "d"), perm, MigrationAction.SetTopicPermissions("/orders", "svc", "x")).forEach { rig.run(it) }
        assertTrue(rig.seen.none { it.method == "DELETE" }, rig.calls.toString())
        assertTrue(rig.writes.isNotEmpty(), "nothing was written")
        assertEquals(FailureKind.Conflict, FailureKind.Conflict)
    }
}
