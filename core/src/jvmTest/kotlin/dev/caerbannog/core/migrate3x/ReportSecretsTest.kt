package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-03 rule 6: no secret reaches a finding, a proposal, a text, the JSON or a toString. Sentinels only. */
class ReportSecretsTest {
    private val sentinels = listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://", "REDACTED", "old-cluster-secret")

    private fun forms(r: CompatibilityReport): Map<String, String> = mapOf(
        "toString" to r.toString(),
        "finding toString" to r.findings.joinToString("\n") { it.toString() },
        "proposal toString" to r.allProposals().joinToString("\n") { it.toString() },
        "JSON" to CompatibilityReportJson.encode(r),
        "text" to CompatibilityReportText.render(r),
    )

    private fun assertClean(r: CompatibilityReport) {
        assertTrue(r.findings.isNotEmpty(), "the report must not be empty for this test to mean anything")
        for ((form, text) in forms(r)) sentinels.forEach { assertFalse(text.contains(it), "the $form of the report contains \"$it\"") }
    }

    @Test
    fun `the shovel fixture report holds neither the hash nor the password nor the URI, in file and live mode`() {
        val doc = parsedDocument(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER))
        assertClean(analyze(doc))
        assertClean(analyze(doc, D.liveFacts()))
    }

    @Test
    fun `the full fixture report holds no secret`() {
        assertClean(analyze(fullFixture()))
    }

    @Test
    fun `the user svc is listed with name, tags, algorithm and the hash flag only`() {
        val doc = parsedDocument(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER))
        val f = analyze(doc).only("access-declare-user", "svc")
        assertEquals(Proposal.DeclareUser("svc", listOf("management", "policymaker"), "rabbit_password_hashing_sha256", true, null), f.proposal)
        assertContainsText(CompatibilityReportJson.encode(analyze(doc)), "\"hasPasswordHash\"", "the JSON")
    }

    @Test
    fun `a sentinel in a user name tag or algorithm is the only way text from a user appears, and a hash never does`() {
        val doc = D.document(users = listOf(D.user("u", hash = "SENTINEL-HASH-direct", algorithm = "rabbit_password_hashing_bcrypt")))
        assertClean(analyze(doc))
    }

    @Test
    fun `no report type has a property of type SecretValue`() {
        val types: List<Class<*>> = listOf(
            Finding::class, ReportSummary::class, ReportSource::class, CompatibilityReport::class, LiveSourceFacts::class, QueueRef::class,
            Proposal.ConvertQueueType::class, Proposal.RemoveArgument::class, Proposal.SetArgument::class, Proposal.DropPolicyKeys::class,
            Proposal.DeclareVirtualHost::class, Proposal.DeclareUser::class, Proposal.SetPermissions::class, Proposal.SetTopicPermissions::class,
            Proposal.Manual::class, RuleInfo::class,
        ).map { it.java }
        val offenders = types.flatMap { c -> c.declaredFields.filter { SecretValue::class.java.isAssignableFrom(it.type) }.map { "${c.simpleName}.${it.name}" } }
        assertTrue(offenders.isEmpty(), "report types with a SecretValue field: $offenders")
    }

    @Test
    fun `the analyzer source never calls expose`() {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/migrate3x")
        assertTrue(dir.isDirectory, "cannot find ${dir.absolutePath}; run from the core module directory")
        val old = setOf("ArgumentValue.kt", "DefinitionsEntryReader.kt", "DefinitionsFileLoading.kt", "DefinitionsModel.kt", "DefinitionsParser.kt", "DefinitionsReader.kt", "SecretValue.kt", "UnassignedDefinitions.kt", "M803TestStubs.kt")
        val mustExist = listOf("QueueRules.kt", "PolicyRules.kt", "ClassicIndexRule.kt", "AccessRules.kt", "GlobalRules.kt", "ParameterRules.kt", "ReportTexts.kt")
        val missing = mustExist.filter { !File(dir, it).isFile }
        assertTrue(missing.isEmpty(), "the brief names these files and they do not exist yet: $missing")
        val offenders = dir.listFiles { f -> f.extension == "kt" && f.name !in old }.orEmpty().filter { it.readText().contains("expose(") }.map { it.name }
        assertTrue(offenders.isEmpty(), "new analyzer files that call expose(): $offenders")
    }
}
