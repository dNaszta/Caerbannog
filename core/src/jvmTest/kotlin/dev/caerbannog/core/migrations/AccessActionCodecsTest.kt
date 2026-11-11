package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** M8-12a: the four access actions in the migration format. Red against the stub codecs (they refuse every access entry). */
class AccessActionCodecsTest {
    private val hash = "SENTINEL-HASH-aGFzaA=="
    private val sha256 = "rabbit_password_hashing_sha256"
    private fun user(name: String = "svc", algorithm: String? = sha256, tags: String = "\"management\"", pass: String? = "\"$hash\"") =
        buildString {
            append("""{ "action": "declareUser", "name": "$name"""")
            if (pass != null) append(""", "passwordHash": $pass""")
            if (algorithm != null) append(""", "hashingAlgorithm": "$algorithm"""")
            append(""", "tags": [$tags] }""")
        }

    private fun file(vararg a: String) = MigrationTestSupport.fileWith(*a)

    // ---- reading ----

    @Test
    fun `a declareVhost with every field and with only a name`() {
        val full = MigrationTestSupport.only(file("""{ "action": "declareVhost", "name": "/orders", "description": "Orders", "tags": ["prod"], "defaultQueueType": "quorum" }"""))
        assertEquals(MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "quorum"), full)
        val bare = MigrationTestSupport.only(file("""{ "action": "declareVhost", "name": "x" }"""))
        assertEquals(MigrationAction.DeclareVhost("x", "", emptyList(), null), bare)
    }

    @Test
    fun `a declareUser keeps its hash as a secret, with algorithm and tags`() {
        val action = MigrationTestSupport.only(file(user())) as? MigrationAction.DeclareUser ?: fail("not a DeclareUser")
        assertEquals("svc", action.name)
        assertEquals(sha256, action.hashingAlgorithm)
        assertEquals(listOf("management"), action.tags)
        assertTrue(action.passwordHash.matches(SecretValue.of(hash)))
    }

    @Test
    fun `setPermissions and setTopicPermissions are read with their patterns`() {
        val p = MigrationTestSupport.only(file("""{ "action": "setPermissions", "vhost": "/orders", "user": "svc", "configure": "^a", "write": "^b", "read": "^c" }"""))
        assertEquals(MigrationAction.SetPermissions("/orders", "svc", "^a", "^b", "^c"), p)
        val t = MigrationTestSupport.only(file("""{ "action": "setTopicPermissions", "vhost": "/orders", "user": "svc", "exchange": "amq.topic", "write": "^w", "read": "^r" }"""))
        assertEquals(MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic", "^w", "^r"), t)
    }

    @Test
    fun `the four actions are broker-level, no old action is`() {
        val access = listOf(
            MigrationAction.DeclareVhost("v"), MigrationAction.DeclareUser("u", SecretValue.of("h"), sha256), MigrationAction.SetPermissions("v", "u"), MigrationAction.SetTopicPermissions("v", "u", "x"),
        )
        assertTrue(access.all { it.isBrokerLevel })
        assertFalse(MigrationAction.DeclareQueue("q").isBrokerLevel)
        assertFalse(MigrationAction.DeletePolicy("p").isBrokerLevel)
        assertEquals("v", access[0].vhost)
        assertEquals("", access[1].vhost)
    }

    @Test
    fun `description and detail of a user name the tags and never the hash`() {
        val a = MigrationAction.DeclareUser("svc", SecretValue.of(hash), sha256, listOf("administrator"))
        assertEquals("Declare user svc", a.description)
        assertEquals("administrator, password hash hidden", a.detail)
        assertFalse(a.toString().contains(hash) || "${a.description}${a.detail}".contains(hash))
        assertEquals("password hash hidden", MigrationAction.DeclareUser("x", SecretValue.of(hash), sha256).detail)
    }

    // ---- the algorithm allow-list and refusals ----

    @Test
    fun `sha256, sha512 and md5 are accepted`() {
        for (algorithm in listOf("rabbit_password_hashing_sha256", "rabbit_password_hashing_sha512", "rabbit_password_hashing_md5")) {
            assertTrue(MigrationTestSupport.only(file(user(algorithm = algorithm))) is MigrationAction.DeclareUser, algorithm)
        }
    }

    @Test
    fun `an unknown or missing algorithm is refused with a fixed text that holds neither the hash nor the algorithm name`() {
        for (raw in listOf(file(user(algorithm = "rabbit_password_hashing_bcrypt")), file(user(algorithm = "a_name_nobody_knows")), file(user(algorithm = null)))) {
            val reason = MigrationTestSupport.reason(raw)
            assertTrue(reason.isNotBlank())
            assertFalse(reason.contains(hash) || reason.contains("bcrypt") || reason.contains("nobody"), reason)
        }
    }

    @Test
    fun `a declareUser without a hash, or with a hash of the wrong type, is refused and no refusal holds the hash`() {
        for (raw in listOf(file(user(pass = null)), file(user(pass = "12345")), file(user(pass = "[\"$hash\"]")), file(user(pass = "{\"x\":\"$hash\"}")))) {
            assertFalse(MigrationTestSupport.reason(raw).contains(hash))
        }
    }

    // ---- writing ----

    @Test
    fun `a round trip keeps every action and the file holds the hash exactly once per user`() {
        val actions = listOf(
            MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "quorum"),
            MigrationAction.DeclareUser("svc", SecretValue.of(hash), sha256, listOf("management")),
            MigrationAction.SetPermissions("/orders", "svc", "^a", "^b", "^c"),
            MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic", "^w", "^r"),
        )
        val text = MigrationParser.exportToJson("0001", "access", actions)
        assertEquals(1, Regex(Regex.escape(hash)).findAll(text).count(), "the file must hold the real hash once")
        val back = MigrationTestSupport.actions(text)
        assertEquals(4, back.size)
        assertEquals(actions[0], back[0]); assertEquals(actions[2], back[2]); assertEquals(actions[3], back[3])
        val u = back[1] as MigrationAction.DeclareUser
        assertTrue(u.passwordHash.matches(SecretValue.of(hash)))
        assertEquals(listOf("svc", sha256, listOf("management")), listOf(u.name, u.hashingAlgorithm, u.tags))
    }

    @Test
    fun `no access action writes a vhost key, the vhost of a permission is written unless it is the default`() {
        val text = MigrationParser.exportToJson(
            "0001", "x",
            listOf(MigrationAction.DeclareVhost("/orders"), MigrationAction.DeclareUser("svc", SecretValue.of(hash), sha256), MigrationAction.SetPermissions("/orders", "svc")),
        )
        val up = Json.parseToJsonElement(text).jsonObject["up"]!!.jsonArray.map { it as JsonObject }
        assertEquals(3, up.size, "the actions were not written: $text")
        assertFalse("vhost" in up[0].keys, "declareVhost writes its name, not a vhost key")
        assertFalse("vhost" in up[1].keys, "declareUser has no vhost")
        assertEquals("/orders", up[2]["vhost"]?.toString()?.trim('"'))
    }

    // ---- no delete ----

    @Test
    fun `there is no delete action for a virtual host, a user or a permission`() {
        for (name in listOf("deleteVhost", "deleteUser", "deletePermissions", "deleteTopicPermissions", "removeUser", "dropVhost")) {
            assertEquals(null, ActionCodecs.find(name), name)
        }
        val names = MigrationAction::class.java.permittedSubclasses.map { it.simpleName }
        assertTrue(names.none { it.startsWith("Delete") && (it.contains("Vhost") || it.contains("User") || it.contains("Permission")) }, "delete actions: $names")
        assertTrue(listOf("DeclareVhost", "DeclareUser", "SetPermissions", "SetTopicPermissions").all { it in names })
    }
}
