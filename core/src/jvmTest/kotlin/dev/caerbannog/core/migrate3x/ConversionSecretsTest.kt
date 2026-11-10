package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-05: no secret enters a generated migration, a result, a note or a toString; the new files never read a secret, open a file or build a delete. */
class ConversionSecretsTest {
    private val sentinels = listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://", "REDACTED", "old-cluster-secret", "SecretValue")

    private fun forms(review: ConversionReview, result: GenerationResult.Generated): Map<String, String> = buildMap {
        put("review toString", review.toString())
        put("result toString", result.toString())
        put("notes", result.notes.joinToString("\n") { "${it.text} ${it.item}" })
        put("skipped", result.skipped.joinToString("\n"))
        result.migrations.forEach { put("file ${it.vhost} rawJson", it.file.rawJson); put("file ${it.vhost} toString", it.file.toString()) }
    }

    private fun assertClean(document: DefinitionsDocument) {
        val s = started(document)
        s.acceptEveryDefault().forEach { s.decide(it, ItemDecision.Skip) }
        val result = s.generated()
        assertTrue(result.migrations.isNotEmpty(), "nothing was generated, so a clean result proves nothing")
        for ((form, text) in forms(s.review, result)) sentinels.forEach { assertFalse(text.contains(it), "the $form contains \"$it\"") }
    }

    @Test
    fun `the shovel parameter fixture generates a migration with no hash, no password and no URI`() {
        assertClean(parsedDocument(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)))
    }

    @Test
    fun `the full fixture generates files with no secret`() {
        assertClean(fullFixture())
    }

    @Test
    fun `a hand-made user with a hash next to a queue leaves no trace`() {
        assertClean(D.document(queues = listOf(D.quorumQueue("a")), users = listOf(D.user("svc", hash = "SENTINEL-HASH-direct"))))
    }

    private val sourceDir = File("src/commonMain/kotlin/dev/caerbannog/core/migrate3x")
    private val newFiles get() = listOf("ConversionReview.kt", "MigrationGenerator.kt").map { File(sourceDir, it) }

    @Test
    fun `the new source files exist and never call expose, import a java type or build a delete action`() {
        newFiles.forEach { assertTrue(it.isFile, "${it.path} is missing") }
        for (file in sourceDir.listFiles { f -> f.name.startsWith("Conversion") || f.name.startsWith("Migration") || f.name.startsWith("Generat") || f.name.startsWith("Review") }.orEmpty()) {
            val text = file.readText()
            assertFalse(text.contains("expose("), "${file.name} calls expose(")
            assertFalse(Regex("^import (java|javax)\\.", RegexOption.MULTILINE).containsMatchIn(text), "${file.name} imports a java type")
            assertFalse(Regex("MigrationAction\\.Delete").containsMatchIn(text), "${file.name} builds or names a delete action")
            assertFalse(text.contains("SecretValue"), "${file.name} touches SecretValue")
        }
    }

    @Test
    fun `no public type of the review has a SecretValue, a request or a file member`() {
        val types: List<Class<*>> = listOf(
            ConversionReview::class, ReviewItem::class, ItemDecision.Accept::class, ItemDecision.Replace::class, GenerationRequest::class,
            GenerationResult.Generated::class, GenerationResult.Incomplete::class, GeneratedMigration::class, SkippedItem::class, GenerationNote::class,
        ).map { it.java }
        for (type in types) {
            val bad = type.declaredFields.filter { SecretValue::class.java.isAssignableFrom(it.type) || it.type.name.startsWith("java.io") || it.type.name.startsWith("java.net") }
            assertEquals(emptyList<String>(), bad.map { it.name }, "${type.simpleName} has a secret, file or network member")
        }
    }
}
