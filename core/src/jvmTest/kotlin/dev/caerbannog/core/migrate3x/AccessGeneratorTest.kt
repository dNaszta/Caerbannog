package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles as F
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-12b: the pure access generator (D1 to D5): what is declared, what is listed as not migrated or left out, the order and determinism. Red against the stub. */
class AccessGeneratorTest {
    private val sha = "rabbit_password_hashing_sha256"
    private fun doc(text: String): DefinitionsDocument = (DefinitionsParser.parse(text) as DefinitionsParseResult.Parsed).document
    private fun gen(text: String, source: String = "legacy.json") = AccessGenerator.generate(doc(text), GenerationRequest(emptySet(), source))
    private fun actions(g: AccessGeneration) = assertNotNull(g.file, "no access file was generated").up

    @Test
    fun `every allow-listed algorithm is declared with its hash and algorithm`() {
        for (algorithm in listOf("rabbit_password_hashing_sha256", "rabbit_password_hashing_sha512", "rabbit_password_hashing_md5")) {
            val g = gen(F.accessFile(vhosts = listOf(F.vhost("/")), users = listOf(F.user("svc", "H-$algorithm", algorithm, listOf("management")))))
            val u = actions(g).filterIsInstance<MigrationAction.DeclareUser>().single()
            assertEquals(listOf("svc", algorithm, listOf("management")), listOf(u.name, u.hashingAlgorithm, u.tags))
            assertTrue(u.passwordHash.matches(SecretValue.of("H-$algorithm")))
        }
    }

