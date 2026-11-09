package dev.caerbannog.core.migrate3x

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-03 behaviour rules 1 (order, determinism, unique ids) and 2 (summary). */
class ReportOrderAndSummaryTest {
    private fun threeItems() = D.document(queues = listOf(D.quorumQueue("events")), exchanges = listOf(D.exchange("events.x")), bindings = listOf(D.binding("events.x", "events", "rk")))

    // ---- summary ----

    @Test
    fun `quorum queue, exchange and binding give 3 of 3 compatible and no blocker or warning`() {
        val r = analyze(threeItems())
        assertEquals(3, r.summary.topologyItems)
        assertEquals(3, r.summary.compatibleItems)
        assertEquals(0, r.summary.needDecision)
        assertEquals(0, r.summary.blockers)
        assertEquals(0, r.summary.warnings)
        assertEquals(0, r.summary.messageLoss)
        assertEquals(r.findings.size, r.summary.infos, "only infos (the client checks) may remain: ${r.findings.map { it.rule }}")
    }

    @Test
    fun `topologyItems counts queues, exchanges, bindings, policies and operator policies and nothing else`() {
        val r = analyze(
            D.document(
                queues = listOf(D.quorumQueue("q1"), D.quorumQueue("q2")),
                exchanges = listOf(D.exchange("e")),
                bindings = listOf(D.binding("e", "q1"), D.binding("e", "q2", "k")),
                policies = listOf(D.policy("p", "^x$", D.args("max-length" to 1))),
                operatorPolicies = listOf(D.operatorPolicy("op", "^x$", D.args("max-length" to 1))),
                virtualHosts = listOf(D.virtualHost("/")),
                users = listOf(D.user("u")),
                permissions = listOf(D.permission("/", "u")),
                topicPermissions = listOf(D.topicPermission("/", "u", "e")),
                otherParameters = listOf(D.parameter("shovel", "s")),
            ),
        )
        assertEquals(7, r.summary.topologyItems)
        assertEquals(7, r.summary.compatibleItems, "access items, parameters and global findings do not change the counts")
    }

