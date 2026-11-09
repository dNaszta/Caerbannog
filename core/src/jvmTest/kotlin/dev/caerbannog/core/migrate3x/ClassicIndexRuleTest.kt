package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-03 `classic-index-v1` (S-N, P16): the message-loss matrix, live and file-only, plus the proposals. */
class ClassicIndexRuleTest {
    private val rule = "classic-index-v1"
    private fun live(index: Map<QueueRef, ClassicIndexVersion> = emptyMap()) = D.liveFacts(indexVersions = index)

    private fun Finding.assertMessageLoss(severity: Severity, evidence: EvidenceSource) =
        assertShape(severity, FindingClass.MessageLoss, AppliesWhen.Always, verified = true, evidence = evidence, defaultDecision = DefaultDecision.Accept)

    // ---- live facts ----

    @Test
    fun `live V1 is a blocker with the required message and live evidence`() {
        val f = analyzeQueues(D.classicQueue("orders"), live = live(rec(ClassicIndexVersion.V1, "orders"))).only(rule, "orders")
        f.assertMessageLoss(Severity.Blocker, EvidenceSource.LiveQueueStats)
        assertEquals(listOf(ItemKind.Queue, emptyList<String>()), listOf(f.kind, f.about))
        assertContainsText(f.message, "uses the old classic queue index (version 1)", "message")
        assertContainsText(f.message, "RabbitMQ 4.3 and later cannot read it", "message")
        assertContainsText(f.message, "an in-place upgrade would silently empty this queue (messages lost, no error in the management API)", "message")
        assertContainsText(f.why, "broker log", "why")
        assertContainsText(f.why, "cannot detect", "why")
    }

    @Test
    fun `live V2 gives no finding, even when the file says version 1`() {
        analyzeQueues(D.classicQueue("events", arguments = D.args("x-queue-version" to 1)), live = live(rec(ClassicIndexVersion.V2, "events"))).assertNone(rule)
        analyzeQueues(D.classicQueue("events"), live = live(rec(ClassicIndexVersion.V2, "events"))).assertNone(rule)
    }

    @Test
    fun `live Unknown is a blocker that says the version could not be read and is treated as version 1`() {
        val f = analyzeQueues(D.classicQueue("orders"), live = live(rec(ClassicIndexVersion.Unknown, "orders"))).only(rule, "orders")
        f.assertMessageLoss(Severity.Blocker, EvidenceSource.LiveQueueStats)
        assertContainsText(f.message, "the index version could not be read; treated as version 1", "message")
    }

    @Test
    fun `a classic queue missing from the live map counts as Unknown`() {
        val f = analyzeQueues(D.classicQueue("orders"), live = live(emptyMap())).only(rule, "orders")
        f.assertMessageLoss(Severity.Blocker, EvidenceSource.LiveQueueStats)
        assertContainsText(f.message, "could not be read", "message")
        // same name in another vhost does not count as the entry
        val g = analyzeQueues(D.classicQueue("orders"), live = live(rec(ClassicIndexVersion.V2, "orders", vhost = "other"))).only(rule, "orders")
        assertContainsText(g.message, "could not be read", "message")
    }

    // ---- file only ----

    @Test
    fun `file with x-queue-version 1 is a blocker that says the file states it`() {
        val f = analyzeQueues(D.classicQueue("orders", arguments = D.args("x-queue-version" to 1))).only(rule, "orders")
        f.assertMessageLoss(Severity.Blocker, EvidenceSource.Definitions)
        assertEquals(listOf("x-queue-version"), f.about)
        assertContainsText(f.message, "RabbitMQ 4.3 and later cannot read it", "message")
    }

    @Test
    fun `file without x-queue-version is a warning that says the file does not carry the index version`() {
        val f = analyzeQueues(D.classicQueue("orders")).only(rule, "orders")
        f.assertMessageLoss(Severity.Warning, EvidenceSource.Definitions)
        assertEquals(emptyList<String>(), f.about)
        assertContainsText(f.text(), "the file does not carry the index version", "message")
    }

    @Test
    fun `file with x-queue-version 2 gives no finding`() {
        analyzeQueues(D.classicQueue("orders", arguments = D.args("x-queue-version" to 2))).assertNone(rule)
    }

    // ---- which queues ----

    @Test
    fun `non-durable classic, quorum and stream queues are skipped, in both modes`() {
        val queues = arrayOf(D.classicQueue("transient", durable = false), D.quorumQueue("qq"), D.streamQueue("ss"), D.queue("oo", QueueKind.Other("x-foo")))
        analyzeQueues(*queues).assertNone(rule)
        val index = rec(ClassicIndexVersion.V1, "transient", "qq", "ss", "oo")
        analyzeQueues(*queues, live = live(index)).assertNone(rule)
    }

    // ---- source version ----