    @Test
    fun `the default virtual host is not declared, another one carries only the settings the export has`() {
        val g = gen(F.accessFile(vhosts = listOf(F.vhost("/", description = "Production"), F.vhost("/orders", "Orders", listOf("prod"), "quorum"), F.vhost("bare")), users = listOf(F.user("svc"))))
        val vhosts = actions(g).filterIsInstance<MigrationAction.DeclareVhost>()
        assertEquals(listOf("/orders", "bare"), vhosts.map { it.name }, "vhosts: ${vhosts.map { it.name }}")
        assertEquals(MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "quorum"), vhosts.first { it.name == "/orders" })
        assertEquals(MigrationAction.DeclareVhost("bare", "", emptyList(), null), vhosts.first { it.name == "bare" })
        assertTrue(vhosts.none { it.name == "/" })
    }

    @Test
    fun `guest is not declared and is listed, its permissions are left out once per user and virtual host with topic permissions folded in`() {
        val g = gen(
            F.accessFile(
                vhosts = listOf(F.vhost("/"), F.vhost("/orders")), users = listOf(F.user("guest"), F.user("svc")),
                permissions = listOf(F.permission("/", "guest"), F.permission("/orders", "guest"), F.permission("/orders", "svc")),
                topicPermissions = listOf(F.topicPermission("/orders", "guest")),
            ),
        )
        assertEquals(listOf(NotMigratedUser("guest", NotMigratedReason.Guest)), g.notMigrated)
        assertEquals(setOf(LeftOutPermission("guest", "/"), LeftOutPermission("guest", "/orders")), g.leftOutPermissions.toSet())
        assertEquals(2, g.leftOutPermissions.size, "one row per user and vhost: ${g.leftOutPermissions}")
        assertTrue(actions(g).filterIsInstance<MigrationAction.SetPermissions>().none { it.user == "guest" })
        assertTrue(actions(g).filterIsInstance<MigrationAction.SetTopicPermissions>().none { it.user == "guest" })
        assertTrue(actions(g).none { it is MigrationAction.DeclareUser && it.name == "guest" })
    }

    @Test
    fun `a user with no hash, no algorithm or an unsupported algorithm is listed with its reason and not declared`() {
        val g = gen(
            F.accessFile(
                vhosts = listOf(F.vhost("/")),
                users = listOf(F.user("ldap", hash = null, algorithm = null), F.user("noalg", algorithm = null), F.user("old", algorithm = "rabbit_password_hashing_bcrypt"), F.user("ok")),
            ),
        )
        assertEquals(
            setOf(
                NotMigratedUser("ldap", NotMigratedReason.NoPasswordHash), NotMigratedUser("noalg", NotMigratedReason.NoAlgorithm),
                NotMigratedUser("old", NotMigratedReason.UnsupportedAlgorithm("rabbit_password_hashing_bcrypt")),
            ),
            g.notMigrated.toSet(),
        )
        assertEquals(listOf("ok"), actions(g).filterIsInstance<MigrationAction.DeclareUser>().map { it.name })
    }

    @Test
    fun `permissions are written only for declared users and for declared virtual hosts or the default one`() {
        val g = gen(
            F.accessFile(
                vhosts = listOf(F.vhost("/"), F.vhost("/orders")), users = listOf(F.user("svc"), F.user("old", algorithm = "x_unknown")),
                permissions = listOf(F.permission("/orders", "svc", "^a", "^b", "^c"), F.permission("/", "svc"), F.permission("/ghost", "svc"), F.permission("/orders", "old")),
                topicPermissions = listOf(F.topicPermission("/orders", "svc", "amq.topic", "^w", "^r")),
            ),
        )
        val perms = actions(g).filterIsInstance<MigrationAction.SetPermissions>()
        assertEquals(setOf("/orders" to "svc", "/" to "svc"), perms.map { it.vhost to it.user }.toSet())
        assertEquals(MigrationAction.SetPermissions("/orders", "svc", "^a", "^b", "^c"), perms.first { it.vhost == "/orders" })
        assertEquals(listOf(MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic", "^w", "^r")), actions(g).filterIsInstance<MigrationAction.SetTopicPermissions>())
        assertEquals(listOf(LeftOutPermission("old", "/orders")), g.leftOutPermissions)
    }

    @Test
    fun `the actions come in the order vhosts, users, permissions, topic permissions, each sorted by name, and the file is deterministic`() {
        val text = F.accessFile(
            vhosts = listOf(F.vhost("zeta"), F.vhost("alpha")), users = listOf(F.user("b"), F.user("a")),
            permissions = listOf(F.permission("zeta", "b"), F.permission("alpha", "a")),
            topicPermissions = listOf(F.topicPermission("zeta", "b"), F.topicPermission("alpha", "a")),
        )
        val g = gen(text)
        val a = actions(g)
        assertEquals(listOf("declareVhost", "declareVhost", "declareUser", "declareUser", "setPermissions", "setPermissions", "setTopicPermissions", "setTopicPermissions"), a.map { it.actionName })
        assertEquals(listOf("alpha", "zeta"), a.filterIsInstance<MigrationAction.DeclareVhost>().map { it.name })
        assertEquals(listOf("a", "b"), a.filterIsInstance<MigrationAction.DeclareUser>().map { it.name })
        assertEquals(listOf("alpha" to "a", "zeta" to "b"), a.filterIsInstance<MigrationAction.SetPermissions>().map { it.vhost to it.user })
        assertEquals(g.file?.rawJson, gen(text).file?.rawJson)
    }

    @Test
    fun `the description names the source, the file parses with the real parser and the summary counts what it holds`() {
        val g = gen(
            F.accessFile(
                vhosts = listOf(F.vhost("/"), F.vhost("/orders")), users = listOf(F.user("svc"), F.user("guest")),
                permissions = listOf(F.permission("/orders", "svc"), F.permission("/", "svc")), topicPermissions = listOf(F.topicPermission("/orders", "svc")),
            ),
            source = "legacy-eu.json",
        )
        val file = assertNotNull(g.file, "no access file was generated")
        assertEquals("Access migrated from RabbitMQ 3.x legacy-eu.json", file.description)
        assertTrue(MigrationParser.parse(file.rawJson) is MigrationValidationResult.Valid, "the generated file does not parse")
        assertEquals(AccessSummary(virtualHosts = 1, users = 1, permissions = 2, topicPermissions = 1), g.summary)
    }

    @Test
    fun `nothing to write gives no file, a document of only guest still lists it, a document without access data lists nothing`() {
        val onlyGuest = gen(F.accessFile(vhosts = listOf(F.vhost("/")), users = listOf(F.user("guest")), permissions = listOf(F.permission("/", "guest"))))
        assertNull(onlyGuest.file)
        assertEquals(listOf(NotMigratedUser("guest", NotMigratedReason.Guest)), onlyGuest.notMigrated)
        assertNull(onlyGuest.summary)
        val none = gen(F.accessFile(vhosts = listOf(F.vhost("/"))))
        assertEquals(AccessGeneration(null, emptyList(), emptyList(), null), none)
        assertNotNull(gen(F.accessFile(vhosts = listOf(F.vhost("/"), F.vhost("/orders")))).file, "a virtual host other than / is something to write")
    }

    @Test
    fun `a per-virtual-host export gives no access data and no lists`() {
        val parsed = DefinitionsParser.parse(dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures.read(dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures.VHOST_V1))
        val document = ((parsed as DefinitionsParseResult.NeedsVirtualHost).draft.assignVirtualHost("orders") as VirtualHostAssignment.Assigned).document
        assertEquals(AccessGeneration(null, emptyList(), emptyList(), null), AccessGenerator.generate(document, GenerationRequest(emptySet(), "vhost.json")))
    }

    @Test
    fun `the hash is in the file text and in no other text of the result`() {
        val g = gen(F.accessFile(vhosts = listOf(F.vhost("/orders")), users = listOf(F.user("svc", "SENTINEL-HASH-xyz"), F.user("old", "SENTINEL-HASH-old", "x_unknown"))))
        val file = assertNotNull(g.file, "no access file was generated")
        assertTrue(file.rawJson.contains("SENTINEL-HASH-xyz"), "the file must hold the real hash")
        val texts = listOf(g.toString(), g.notMigrated.toString(), g.leftOutPermissions.toString(), g.summary.toString(), file.up.toString())
        texts.forEach { assertFalse(it.contains("SENTINEL-HASH"), "a hash is in: $it") }
        assertFalse(g.notMigrated.toString().contains("SENTINEL-HASH-old"))
    }
}
