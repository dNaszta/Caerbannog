package dev.caerbannog.core.migrate3x

/**
 * What is known about one rule. [evidence] names the spike section and fixture of `M8-00` that proves the rule, or `"none"`
 * for a rule nobody measured; [verified] is true only for the first kind.
 */
data class RuleInfo(val id: String, val verified: Boolean, val evidence: String)

/** Deprecated features a 4.4 broker denies by default (M8-00 S-G); a finding about one of them is verified. */
internal val DENIED_ON_4X: Set<String> = setOf("queue_master_locator", "global_qos", "transient_nonexcl_queues", "amqp_address_v1", "amqp_filter_set_bug")

/** The one list of rule ids and their evidence. A finding reads its `verified` flag here, never at the place it is emitted. */
object RuleCatalog {
    private const val NONE = "none"

    val all: List<RuleInfo> = listOf(
        verified("ha-policy-key", "M8-00 S-E: ha-* keys rejected on 4.4, policy and operator policy"),
        verified("queue-master-locator-policy-key", "M8-00 S-E: queue-master-locator rejected as deprecated on 4.4"),
        verified("operator-policy-key-rejected", "M8-00 S-E: operator policy on 4.4 accepts only the limits"),
        verified("policy-queue-mode", "M8-00 S-E: queue-mode accepted on 4.4"),
        unverified("policy-max-in-memory"),
        verified("not-covered-policy-key", "M8-00 S-B: federation parameter rejected when the plugin is off"),
        unverified("policy-pattern-unreadable"),
        verified("queue-not-durable", "M8-00 S-B, S-D: skipped silently with HTTP 204 on import"),
        verified("queue-master-locator-argument", "M8-00 S-B, S-C, S-D: x-queue-master-locator rejected, import skips silently"),
        verified("quorum-rejected-setting", "M8-00 S-C queue-declaration-matrix-4.x.json"),
        verified("classic-rejected-argument", "M8-00 S-D: classic queue arguments rejected on 4.4"),
        unverified("quorum-max-in-memory"),
        verified("classic-not-replicated", "M8-00 S-D: mirroring removed in 4.0 (owner-verified fact)"),
        verified("classic-to-quorum-ordering", "M8-00 S-G: quorum queue orders by priority"),
        unverified("queue-type-unknown"),
        verified("classic-index-v1", "M8-00 S-N: classic queue index version 1 is emptied by an in-place upgrade"),
        unverified("exchange-type-not-builtin"),
        verified("not-covered-parameter", "M8-00 S-B: a parameter of a plugin that is off is rejected on import"),
        verified("not-covered-global-parameter", "M8-00 S-A: shape of the 3.13 export"),
        verified("mirroring-in-use", "M8-00 S-G deprecated-features-used-3.13.json"),
        verified("deprecated-feature-in-use", "M8-00 S-G deprecated-features-used-3.13.json; verified only for the names in DENIED_ON_4X"),
        verified("clients-global-qos", "M8-00 S-I: global_qos denied by default on 4.4"),
        verified("clients-transient-queues", "M8-00 S-I: transient, non-exclusive queues refused by default"),
        verified("clients-amqp-address-v1", "M8-00 S-I: AMQP 1.0 address format v1 denied by default"),
        unverified("classic-to-quorum-unmeasured"),
        verified("access-declare-vhost", "M8-00 S-A: virtual hosts in the export"),
        verified("access-declare-user", "M8-00 S-M: users and password hashes"),
        verified("access-set-permissions", "M8-00 S-A: permissions in the export"),
        verified("access-set-topic-permissions", "M8-00 S-A: topic permissions in the export"),
        verified("user-guest", "spec P11: guest is skipped by default"),
        verified("user-administrator-tag", "spec P11: the migration carries the administrator tag"),
        verified("user-hash-without-algorithm", "M8-00 S-M: login fails without the hashing algorithm"),
        verified("user-hash-algorithm-unsupported", "M8-00 S-M: 4.x rejects the algorithm"),
        verified("user-no-password-hash", "M8-00 S-A: users without a hash in the export"),
        verified("access-not-in-file", "spec P17: a per-virtual-host export holds no access data"),
    )

    private val byId: Map<String, RuleInfo> = all.associateBy { it.id }

    /** The entry of [id]; a rule that is not listed is a programming error, caught by the catalog test. */
    operator fun get(id: String): RuleInfo = byId[id] ?: error("rule $id is not in RuleCatalog")

    private fun verified(id: String, evidence: String) = RuleInfo(id, verified = true, evidence = evidence)

    private fun unverified(id: String) = RuleInfo(id, verified = false, evidence = NONE)
}