    @ParameterizedTest
    @ValueSource(strings = ["4.0.0", "4.4.0", "4", "10.1.2", "04.1"])
    fun `a 4-or-later source version skips the rule in file and live mode`(version: String) {
        analyzeQueues(D.classicQueue("orders"), sourceVersion = version).assertNone(rule)
        analyzeQueues(D.classicQueue("orders", arguments = D.args("x-queue-version" to 1)), sourceVersion = version).assertNone(rule)
        analyzeQueues(D.classicQueue("orders"), live = live(rec(ClassicIndexVersion.V1, "orders")), sourceVersion = version).assertNone(rule)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.13.7", "3.13", "3", "3.9.29"])
    fun `a 3-x source version does not skip the rule`(version: String) {
        analyzeQueues(D.classicQueue("orders"), sourceVersion = version).only(rule, "orders")
    }

    @Test
    fun `an absent or unparseable source version does not skip the rule`() {
        analyzeQueues(D.classicQueue("orders"), sourceVersion = null).only(rule, "orders")
        analyzeQueues(D.classicQueue("orders"), sourceVersion = "abc").only(rule, "orders")
        analyzeQueues(D.classicQueue("orders"), sourceVersion = "").only(rule, "orders")
    }

    @Test
    fun `no other rule looks at the source version`() {
        val doc = { v: String? -> D.document(queues = listOf(D.classicQueue("q", arguments = D.args("x-max-priority" to 1))), sourceVersion = v) }
        val rulesAt = { v: String? -> analyze(doc(v)).findings.map { it.rule }.filter { it != rule }.sorted() }
        assertTrue(rulesAt("3.13.7").isNotEmpty(), "an empty report proves nothing")
        assertEquals(rulesAt("3.13.7"), rulesAt("4.4.0"))
    }

    // ---- proposals ----

    private fun everyMessageLossFinding(): List<Finding> =
        analyzeQueues(
            D.classicQueue("v1", arguments = D.args("x-queue-version" to 1)),
            D.classicQueue("file"),
            D.classicQueue("liveV1"),
            D.classicQueue("liveUnknown"),
            live = null,
        ).findings.filter { it.findingClass == FindingClass.MessageLoss } +
            analyzeQueues(D.classicQueue("liveV1"), D.classicQueue("liveUnknown"), live = live(rec(ClassicIndexVersion.V1, "liveV1"))).findings.filter { it.findingClass == FindingClass.MessageLoss }

    @Test
    fun `every message-loss finding has exactly one recommended manual proposal and two secondary manual alternatives`() {
        val findings = everyMessageLossFinding()
        assertEquals(6, findings.size, "4 file findings + 2 live findings but got ${findings.map { it.name }}")
        for (f in findings) {
            val p = f.proposal
            assertTrue(p is Proposal.Manual && p.recommended, "default proposal of ${f.name} must be a recommended Manual but is $p")
            assertEquals(1, f.allProposals().count { it is Proposal.Manual && it.recommended }, "exactly one recommended Manual for ${f.name}")
            assertEquals(2, f.alternatives.size)
            f.alternatives.forEach { assertTrue(it is Proposal.Manual && !it.recommended, "alternative must be a secondary Manual but is $it") }
        }
    }

    @Test
    fun `the recommended proposal is the new broker with a Shovel and the alternatives are the policy fix and the upgrade through 4_0 to 4_2`() {
        val f = analyzeQueues(D.classicQueue("orders")).only(rule, "orders")
        val recommended = f.proposal as Proposal.Manual
        val rec = "${recommended.title}\n${recommended.text}"
        assertContainsText(rec, "new 4.x broker", "recommended proposal")
        assertContainsText(rec, "topology", "recommended proposal")
        assertContainsText(rec, "Shovel", "recommended proposal")
        val alt = f.alternatives.map { it as Proposal.Manual }.map { "${it.title}\n${it.text}" }
        assertTrue(alt.any { it.contains("queue-version") && it.contains("2") && it.contains("restart", ignoreCase = true) }, "an alternative must set the policy queue-version to 2 and restart: $alt")
        assertTrue(alt.any { it.contains("4.0") && it.contains("4.2") }, "an alternative must say to upgrade through 4.0, 4.1 or 4.2 first: $alt")
    }

    @Test
    fun `no proposal text offers an in-place upgrade as a safe step`() {
        assertTrue(everyMessageLossFinding().isNotEmpty(), "no message-loss finding to scan")
        for (f in everyMessageLossFinding()) {
            for (text in f.manualTexts()) {
                val mentions = text.contains("in place", ignoreCase = true) || text.contains("in-place", ignoreCase = true)
                if (mentions) assertTrue(text.contains("do not", ignoreCase = true) || text.contains("never", ignoreCase = true) || text.contains("not ", ignoreCase = true), "an in-place upgrade may only appear in a warning but: $text")
            }
        }
    }
}
