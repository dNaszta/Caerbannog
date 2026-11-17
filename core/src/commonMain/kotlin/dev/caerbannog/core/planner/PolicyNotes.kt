package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.echoableName

/** The kinds of queue a policy can bind to, as the measured matrix (S-F1b) knows them. A type string that is none of them is unknown: no note. */
internal enum class QueueKind(val type: String, val target: PolicyTarget, val words: String) {
    QUORUM("quorum", PolicyTarget.QUORUM_QUEUES, "quorum queue"),
    CLASSIC("classic", PolicyTarget.CLASSIC_QUEUES, "classic queue"),
    STREAM("stream", PolicyTarget.STREAMS, "stream"),
    ;

    companion object {
        fun ofType(type: String): QueueKind? = entries.firstOrNull { it.type == type }
    }
}

/**
 * When a policy would bind to a queue: its `apply-to` suits the kind AND every key of its definition is one the kind honours (S-F1b: one ignored
 * key and the policy binds to nothing). The one place this rule lives; the plan notes and the check notes both use it, through the catalog.
 */
internal object PolicyBinding {
    fun suits(applyTo: String, kind: QueueKind): Boolean = when (applyTo) {
        "all", "queues" -> true
        else -> applyTo == kind.target.wire
    }

    private fun honours(kind: QueueKind, key: String): Boolean = PolicyKeyCatalog.entry(key)?.let { kind.target in it.targets } == true

    fun binds(policy: MigrationAction.DeclarePolicy, kind: QueueKind): Boolean =
        suits(policy.applyTo, kind) && policy.definition.keys.all { honours(kind, it) }

    /** The label of the first key [kind] ignores, in catalog order, then any key the catalog does not know (by its broker key); null when none. */
    fun firstIgnored(policy: MigrationAction.DeclarePolicy, kind: QueueKind): String? {
        val known = PolicyKeyCatalog.entries.firstOrNull { it.key in policy.definition && kind.target !in it.targets }
        if (known != null) return known.label
        // A key from a file goes into the sentence only through the echo rule.
        return policy.definition.keys.firstOrNull { PolicyKeyCatalog.entry(it) == null }?.let { if (echoableName(it)) it else "one of its settings" }
    }
}

/** The notes about the policy steps of a plan, from the plan alone (and the broker's exchange names). Derived on every change, never stored. */
internal object PlanPolicyNotes {
    private class Declared(val name: String, val kind: QueueKind, val vhost: String)

    private class PolicyStep(val index: Int, val id: Int, val action: MigrationAction.DeclarePolicy, val matches: Set<String>)

    fun of(plan: TopologyPlan, known: KnownTopology): List<PlanProblem> {
        val declared = plan.steps.mapNotNull { declaredOf(it.action) }
        val names = declared.map { it.name }.distinct()
        val policies = plan.steps.mapIndexedNotNull { index, step ->
            (step.action as? MigrationAction.DeclarePolicy)?.let { PolicyStep(index, step.id, it, matched(it.pattern, names)) }
        }
        if (policies.isEmpty()) return emptyList()
        val found = mutableListOf<Pair<Int, PlanProblem>>()
        fun note(policy: PolicyStep, message: String) {
            found += policy.index to PlanProblem(policy.id, message, blocking = false)
        }
        for (queue in declared) {
            val binders = policies.filter { it.action.vhost == queue.vhost && queue.name in it.matches && PolicyBinding.binds(it.action, queue.kind) }
            val first = binders.firstOrNull() ?: continue
            binders.drop(1).forEach { other ->
                val message = PlannerTexts.policiesOverlap(first.action.name, other.action.name, queue.name)
                note(first, message)
                note(other, message)
            }
        }
        for (policy in policies) {
            val suited = declared.filter { it.vhost == policy.action.vhost && it.name in policy.matches && PolicyBinding.suits(policy.action.applyTo, it.kind) }
            suited.firstNotNullOfOrNull { queue -> PolicyBinding.firstIgnored(policy.action, queue.kind)?.let { queue to it } }
                ?.let { (queue, ignored) -> note(policy, PlannerTexts.policyNoEffect(queue.name, queue.kind.words, ignored)) }
            valueNotes(policy, plan, known).forEach { note(policy, it) }
        }
        return found.distinctBy { it.second.stepId to it.second.message }.sortedBy { it.first }.map { it.second }
    }

