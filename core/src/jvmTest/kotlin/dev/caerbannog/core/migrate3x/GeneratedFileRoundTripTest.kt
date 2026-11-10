package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.migrations.ActionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-05: a generated file is an ordinary migration file: it parses, has no delete, and the runner's dry run lists every action. */
class GeneratedFileRoundTripTest {
    private val neverCalled = object : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = error("a dry run must not send anything")
    }
    private val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), neverCalled)

    private fun fullGeneration(): GenerationResult.Generated {
        val s = started(fullFixture())
        s.acceptEveryDefault().forEach { s.decide(it, ItemDecision.Skip) }
        return s.generated()
    }

    @Test
    fun `every generated file of the full fixture parses to the same actions`() {
        val result = fullGeneration()
        assertEquals(setOf("/", "v1"), result.migrations.map { it.vhost }.toSet(), "the fixture has topology in two virtual hosts")
        for (m in result.migrations) {
            val parsed = MigrationParser.parse(m.file.rawJson, emptySet())
            assertTrue(parsed is MigrationValidationResult.Valid, "the file of ${m.vhost} does not parse: $parsed")
            val file = (parsed as MigrationValidationResult.Valid).migration
            assertEquals(m.file.up, file.up)
            assertEquals(m.file.version, file.version)
            assertEquals(m.file.checksum, MigrationParser.computeChecksum(m.file.rawJson))
            assertTrue(m.file.up.isNotEmpty())
        }
    }

    @Test
    fun `a version that already exists is refused by the parser, so the generator never picks it`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a"))))
        val existing = setOf("0001", "0002", "0003")
        val version = s.generated(GenerationRequest(existing, "legacy-eu")).migrations.single().file.version
        assertFalse(version in existing, "picked an existing version $version")
        assertTrue(Regex("\\d{4}").matches(version), "version is not four digits: $version")
    }

    @Test
    fun `no generated action is a delete, a shovel or a destructive action`() {
        for (action in fullGeneration().allActions()) {
            assertFalse(action.isDestructive, "destructive: ${action.description}")
            assertTrue(action.actionName.startsWith("declare"), "not a declaration: ${action.actionName}")
            assertFalse(action.actionName.contains("shovel", ignoreCase = true))
        }
        assertTrue(fullGeneration().allActions().isNotEmpty())
    }

    @Test
    fun `the dry run lists every action of every file and allows them with permission and enough level`() {
        for (m in fullGeneration().migrations) {
            val dry = runner.dryRun(m.file, true, TopologyControlLevel.DECLARE_AND_EDIT, m.vhost)
            assertEquals(m.file.up, dry.actions.map { it.action })
            assertTrue(dry.canApply, "blocked: ${dry.blockedReason}")
            assertTrue(dry.actions.all { it.allowed })
        }
    }

    @Test
    fun `without configure permission, on read-only level, or on another virtual host the dry run blocks every action`() {
        val m = fullGeneration().migrations.first { it.vhost == "/" }
        for ((label, dry) in listOf(
            "no configure permission" to runner.dryRun(m.file, false, TopologyControlLevel.FULL_CONTROL, "/"),
            "read-only level" to runner.dryRun(m.file, true, TopologyControlLevel.READ_ONLY, "/"),
            "other virtual host" to runner.dryRun(m.file, true, TopologyControlLevel.FULL_CONTROL, "/elsewhere"),
        )) {
            assertFalse(dry.canApply, label)
            assertEquals(m.file.up.size, dry.actions.size, label)
            assertTrue(dry.actions.none { it.allowed }, label)
        }
    }
}
