package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles as F
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-12b: the access file inside "Generate": first, with the first free number, topology after it; the stale note is gone; the lists and the summary are carried; no hash outside the file. */
class AccessGenerationFlowTest {
    private fun generate(text: String, existing: Set<String> = emptySet()): GenerationResult.Generated {
        val review = ConversionReviewsRef.core(text)
        return assertIs<GenerationResult.Generated>(review.generate(GenerationRequest(existing, "legacy.json")), "the review was not complete")
    }

    private val withAccess = F.accessFile(
        vhosts = listOf(F.vhost("/"), F.vhost("/orders")), users = listOf(F.user("svc", "SENTINEL-HASH-xyz"), F.user("guest")),
        permissions = listOf(F.permission("/orders", "svc"), F.permission("/", "guest")),
        queues = listOf(F.queue("orders", type = "quorum", vhost = "/orders"), F.queue("jobs", type = "quorum", vhost = "/")),
    )

    @Test
    fun `the access migration is the first file with the first free number and the topology files follow`() {
        val g = generate(withAccess)
        assertEquals(listOf(true, false, false), g.migrations.map { it.access }, "files: ${g.migrations.map { it.file.version to it.vhost }}")
        assertEquals(listOf("0001", "0002", "0003"), g.migrations.map { it.file.version })
        assertEquals("", g.migrations.first().vhost)
        assertTrue(g.migrations.first().file.up.any { it is MigrationAction.DeclareUser })
        assertTrue(g.migrations.drop(1).all { f -> f.file.up.none { it.isBrokerLevel } })
    }

    @Test
    fun `existing versions in the folder shift the numbers, the access file still comes first`() {
        val g = generate(withAccess, existing = setOf("0005"))
        assertEquals(listOf("0006", "0007", "0008"), g.migrations.map { it.file.version })
        assertTrue(g.migrations.first().access)
    }

    @Test
    fun `a document without access data writes the topology files as before, with no access file`() {
        val g = generate(F.file(queues = listOf(F.queue("jobs", type = "quorum"))))
        assertEquals(listOf(false), g.migrations.map { it.access })
        assertEquals(listOf("0001"), g.migrations.map { it.file.version })
        assertNull(g.access)
        assertEquals(emptyList(), g.notMigrated)
    }

    @Test
    fun `the old per-finding access note is gone and the default virtual host note is added`() {
        val g = generate(withAccess)
        val texts = g.notes.map { it.text }
        assertTrue(texts.none { it.contains("Handled by the access migration") }, "the stale note is still there: $texts")
        assertEquals("The default virtual host / already exists on 4.x and is not changed", MigrateFromThreeTexts.DEFAULT_VHOST_NOTE)
        assertTrue(MigrateFromThreeTexts.DEFAULT_VHOST_NOTE in texts, "notes: $texts")
    }

    @Test
    fun `the lists and the summary are carried on the result`() {
        val g = generate(withAccess)
        assertEquals(listOf(NotMigratedUser("guest", NotMigratedReason.Guest)), g.notMigrated)
        assertEquals(listOf(LeftOutPermission("guest", "/")), g.leftOutPermissions)
        assertEquals(AccessSummary(virtualHosts = 1, users = 1, permissions = 1, topicPermissions = 0), g.access)
    }

    @Test
    fun `the hash is in the access file text only`() {
        val g = generate(withAccess)
        assertTrue(g.migrations.first().file.rawJson.contains("SENTINEL-HASH-xyz"))
        assertTrue(g.migrations.drop(1).none { it.file.rawJson.contains("SENTINEL") })
        listOf(g.toString(), g.notes.toString(), g.notMigrated.toString(), g.leftOutPermissions.toString(), g.skipped.toString(), g.access.toString()).forEach {
            assertFalse(it.contains("SENTINEL-HASH"), "a hash is in: $it")
        }
    }

    @Test
    fun `no generated action deletes a virtual host, a user or a permission`() {
        val all = generate(withAccess).migrations.flatMap { it.file.up }
        assertTrue(all.none { it.actionName.startsWith("delete") && (it.actionName.contains("Vhost") || it.actionName.contains("User") || it.actionName.contains("Permission")) })
        assertNotNull(all.firstOrNull { it is MigrationAction.DeclareUser }, "the premise: the access actions were generated")
    }
}