    @Test
    fun `an item with several blocker and warning findings counts once, conditional findings do not add items`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("q", autoDelete = true, arguments = D.args("x-max-priority" to 5, "x-queue-mode" to "lazy", "x-max-in-memory-length" to 5)))))
        assertEquals(1, r.summary.topologyItems)
        assertEquals(1, r.summary.needDecision)
        assertEquals(0, r.summary.compatibleItems)
    }

    @Test
    fun `info findings do not make an item need a decision`() {
        val r = analyze(D.document(policies = listOf(D.policy("p", "^x$", D.args("queue-mode" to "lazy")))))
        assertEquals(1, r.withRule("policy-queue-mode").size)
        assertEquals(listOf(1, 1, 0), listOf(r.summary.topologyItems, r.summary.compatibleItems, r.summary.needDecision))
    }

    @Test
    fun `only the item that a blocker names needs a decision`() {
        val r = analyze(D.document(queues = listOf(D.quorumQueue("good"), D.quorumQueue("bad", arguments = D.args("x-queue-master-locator" to "x"))), policies = listOf(D.policy("ha", "^x$", D.args("ha-mode" to "all")))))
        assertEquals(listOf(3, 1, 2), listOf(r.summary.topologyItems, r.summary.compatibleItems, r.summary.needDecision))
    }

    @Test
    fun `the same name in another virtual host is another item`() {
        val r = analyze(D.document(queues = listOf(D.quorumQueue("q", vhost = "a", arguments = D.args("x-queue-master-locator" to "x")), D.quorumQueue("q", vhost = "b"))))
        assertEquals(listOf(2, 1, 1), listOf(r.summary.topologyItems, r.summary.compatibleItems, r.summary.needDecision))
    }

    @Test
    fun `severity counts and message loss count match the findings`() {
        val r = analyze(fullFixture())
        assertEquals(r.findings.count { it.severity == Severity.Blocker }, r.summary.blockers)
        assertEquals(r.findings.count { it.severity == Severity.Warning }, r.summary.warnings)
        assertEquals(r.findings.count { it.severity == Severity.Info }, r.summary.infos)
        assertEquals(r.findings.count { it.findingClass == FindingClass.MessageLoss }, r.summary.messageLoss)
        assertTrue(r.summary.messageLoss > 0)
    }

    @Test
    fun `a binding is counted as a topology item`() {
        val r = analyze(D.document(bindings = listOf(D.binding("s", "d", "rk"))))
        assertEquals(listOf(1, 1, 0), listOf(r.summary.topologyItems, r.summary.compatibleItems, r.summary.needDecision))
    }

    // ---- order ----

    private val sortKey = compareBy<Finding>({ it.findingClass != FindingClass.MessageLoss }, { it.vhost != null }, { it.vhost }, { it.kind }, { it.name != null }, { it.name }, { it.rule }, { it.about.joinToString(",") })

    @Test
    fun `findings are in the documented order and message loss comes first`() {
        val r = analyze(fullFixture(), D.liveFacts(deprecatedFeatures = setOf("classic_queue_mirroring", "global_qos")))
        val firstOther = r.findings.indexOfFirst { it.findingClass != FindingClass.MessageLoss }
        assertTrue(firstOther > 0 && r.findings.drop(firstOther).none { it.findingClass == FindingClass.MessageLoss }, "message-loss findings must all come first")
        assertEquals(r.findings.sortedWith(sortKey).map { it.id }, r.findings.map { it.id }, "order by (class message loss first, vhost null first, kind, name null first, rule, about)")
    }

    @Test
    fun `the message-loss finding comes first even when its virtual host sorts last`() {
        val doc = D.document(queues = listOf(D.classicQueue("zeta", vhost = "z")), policies = listOf(D.policy("ha", ".*", D.args("ha-mode" to "all"), vhost = "/")))
        val r = analyze(doc, D.liveFacts(indexVersions = rec(ClassicIndexVersion.V1, "zeta", vhost = "z")))
        assertEquals(FindingClass.MessageLoss, r.findings.first().findingClass)
        assertEquals("zeta", r.findings.first().name)
    }

    // ---- determinism and ids ----

    private fun shuffled(d: DefinitionsDocument, seed: Int): DefinitionsDocument {
        val rnd = Random(seed)
        return d.copy(
            virtualHosts = d.virtualHosts.shuffled(rnd), queues = d.queues.shuffled(rnd), exchanges = d.exchanges.shuffled(rnd), bindings = d.bindings.shuffled(rnd),
            policies = d.policies.shuffled(rnd), operatorPolicies = d.operatorPolicies.shuffled(rnd), users = d.users.shuffled(rnd), permissions = d.permissions.shuffled(rnd),
            topicPermissions = d.topicPermissions.shuffled(rnd), otherParameters = d.otherParameters.shuffled(rnd),
        )
    }

    @Test
    fun `shuffled input lists give an equal report`() {
        val doc = fullFixture()
        val live = D.liveFacts(indexVersions = rec(ClassicIndexVersion.V1, "classic-dlx", "classic-plain"), deprecatedFeatures = setOf("global_qos", "classic_queue_mirroring"))
        val expectedFile = analyze(doc)
        assertTrue(expectedFile.findings.isNotEmpty(), "an empty report proves nothing")
        val expectedLive = analyze(doc, live)
        for (seed in 1..8) {
            assertEquals(expectedFile, analyze(shuffled(doc, seed)), "file report differs for shuffle seed $seed")
            assertEquals(expectedLive, analyze(shuffled(doc, seed), live), "live report differs for shuffle seed $seed")
        }
    }

    @Test
    fun `two runs give an equal report`() {
        val doc = fullFixture()
        assertTrue(analyze(doc).findings.isNotEmpty(), "an empty report proves nothing")
        assertEquals(analyze(doc), analyze(doc))
    }

    @Test
    fun `finding ids are unique inside a report and follow the documented format`() {
        for (r in listOf(analyze(fullFixture()), analyze(fullFixture(), D.liveFacts(deprecatedFeatures = setOf("a", "b"))))) {
            r.assertAnalyzed()
            assertEquals(r.findings.size, r.findings.map { it.id }.toSet().size, "duplicate ids: ${r.findings.groupBy { it.id }.filter { it.value.size > 1 }.keys}")
            r.findings.forEach { f ->
                assertEquals("${f.rule}|${f.vhost ?: "-"}|${f.kind}|${f.name ?: "-"}|${f.about.joinToString(",")}", f.id)
            }
        }
    }

    @Test
    fun `about is sorted for every finding`() {
        val r = analyze(fullFixture())
        r.assertAnalyzed()
        r.findings.forEach { assertEquals(it.about.sorted(), it.about, "about of ${it.id}") }
    }

    // ---- round 1 (F3): unique ids for duplicate items ----

    @Test
    fun `duplicate queues get unique ids, the second and later with the suffix #2 and #3 in report order`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("q"), D.classicQueue("q"), D.classicQueue("q"))))
        assertEquals(r.findings.size, r.findings.map { it.id }.toSet().size, "ids must be unique")
        val ids = r.withRule("classic-not-replicated").map { it.id }
        val base = "classic-not-replicated|/|Queue|q|"
        assertEquals(listOf(base, "$base#2", "$base#3"), ids)
    }

    @Test
    fun `duplicate users get unique ids`() {
        val r = analyze(D.document(users = listOf(D.user("x"), D.user("x"))))
        assertEquals(r.findings.size, r.findings.map { it.id }.toSet().size)
        assertEquals(listOf("access-declare-user|-|User|x|", "access-declare-user|-|User|x|#2"), r.withRule("access-declare-user").map { it.id })
    }

    @Test
    fun `equal duplicates are shuffle-stable and a suffix never appears without a duplicate`() {
        val doc = D.document(queues = listOf(D.classicQueue("q"), D.classicQueue("q"), D.classicQueue("other")), users = listOf(D.user("x"), D.user("x")))
        val expected = analyze(doc)
        for (seed in 1..6) assertEquals(expected, analyze(doc.copy(queues = doc.queues.shuffled(Random(seed)), users = doc.users.shuffled(Random(seed)))))
        assertTrue(expected.findings.filter { it.name == "other" }.none { it.id.contains('#') })
    }

    @Test
    fun `duplicates with different content are ordered by message then proposal text, not by their place in the file`() {
        val a = D.user("x", tags = listOf("aaa"))
        val b = D.user("x", tags = listOf("bbb"))
        val one = analyze(D.document(users = listOf(a, b)))
        val two = analyze(D.document(users = listOf(b, a)))
        assertEquals(one, two)
        val first = one.withRule("access-declare-user").first()
        assertEquals(listOf("aaa"), (first.proposal as Proposal.DeclareUser).tags, "proposal.toString() decides: the smaller one gets the plain id")
        assertTrue(!first.id.contains('#'))
    }
}