    /** The two notes of M9-00b: at least once without reject-publish, and a dead-letter exchange that is nowhere. */
    private fun valueNotes(policy: PolicyStep, plan: TopologyPlan, known: KnownTopology): List<String> = buildList {
        val definition = policy.action.definition
        if (definition["dead-letter-strategy"] == ArgumentValue.Text("at-least-once") && definition["overflow"] != ArgumentValue.Text("reject-publish")) {
            add(PlannerTexts.AT_LEAST_ONCE_WITHOUT_REJECT)
        }
        val exchange = (definition["dead-letter-exchange"] as? ArgumentValue.Text)?.value
        // No claim about the broker when its names were not read: a real read of a virtual host always lists the default and amq.* exchanges.
        if (exchange != null && exchange.isNotEmpty() && known.exchanges.isNotEmpty() && exchange !in known.exchanges) {
            val earlier = plan.steps.take(policy.index).any {
                val a = it.action
                a is MigrationAction.DeclareExchange && a.name == exchange && a.vhost == policy.action.vhost
            }
            if (!earlier) add(PlannerTexts.deadLetterExchangeMissing(exchange))
        }
    }

    private fun matched(pattern: String, names: List<String>): Set<String> =
        (PatternMatches.of(pattern, names) as? PatternMatchResult.Matches)?.names?.toSet().orEmpty()

    private fun declaredOf(action: MigrationAction): Declared? = when (action) {
        is MigrationAction.DeclareQueue -> QueueKind.ofType(action.type)?.takeIf { it != QueueKind.STREAM }?.let { Declared(action.name, it, action.vhost) }
        is MigrationAction.DeclareStream -> Declared(action.name, QueueKind.STREAM, action.vhost)
        else -> null
    }
}

/** What the broker already applies to a queue, read for the check: one item read per distinct policy, at most [PlanCheck.MAX_POLICY_READS]. */
internal class BrokerPolicyNotes(private val reader: BrokerPolicyReader, private val known: KnownTopology) {
    private val reads = mutableMapOf<String, PolicyReadResult>()

    /** The check note for [action], or empty: no queue to bind to, an unreadable policy and the cap all say nothing, never a guess. */
    suspend fun notesFor(action: MigrationAction.DeclarePolicy): List<String> {
        val candidates = known.queueDetails.entries.filter { (_, q) -> q.policy != null && QueueKind.ofType(q.type) != null }
        if (candidates.isEmpty()) return emptyList()
        val matching = (PatternMatches.of(action.pattern, candidates.map { it.key }) as? PatternMatchResult.Matches)?.names?.toSet().orEmpty()
        val perPolicy = LinkedHashMap<String, String>() // distinct policy name -> first queue it applies to
        for ((queueName, queue) in candidates) {
            val kind = QueueKind.ofType(queue.type) ?: continue
            val policyName = queue.policy ?: continue
            // A policy of the same name is replaced by this very step, so it is not "another policy".
            if (queueName in matching && policyName != action.name && PolicyBinding.binds(action, kind)) perPolicy.putIfAbsent(policyName, queueName)
        }
        return perPolicy.mapNotNull { (policyName, queueName) ->
            val row = (read(action.vhost, policyName) as? PolicyReadResult.Found)?.row?.takeIf { it.definitionComplete } ?: return@mapNotNull null
            when {
                action.priority > row.priority -> PlannerTexts.brokerPolicyTakenPlace(policyName, queueName)
                action.priority < row.priority -> PlannerTexts.brokerPolicyWins(policyName, queueName)
                else -> PlannerTexts.brokerPolicySamePriority(policyName, queueName)
            }
        }
    }

    private suspend fun read(vhost: String, name: String): PolicyReadResult? {
        reads[name]?.let { return it }
        if (reads.size >= PlanCheck.MAX_POLICY_READS) return null
        val answer = try {
            reader.read(vhost, name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            PolicyReadResult.Unreadable
        }
        reads[name] = answer
        return answer
    }
}
