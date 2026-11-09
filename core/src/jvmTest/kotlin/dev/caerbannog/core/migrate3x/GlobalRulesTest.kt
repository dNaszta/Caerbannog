package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-03: parameter rules, live-fact rules, and the once-per-report global rules. */
class GlobalRulesTest {
    // ---- not-covered-parameter / not-covered-global-parameter ----

    @Test
    fun `not-covered-parameter fires for a parameter with a vhost and lists component and name only`() {
        val r = analyze(D.document(otherParameters = listOf(D.parameter("shovel", "move-orders"), D.parameter("federation-upstream", "up1", vhost = "v1"))))
        val shovel = r.only("not-covered-parameter", "move-orders")
        shovel.assertShape(Severity.Warning, FindingClass.NotCovered, verified = true)
        assertEquals(listOf(ItemKind.Parameter, "/", listOf("shovel")), listOf(shovel.kind, shovel.vhost, shovel.about))
        assertTrue(shovel.proposal is Proposal.Manual, "proposal is ${shovel.proposal}")
        assertContainsText(shovel.text(), "not migrated", "not-covered-parameter")
        assertEquals("v1", r.only("not-covered-parameter", "up1").vhost)
        r.assertNone("not-covered-global-parameter")
    }

    @Test
    fun `not-covered-global-parameter fires for a parameter without a vhost, once per parameter`() {
        val r = analyze(D.document(otherParameters = listOf(D.parameter("global", "cluster_name", vhost = null), D.parameter("global", "internal_cluster_id", vhost = null))))
        val found = r.withRule("not-covered-global-parameter")
        assertEquals(2, found.size, "findings: ${r.findings.map { it.rule }}")
        found.forEach {
            it.assertShape(Severity.Info, FindingClass.NotCovered, verified = true)
            assertEquals(ItemKind.Global, it.kind)
            assertEquals(Proposal.None, it.proposal)
            assertContainsText(it.text(), "not migrated", "not-covered-global-parameter")
        }
        assertEquals(setOf("cluster_name", "internal_cluster_id"), found.flatMap { it.about }.toSet(), "the parameter name is in about")
        r.assertNone("not-covered-parameter")
    }

    // ---- live facts ----

    @Test
    fun `mirroring-in-use fires for classic_queue_mirroring in the live facts and not for a file or other features`() {
        val f = analyze(D.document(), D.liveFacts(deprecatedFeatures = setOf("classic_queue_mirroring"))).only("mirroring-in-use")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, verified = true, evidence = EvidenceSource.LiveDeprecatedFeatures)
        assertEquals(ItemKind.Global, f.kind)
        assertEquals(Proposal.None, f.proposal)
        assertContainsText(f.text(), "reports classic queue mirroring as in use", "mirroring-in-use")
        analyze(D.document()).assertNone("mirroring-in-use")
        analyze(D.document(), D.liveFacts(deprecatedFeatures = emptySet())).assertNone("mirroring-in-use")
        analyze(D.document(), D.liveFacts(deprecatedFeatures = setOf("global_qos"))).assertNone("mirroring-in-use")
    }

    @Test
    fun `classic_queue_mirroring does not also produce deprecated-feature-in-use`() {
        analyze(D.document(), D.liveFacts(deprecatedFeatures = setOf("classic_queue_mirroring"))).assertNone("deprecated-feature-in-use")
    }

    @ParameterizedTest
    @ValueSource(strings = ["queue_master_locator", "global_qos", "transient_nonexcl_queues", "amqp_address_v1", "amqp_filter_set_bug"])
    fun `deprecated-feature-in-use is verified for the names the 4_4 broker denies`(name: String) {
        val f = analyze(D.document(), D.liveFacts(deprecatedFeatures = setOf(name))).only("deprecated-feature-in-use")
        f.assertShape(Severity.Warning, FindingClass.ClientCheck, verified = true, evidence = EvidenceSource.LiveDeprecatedFeatures)
        assertEquals(listOf(name), f.about)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertContainsText(f.text(), "reports this deprecated feature as in use", "deprecated-feature-in-use")
    }

    @Test
    fun `deprecated-feature-in-use for another name is unverified, one finding per name`() {
        val r = analyze(D.document(), D.liveFacts(deprecatedFeatures = setOf("some_other_feature", "global_qos", "another_one")))
        val found = r.withRule("deprecated-feature-in-use")
        assertEquals(listOf("another_one", "global_qos", "some_other_feature"), found.flatMap { it.about }.sorted())
        assertEquals(listOf(false, true, false), found.sortedBy { it.about.single() }.map { it.verified })
    }

    @Test
    fun `no deprecated-feature-in-use without live facts`() {
        analyze(D.document()).assertNone("deprecated-feature-in-use")
    }

    // ---- once per report ----

    @Test
    fun `the three clients- checks are info, once each, for any document including an empty one`() {
        for (doc in listOf(D.document(), D.document(queues = listOf(D.classicQueue("a"), D.classicQueue("b"), D.quorumQueue("c"))))) {
            val r = analyze(doc)
            for (id in listOf("clients-global-qos", "clients-transient-queues", "clients-amqp-address-v1")) {
                val f = r.only(id)
                f.assertShape(Severity.Info, FindingClass.ClientCheck, verified = true, evidence = EvidenceSource.KnownBrokerBehaviour)
                assertEquals(listOf(ItemKind.Global, Proposal.None), listOf(f.kind, f.proposal))
                assertContainsText(f.text(), "check your clients", id)
            }
        }
        assertContainsText(analyze(D.document()).only("clients-global-qos").text(), "basic.qos(global=true)", "clients-global-qos")
        assertContainsText(analyze(D.document()).only("clients-transient-queues").text(), "transient, non-exclusive queues are refused by default", "clients-transient-queues")
        assertContainsText(analyze(D.document()).only("clients-amqp-address-v1").text(), "AMQP 1.0 address format v1", "clients-amqp-address-v1")
    }

    @Test
    fun `classic-to-quorum-unmeasured fires once when a classic queue exists, is an unverified info, and names the three differences`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("a"), D.classicQueue("b"), D.classicQueue("c", durable = false))))
        val f = r.only("classic-to-quorum-unmeasured")
        f.assertShape(Severity.Info, FindingClass.BehaviourChange, verified = false)
        assertEquals(Proposal.None, f.proposal)
        assertContainsText(f.text(), "redelivery", "classic-to-quorum-unmeasured")
        assertContainsText(f.text(), "delivery limit", "classic-to-quorum-unmeasured")
        assertContainsText(f.text(), "exclusive consumers", "classic-to-quorum-unmeasured")
    }

    @Test
    fun `classic-to-quorum-unmeasured does not fire without a classic queue`() {
        analyze(D.document(queues = listOf(D.quorumQueue("a"), D.streamQueue("b")))).assertNone("classic-to-quorum-unmeasured")
        analyze(D.document()).assertNone("classic-to-quorum-unmeasured")
    }

    // ---- report source ----

    @Test
    fun `the source of a live report names the connection, a file report is not live`() {
        val doc = D.document(sourceVersion = "3.13.7")
        val live = analyze(doc, D.liveFacts(connectionName = "legacy-eu", brokerVersion = "3.13.7")).source
        assertEquals(listOf<Any?>("legacy-eu", true, null), listOf(live.name, live.live, live.singleVirtualHost))
        val file = analyze(doc).source
        assertEquals(listOf<Any?>(false, "3.13.7", null), listOf(file.live, file.sourceVersion, file.singleVirtualHost))
    }
}
