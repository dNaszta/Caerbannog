package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-03 rule 7: the catalog guard. The id list below is the brief's rule table. */
class RuleCatalogTest {
    private val tableIds = listOf(
        "ha-policy-key", "queue-master-locator-policy-key", "operator-policy-key-rejected", "policy-queue-mode", "policy-max-in-memory",
        "not-covered-policy-key", "policy-pattern-unreadable", "queue-not-durable", "queue-master-locator-argument", "quorum-rejected-setting",
        "classic-rejected-argument", "quorum-max-in-memory", "classic-not-replicated", "classic-to-quorum-ordering", "queue-type-unknown",
        "classic-index-v1", "exchange-type-not-builtin", "not-covered-parameter", "not-covered-global-parameter", "mirroring-in-use",
        "deprecated-feature-in-use", "clients-global-qos", "clients-transient-queues", "clients-amqp-address-v1", "classic-to-quorum-unmeasured",
        "access-declare-vhost", "access-declare-user", "access-set-permissions", "access-set-topic-permissions", "user-guest",
        "user-administrator-tag", "user-hash-without-algorithm", "user-hash-algorithm-unsupported", "user-no-password-hash", "access-not-in-file",
    )
    private val unverified = setOf("policy-max-in-memory", "policy-pattern-unreadable", "quorum-max-in-memory", "queue-type-unknown", "exchange-type-not-builtin", "classic-to-quorum-unmeasured")

    @Test
    fun `the catalog has unique ids and every id of the rule table`() {
        val ids = RuleCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate catalog ids")
        assertEquals(emptyList<String>(), tableIds - ids.toSet(), "rule table ids missing from RuleCatalog")
        assertEquals(emptyList<String>(), ids - tableIds.toSet(), "RuleCatalog ids that are not in the rule table (add them to the brief first)")
    }

    @Test
    fun `a verified rule names its evidence and an unverified rule names none`() {
        for (info in RuleCatalog.all) {
            if (info.verified) assertTrue(info.evidence != "none" && info.evidence.isNotBlank(), "verified rule ${info.id} must name its spike section and fixture")
            else assertEquals("none", info.evidence, "unverified rule ${info.id} must not name a fixture")
        }
    }

    @Test
    fun `the unverified rules are exactly the ones the brief lists`() {
        val flagged = RuleCatalog.all.filter { !it.verified }.map { it.id }.toSet() - "deprecated-feature-in-use"
        assertEquals(unverified, flagged)
    }

    @Test
    fun `verified rules point at the spike sections the brief names`() {
        val byId = RuleCatalog.all.associateBy { it.id }
        val expectedSection = mapOf("ha-policy-key" to "S-E", "queue-not-durable" to "S-B", "classic-index-v1" to "S-N", "clients-global-qos" to "S-I", "access-declare-user" to "S-M", "classic-to-quorum-ordering" to "S-G")
        expectedSection.forEach { (id, section) -> assertTrue(byId[id]?.evidence?.contains(section) == true, "evidence of $id should name $section but is ${byId[id]?.evidence}") }
    }

    /** A kitchen-sink document and live facts that fire every rule but `access-not-in-file`, to run the catalog guard on real findings. */
    private fun kitchenSink(): List<CompatibilityReport> {
        val doc = D.document(
            queues = listOf(
                D.classicQueue("c1", arguments = D.args("x-max-priority" to 5, "x-queue-mode" to "lazy", "x-queue-version" to 1, "x-max-in-memory-length" to 5, "x-queue-master-locator" to "x")),
                D.classicQueue("c2", durable = false, autoDelete = true),
                D.queue("o", QueueKind.Other("x-foo")),
                D.quorumQueue("q"),
            ),
            exchanges = listOf(D.exchange("ex", "x-delayed-message")),
            policies = listOf(
                D.policy("p-ha", "^c", D.args("ha-mode" to "all", "queue-master-locator" to "x", "queue-mode" to "lazy", "max-in-memory-length" to 5, "federation-upstream-set" to "all")),
                D.policy("p-bad", "(", D.args("max-length" to 1)),
            ),
            operatorPolicies = listOf(D.operatorPolicy("op", "^c", D.args("queue-mode" to "lazy"))),
            virtualHosts = listOf(D.virtualHost("/")),
            users = listOf(
                D.user("guest", tags = listOf("administrator")), D.user("noalg", algorithm = null), D.user("bcrypt", algorithm = "rabbit_password_hashing_bcrypt"),
                D.user("nohash", hash = null, algorithm = null), D.user("ok"),
            ),
            permissions = listOf(D.permission("/", "ok")),
            topicPermissions = listOf(D.topicPermission("/", "ok", "ex")),
            otherParameters = listOf(D.parameter("shovel", "s"), D.parameter("global", "cluster_name", vhost = null)),
        )
        val live = D.liveFacts(indexVersions = rec(ClassicIndexVersion.V1, "c1"), deprecatedFeatures = setOf("classic_queue_mirroring", "global_qos", "weird_feature"))
        val single = D.document(queues = listOf(D.classicQueue("x", vhost = "orders")), origin = DefinitionsOrigin.SingleVirtualHost("orders"))
        return listOf(analyze(doc, live), analyze(doc), analyze(single))
    }

    @Test
    fun `every rule of the table is emitted at least once by the kitchen sink and every emitted rule is in the catalog`() {
        val reports = kitchenSink()
        val emitted = reports.flatMap { r -> r.findings.map { it.rule } }.toSet()
        assertEquals(emptyList<String>(), tableIds - emitted, "rules never emitted by the kitchen-sink input")
        val catalog = RuleCatalog.all.associateBy { it.id }
        for (f in reports.flatMap { it.findings }) {
            val info = catalog[f.rule]
            assertTrue(info != null, "emitted rule ${f.rule} is not in RuleCatalog")
            if (f.rule != "deprecated-feature-in-use") assertEquals(info!!.verified, f.verified, "Finding.verified of ${f.rule} must come from the catalog")
        }
    }
}
