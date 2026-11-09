package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-03: the queue rules (not the message-loss rule, see ClassicIndexRuleTest). One test per rule id, fires and does-not-fire. */
class QueueRulesTest {
    private val quorumOrdering = Proposal.ConvertQueueType(QueueKind.Quorum, Acknowledgement.OrderingByPriorityMayChange)

    // ---- queue-not-durable ----

    @ParameterizedTest
    @ValueSource(strings = ["classic", "quorum", "stream"])
    fun `queue-not-durable fires for a non-durable queue of any type`(type: String) {
        val kind = when (type) { "classic" -> QueueKind.Classic; "quorum" -> QueueKind.Quorum; else -> QueueKind.Stream }
        val f = analyzeQueues(D.queue("q", kind, durable = false)).only("queue-not-durable", "q")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
        assertEquals(ItemKind.Queue, f.kind)
        assertTrue(f.proposal is Proposal.Manual, "the default is a Manual step (declare it durable or let the application declare it) but is ${f.proposal}")
        assertEquals(listOf<Proposal>(Proposal.MakeDurable), f.alternatives)
        assertContainsText(f.text(), "RabbitMQ 4 does not allow it", "queue-not-durable")
        assertContainsText(f.text(), "import of the raw file would skip it without an error", "queue-not-durable")
    }

    @Test
    fun `queue-not-durable does not fire for a durable queue`() {
        analyzeQueues(D.classicQueue("q"), D.quorumQueue("q2"), D.streamQueue("q3")).assertNone("queue-not-durable")
    }

    // ---- queue-master-locator-argument ----

    @ParameterizedTest
    @ValueSource(strings = ["classic", "quorum"])
    fun `queue-master-locator-argument fires on classic and quorum queues`(type: String) {
        val q = D.queue("q", if (type == "classic") QueueKind.Classic else QueueKind.Quorum, arguments = D.args("x-queue-master-locator" to "min-masters"))
        val f = analyzeQueues(q).only("queue-master-locator-argument", "q")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
        assertEquals(Proposal.RemoveArgument("x-queue-master-locator"), f.proposal)
        assertEquals(emptyList<Proposal>(), f.alternatives)
        assertEquals(listOf("x-queue-master-locator"), f.about)
        assertContainsText(f.text(), "rejected", "queue-master-locator-argument")
        // the scenario "Transient queues and master-locator queues are blockers ..." wants this phrase for both rows
        assertContainsText(f.text(), "RabbitMQ 4 does not allow it", "queue-master-locator-argument")
        assertContainsText(f.text(), "import of the raw file would skip", "queue-master-locator-argument")
        assertContainsText(f.text(), "without an error", "queue-master-locator-argument")
    }

    @Test
    fun `queue-master-locator-argument does not fire without the argument or for x-queue-leader-locator`() {
        analyzeQueues(D.classicQueue("q"), D.classicQueue("q2", arguments = D.args("x-queue-leader-locator" to "balanced"))).assertNone("queue-master-locator-argument")
    }

    // ---- quorum-rejected-setting ----

    @ParameterizedTest
    @ValueSource(strings = ["x-max-priority", "x-queue-mode", "x-queue-version"])
    fun `quorum-rejected-setting fires for each rejected argument whatever its value`(argument: String) {
        for (value in listOf<Any>(5, "lazy", 2)) {
            val f = analyzeQueues(D.classicQueue("q", arguments = D.args(argument to value))).only("quorum-rejected-setting", "q")
            f.assertShape(Severity.Blocker, FindingClass.Incompatibility, AppliesWhen.ConvertedToQuorum, verified = true)
            assertEquals(listOf(argument), f.about)
            assertEquals(Proposal.RemoveArgument(argument), f.proposal)
            assertEquals(emptyList<Proposal>(), f.alternatives)
            assertContainsText(f.text(), "a quorum queue rejects this setting", "quorum-rejected-setting")
        }
    }

