package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M8-03 golden check on the recorded 3.13.7 export (`fixtures/m8-01/definitions-3.13-full.json`). The expected list is
 * `fixtures/m8-03/full-fixture-golden.txt`, derived by the test developer from the brief's rule table and reviewed by the architect:
 * one line per finding `rule|kind|vhost|name|about` in report order ("-" for none). The list is the contract, edit it only with the architect.
 */
class FullFixtureGoldenTest {
    private val report = analyze(fullFixture())

    private fun golden(): List<String> =
        DefinitionsFixtures.readM803("full-fixture-golden.txt").lines().filter { it.isNotBlank() }

    private fun line(f: Finding) = listOf(f.rule, f.kind.name, f.vhost ?: "-", f.name ?: "-", f.about.joinToString(",").ifEmpty { "-" }).joinToString("|")

    @Test
    fun `the findings of the full fixture are exactly the golden list, in order`() {
        assertEquals(golden().joinToString("\n"), report.findings.joinToString("\n") { line(it) })
    }

    @Test
    fun `summary of the full fixture`() {
        assertEquals(
            ReportSummary(topologyItems = 24, compatibleItems = 10, needDecision = 14, blockers = 9, warnings = 36, infos = 17, messageLoss = 10),
            report.summary,
        )
        assertEquals(62, report.findings.size)
    }

    @Test
    fun `source of the full fixture`() {
        assertEquals(listOf<Any?>("3.13.7", false, null), listOf(report.source.sourceVersion, report.source.live, report.source.singleVirtualHost))
    }

    // ---- spot checks required by the brief ----

    private fun rules(name: String, kind: ItemKind? = null) = report.findings.filter { it.name == name && (kind == null || it.kind == kind) }.map { it.rule }.toSet()

    @Test
    fun `ha-all and ha-exactly give ha-policy-key, ha-exactly also queue-master-locator-policy-key`() {
        assertTrue("ha-policy-key" in rules("ha-all"))
        assertTrue(rules("ha-exactly").containsAll(setOf("ha-policy-key", "queue-master-locator-policy-key")), "got ${rules("ha-exactly")}")
        assertTrue("queue-master-locator-policy-key" !in rules("ha-all"))
        assertEquals(listOf("ha-mode", "ha-promote-on-shutdown", "ha-sync-mode"), report.only("ha-policy-key", "ha-all").about)
        assertEquals(listOf("ha-mode", "ha-params"), report.only("ha-policy-key", "ha-exactly").about)
    }

    @Test
    fun `the limits policy and the op-limit operator policy give no finding`() {
        report.assertAnalyzed()
        assertEquals(emptySet<String>(), rules("limits"))
        assertEquals(emptySet<String>(), rules("op-limit"))
    }

    @Test
    fun `single findings of the spot-check items`() {
        assertEquals(emptySet<String>(), rules("quorum-q"))
        assertTrue("queue-master-locator-argument" in rules("classic-locator"))
        assertEquals(setOf("not-covered-parameter"), rules("up1"))
        assertEquals(2, report.withRule("not-covered-global-parameter").size)
        assertEquals(setOf("user-guest", "user-administrator-tag"), rules("guest", ItemKind.User))
        report.assertNone("exchange-type-not-builtin") // ex-delayed is a plain topic exchange in the fixture
    }

    @Test
    fun `the non-durable queues are blockers and the auto-delete queue also blocks the conversion`() {
        assertEquals(setOf("queue-not-durable", "classic-to-quorum-ordering"), rules("classic-transient"))
        assertEquals(setOf("queue-not-durable", "classic-to-quorum-ordering", "quorum-rejected-setting"), rules("classic-autodel"))
    }
}