    @Test
    fun `quorum-rejected-setting fires for auto-delete with about auto_delete and ClearAutoDelete`() {
        val f = analyzeQueues(D.classicQueue("q", autoDelete = true)).only("quorum-rejected-setting", "q")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, AppliesWhen.ConvertedToQuorum, verified = true)
        assertEquals(listOf("auto_delete"), f.about)
        assertEquals(Proposal.ClearAutoDelete, f.proposal)
    }

    @Test
    fun `quorum-rejected-setting gives one finding per setting`() {
        val r = analyzeQueues(D.classicQueue("q", autoDelete = true, arguments = D.args("x-max-priority" to 3, "x-queue-mode" to "lazy", "x-queue-version" to 2)))
        assertEquals(listOf(listOf("auto_delete"), listOf("x-max-priority"), listOf("x-queue-mode"), listOf("x-queue-version")), r.withRule("quorum-rejected-setting").map { it.about }.sortedBy { it.single() })
    }

    @Test
    fun `quorum-rejected-setting does not fire for settings a quorum queue accepts, nor for quorum and stream queues`() {
        val accepted = D.args("x-message-ttl" to 60000, "x-max-length" to 1000, "x-max-length-bytes" to 1048576, "x-overflow" to "reject-publish", "x-single-active-consumer" to true, "x-dead-letter-exchange" to "dlx")
        analyzeQueues(
            D.classicQueue("accepts", arguments = accepted),
            D.quorumQueue("quorum", arguments = D.args("x-max-priority" to 5)),
            D.queue("stream", QueueKind.Stream, arguments = D.args("x-queue-mode" to "lazy")),
        ).assertNone("quorum-rejected-setting")
    }

    @Test
    fun `x-queue-version 2 gives quorum-rejected-setting but not classic-rejected-argument`() {
        val r = analyzeQueues(D.classicQueue("q", arguments = D.args("x-queue-version" to 2)))
        r.only("quorum-rejected-setting", "q")
        r.assertNone("classic-rejected-argument")
    }

    // ---- classic-rejected-argument ----

    @Test
    fun `classic-rejected-argument fires for x-queue-version 1 and proposes version 2`() {
        val r = analyzeQueues(D.classicQueue("q", arguments = D.args("x-queue-version" to 1)))
        val f = r.only("classic-rejected-argument", "q")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, AppliesWhen.KeptClassic, verified = true)
        assertEquals(listOf("x-queue-version"), f.about)
        assertEquals(Proposal.SetArgument("x-queue-version", ArgumentValue.Whole(2)), f.proposal)
        assertEquals(emptyList<Proposal>(), f.alternatives)
        // version 1 gives both, and the message-loss finding from the file
        r.only("quorum-rejected-setting", "q")
        assertEquals(listOf("x-queue-version"), r.only("classic-index-v1", "q").about)
    }

    @ParameterizedTest
    @ValueSource(strings = ["x-max-in-memory-length", "x-max-in-memory-bytes"])
    fun `classic-rejected-argument fires for the max-in-memory arguments, one per argument`(argument: String) {
        val f = analyzeQueues(D.classicQueue("q", arguments = D.args(argument to 100))).only("classic-rejected-argument", "q")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, AppliesWhen.KeptClassic, verified = true)
        assertEquals(listOf(argument), f.about)
        assertEquals(Proposal.RemoveArgument(argument), f.proposal)
        assertContainsText(f.text(), "a classic queue on 4.x rejects it", "classic-rejected-argument")
    }

    @Test
    fun `classic-rejected-argument gives one finding per argument when both are present`() {
        val r = analyzeQueues(D.classicQueue("q", arguments = D.args("x-max-in-memory-length" to 1, "x-max-in-memory-bytes" to 2, "x-queue-version" to 1)))
        assertEquals(listOf("x-max-in-memory-bytes", "x-max-in-memory-length", "x-queue-version"), r.withRule("classic-rejected-argument").flatMap { it.about }.sorted())
    }

    @Test
    fun `classic-rejected-argument does not fire for other arguments or for queue version 2`() {
        analyzeQueues(D.classicQueue("q", arguments = D.args("x-queue-version" to 2, "x-max-priority" to 5, "x-queue-mode" to "lazy"))).assertNone("classic-rejected-argument")
    }

    // ---- quorum-max-in-memory ----

    @ParameterizedTest
    @ValueSource(strings = ["x-max-in-memory-length", "x-max-in-memory-bytes"])
    fun `quorum-max-in-memory is an unverified warning for the conversion`(argument: String) {
        val f = analyzeQueues(D.classicQueue("q", arguments = D.args(argument to 100))).only("quorum-max-in-memory", "q")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, AppliesWhen.ConvertedToQuorum, verified = false)
        assertEquals(Proposal.None, f.proposal)
        assertContainsText(f.text(), "the effect is not verified", "quorum-max-in-memory")
    }

    @Test
    fun `quorum-max-in-memory does not fire without the arguments`() {
        analyzeQueues(D.classicQueue("q", arguments = D.args("x-max-length" to 5))).assertNone("quorum-max-in-memory")
    }

    // ---- classic-not-replicated ----

    @Test
    fun `classic-not-replicated for a plain durable classic queue proposes quorum with the tick and keeps classic as the alternative`() {
        val f = analyzeQueues(D.classicQueue("q")).only("classic-not-replicated", "q")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, verified = true)
        assertEquals(quorumOrdering, f.proposal)
        assertEquals(listOf<Proposal>(Proposal.KeepClassic), f.alternatives)
        assertContainsText(f.text(), "no longer replicated on 4.x", "classic-not-replicated")
        assertContainsText(f.text(), "quorum queues are the replicated type", "classic-not-replicated")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 5, 255])
    fun `classic-not-replicated with x-max-priority of any value proposes to stay classic and offers quorum as the alternative`(priority: Int) {
        val f = analyzeQueues(D.classicQueue("q", arguments = D.args("x-max-priority" to priority))).only("classic-not-replicated", "q")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, verified = true)
        assertEquals(Proposal.KeepClassic, f.proposal)
        assertEquals(listOf<Proposal>(quorumOrdering), f.alternatives)
        assertContainsText(f.text(), "not replicated", "classic-not-replicated")
        assertContainsText(f.text(), "stay classic", "classic-not-replicated")
    }

    @Test
    fun `classic-not-replicated names the mirroring policy of a mirrored queue and only then`() {
        val ha = D.policy("mirror-pol-xyz", "^m$", D.args("ha-mode" to "all"))
        val r = analyzeQueues(D.classicQueue("m"), D.classicQueue("plain"), policies = listOf(ha))
        assertContainsText(r.only("classic-not-replicated", "m").text(), "mirror-pol-xyz", "the finding of the mirrored queue")
        assertTrue(!r.only("classic-not-replicated", "plain").text().contains("mirror-pol-xyz"), "an unmirrored queue must not name the policy")
    }

    @Test
    fun `classic-not-replicated does not fire for quorum, stream or non-durable queues`() {
        analyzeQueues(D.quorumQueue("a"), D.streamQueue("b"), D.classicQueue("c", durable = false), D.queue("d", QueueKind.Other("x-foo"))).assertNone("classic-not-replicated")
    }

    // ---- classic-to-quorum-ordering ----

    @Test
    fun `classic-to-quorum-ordering fires for a classic queue without priority and says what was measured`() {
        val f = analyzeQueues(D.classicQueue("q")).only("classic-to-quorum-ordering", "q")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, AppliesWhen.ConvertedToQuorum, verified = true)
        assertEquals(Proposal.None, f.proposal)
        assertContainsText(f.text(), "orders messages by priority", "classic-to-quorum-ordering")
        assertContainsText(f.text(), "may be delivered in a different order than in a classic queue", "classic-to-quorum-ordering")
    }

    @Test
    fun `classic-to-quorum-ordering also fires with x-max-priority and then mentions that a kept classic queue is not replicated`() {
        val f = analyzeQueues(D.classicQueue("q", arguments = D.args("x-max-priority" to 5))).only("classic-to-quorum-ordering", "q")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, AppliesWhen.ConvertedToQuorum, verified = true)
        assertContainsText(f.text(), "replicated", "classic-to-quorum-ordering for a priority queue")
    }

    @Test
    fun `classic-to-quorum-ordering does not fire for quorum or stream queues`() {
        analyzeQueues(D.quorumQueue("a"), D.streamQueue("b")).assertNone("classic-to-quorum-ordering")
    }

    // ---- queue-type-unknown ----

    @Test
    fun `queue-type-unknown fires for an unknown type and no classic rule fires for it`() {
        val r = analyzeQueues(D.queue("q", QueueKind.Other("x-foo"), arguments = D.args("x-max-priority" to 3)))
        val f = r.only("queue-type-unknown", "q")
        f.assertShape(Severity.Warning, FindingClass.Incompatibility, verified = false)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertContainsText(f.text(), "does not know this queue type", "queue-type-unknown")
        assertEquals(listOf("queue-type-unknown"), r.findings.filter { it.name == "q" }.map { it.rule })
    }

    @Test
    fun `queue-type-unknown does not fire for classic, quorum or stream`() {
        analyzeQueues(D.classicQueue("a"), D.quorumQueue("b"), D.streamQueue("c")).assertNone("queue-type-unknown")
    }

    // ---- quorum and stream queues are clean ----

    @Test
    fun `a durable quorum queue and a durable stream give no finding that names them`() {
        val r = analyzeQueues(D.quorumQueue("events", arguments = D.args("x-message-ttl" to 5)), D.streamQueue("log"))
        r.assertAnalyzed()
        assertTrue(r.findings.none { it.name == "events" || it.name == "log" }, "got ${r.findings.filter { it.name == "events" || it.name == "log" }.map { it.rule }}")
    }

    // ---- exchange-type-not-builtin ----

    @ParameterizedTest
    @ValueSource(strings = ["x-delayed-message", "x-consistent-hash", "x-random"])
    fun `exchange-type-not-builtin fires for a plugin exchange type`(type: String) {
        val f = analyze(D.document(exchanges = listOf(D.exchange("ex", type)))).only("exchange-type-not-builtin", "ex")
        f.assertShape(Severity.Warning, FindingClass.Incompatibility, verified = false)
        assertEquals(ItemKind.Exchange, f.kind)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertContainsText(f.text(), "may need a plugin that is not enabled on the 4.x broker", "exchange-type-not-builtin")
    }

    @ParameterizedTest
    @ValueSource(strings = ["direct", "fanout", "topic", "headers"])
    fun `exchange-type-not-builtin does not fire for a built-in type`(type: String) {
        analyze(D.document(exchanges = listOf(D.exchange("ex", type)))).assertNone("exchange-type-not-builtin")
    }
}
