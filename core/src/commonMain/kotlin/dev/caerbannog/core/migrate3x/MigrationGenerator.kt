package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * Turns a fully decided review into ordinary topology migrations. Pure: no request, no file, no clock. It reads typed topology
 * only and never builds a delete, a Shovel, a user or a permission action.
 */
internal object MigrationGenerator {
    private const val QUEUE_TYPE = "x-queue-type"
    private const val QUEUE_VERSION = "x-queue-version"
    private const val LAST_VERSION = 9999L
    private const val ACCESS_NOTE = "Handled by the access migration (not part of this generation)"

    /** What became of one item of the document. */
    private sealed interface Outcome {
        data class Written(val actions: List<MigrationAction>) : Outcome
        data class Skipped(val reason: SkipReason) : Outcome

        /** Replaced by the user's actions; [declares] is true when they declare the item's own name again. */
        data class Replaced(val actions: List<MigrationAction>, val declares: Boolean) : Outcome
        data object Emptied : Outcome
    }

    private data class End(val kind: ItemKind, val vhost: String, val name: String)

    fun generate(
        document: DefinitionsDocument,
        report: CompatibilityReport,
        findingsByItem: Map<ReviewItem, List<Finding>>,
        decisions: Map<ReviewItem, ItemDecision>,
        pending: List<ReviewItem>,
        request: GenerationRequest,
    ): GenerationResult {
        if (pending.isNotEmpty()) return GenerationResult.Incomplete(pending)
        val build = Build(findingsByItem, decisions)
        val notes = ArrayList<GenerationNote>()
        val skipped = ArrayList<SkippedItem>()
        val actions = ArrayList<MigrationAction>()
        val unavailable = HashMap<End, String>()

        (document.origin as? DefinitionsOrigin.SingleVirtualHost)?.let {
            notes += GenerationNote(
                "This file is an export of one virtual host and holds no users, virtual hosts or permissions; " +
                    "the virtual host \"${it.assignedName}\" must already exist on the 4.x broker",
            )
        }
        notes += reportNotes(report)

        fun place(item: ReviewItem, outcome: Outcome, end: ItemKind?) {
            val vhost = item.vhost ?: return
            val name = item.name ?: return
            when (outcome) {
                is Outcome.Written -> actions += outcome.actions
                is Outcome.Replaced -> {
                    actions += outcome.actions
                    if (!outcome.declares && end != null) unavailable[End(end, vhost, name)] = "is replaced by actions that do not declare it"
                }
                is Outcome.Skipped -> {
                    skipped += SkippedItem(item, outcome.reason)
                    if (end != null) unavailable[End(end, vhost, name)] = if (outcome.reason == SkipReason.SkippedByUser) "is skipped" else "has a name that cannot be written into a migration"
                }
                Outcome.Emptied -> {
                    val label = if (item.kind == ItemKind.OperatorPolicy) "operator policy" else "policy"
                    notes += GenerationNote("The $label \"$name\" held only keys that 4.x rejects and is not written", item)
                }
            }
        }

        for (exchange in document.exchanges) {
            val item = ReviewItem(ItemKind.Exchange, exchange.vhost, exchange.name)
            place(item, build.outcome(item) { _, _ -> listOf(exchangeAction(exchange)) }, ItemKind.Exchange)
        }
        for (queue in document.queues) {
            val item = ReviewItem(ItemKind.Queue, queue.vhost, queue.name)
            place(item, build.outcome(item) { accept, findings -> listOf(queueAction(queue, accept, findings)) }, ItemKind.Queue)
        }
        for (policy in document.policies) {
            val item = ReviewItem(ItemKind.Policy, policy.vhost, policy.name)
            place(item, build.outcome(item) { accept, findings -> policyActions(policy, accept, findings) }, null)
        }
        for (policy in document.operatorPolicies) {
            val item = ReviewItem(ItemKind.OperatorPolicy, policy.vhost, policy.name)
            place(item, build.outcome(item) { accept, findings -> operatorPolicyActions(policy, accept, findings) }, null)
        }
        for (binding in document.bindings) {
            val reason = bindingLeftOut(binding, unavailable)
            if (reason == null) {
                actions += bindingAction(binding)
            } else {
                notes += GenerationNote(reason, ReviewItem(ItemKind.Binding, binding.vhost, "${binding.source}->${binding.destination}:${binding.routingKey}"))
            }
        }

        return GenerationResult.Generated(files(actions, request), skipped, notes)
    }

    /** Decision lookup shared by every item of one generation. */
    private class Build(private val findingsByItem: Map<ReviewItem, List<Finding>>, private val decisions: Map<ReviewItem, ItemDecision>) {
        fun outcome(item: ReviewItem, written: (ItemDecision.Accept, List<Finding>) -> List<MigrationAction>): Outcome {
            if (!ReviewRules.isWritableName(item.name.orEmpty())) return Outcome.Skipped(SkipReason.NameCannotBeWritten)
            // An item without a decision is compatible: it is written as it is (the default of any finding it may hold).
            return when (val decision = decisions[item] ?: ItemDecision.Accept()) {
                ItemDecision.Skip -> Outcome.Skipped(SkipReason.SkippedByUser)
                is ItemDecision.Replace -> {
                    check(decision.actions.none { it.isDestructive }) { "a replacement with a delete action reached the generator" }
                    Outcome.Replaced(decision.actions, decision.actions.any { declaresItem(it, item) })
                }
                is ItemDecision.Accept -> {
                    val result = written(decision, findingsByItem[item].orEmpty())
                    if (result.isEmpty()) Outcome.Emptied else Outcome.Written(result)
                }
            }
        }

        private fun declaresItem(action: MigrationAction, item: ReviewItem): Boolean = action.vhost == item.vhost && when (item.kind) {
            ItemKind.Queue -> (action as? MigrationAction.DeclareQueue)?.name == item.name || (action as? MigrationAction.DeclareStream)?.name == item.name
            ItemKind.Exchange -> (action as? MigrationAction.DeclareExchange)?.name == item.name
            else -> false
        }
    }

    // ---- notes of the report ----

    private fun reportNotes(report: CompatibilityReport): List<GenerationNote> {
        val notes = ArrayList<GenerationNote>()
        val lossItems = LinkedHashSet<ReviewItem>()
        for (finding in report.findings) {
            when (finding.findingClass) {
                FindingClass.MessageLoss -> {
                    val name = finding.name ?: continue
                    val item = ReviewItem(finding.kind, finding.vhost, name)
                    if (lossItems.add(item)) {
                        notes += GenerationNote(
                            "The queue \"$name\" uses the old classic queue index; a new 4.x broker with this migration and a Shovel is the recommended path",
                            item,
                        )
                    }
                }
                FindingClass.Access -> notes += GenerationNote(ACCESS_NOTE, finding.name?.let { ReviewItem(finding.kind, finding.vhost, it) })
                else -> Unit
            }
        }
        return notes
    }

    // ---- declarations ----

    private fun kindName(kind: QueueKind): String = when (kind) {
        QueueKind.Classic -> "classic"
        QueueKind.Quorum -> "quorum"
        QueueKind.Stream -> "stream"
        is QueueKind.Other -> kind.name
    }

    private fun queueAction(queue: QueueDefinition, accept: ItemDecision.Accept, findings: List<Finding>): MigrationAction {
        if (queue.type == QueueKind.Stream) return MigrationAction.DeclareStream(queue.name, queue.vhost)
        val picked = ProposalChoice.resolve(findings, accept.chosen)
        var type = queue.type
        for ((_, proposal) in picked) {
            when (proposal) {
                is Proposal.ConvertQueueType -> type = proposal.target
                Proposal.KeepClassic -> type = QueueKind.Classic
                else -> Unit
            }
        }
        var durable = queue.durable
        var autoDelete = queue.autoDelete
        val arguments = LinkedHashMap(queue.arguments)
        for ((finding, proposal) in picked) {
            val applies = when (finding.appliesWhen) {
                AppliesWhen.Always -> true
                AppliesWhen.ConvertedToQuorum -> type == QueueKind.Quorum
                AppliesWhen.KeptClassic -> type == QueueKind.Classic
            }
            if (!applies) continue
            when (proposal) {
                is Proposal.RemoveArgument -> arguments.remove(proposal.argument)
                is Proposal.SetArgument -> arguments[proposal.argument] = proposal.value
                Proposal.ClearAutoDelete -> autoDelete = false
                Proposal.MakeDurable -> durable = true
                else -> Unit
            }
        }
        // The type is a field of the action, never an argument; the index version is only ever kept (as 2) on a classic queue.
        arguments.remove(QUEUE_TYPE)
        if (type != QueueKind.Classic || arguments[QUEUE_VERSION] != ArgumentValue.Whole(2)) arguments.remove(QUEUE_VERSION)
        return MigrationAction.DeclareQueue(queue.name, kindName(type), queue.vhost, durable, arguments, autoDelete)
    }

    private fun exchangeAction(exchange: ExchangeDefinition) = MigrationAction.DeclareExchange(
        exchange.name, exchange.type, exchange.vhost, exchange.durable, exchange.autoDelete, exchange.internal, exchange.arguments,
    )

    private fun keptDefinition(definition: Map<String, ArgumentValue>, accept: ItemDecision.Accept, findings: List<Finding>): Map<String, ArgumentValue> {
        val dropped = ProposalChoice.resolve(findings, accept.chosen).flatMap { (it.proposal as? Proposal.DropPolicyKeys)?.keys.orEmpty() }.toSet()
        return if (dropped.isEmpty()) definition else definition.filterKeys { it !in dropped }
    }

    /** An empty result means the policy held only dropped keys and is not written. */
    private fun policyActions(policy: PolicyDefinition, accept: ItemDecision.Accept, findings: List<Finding>): List<MigrationAction> {
        val definition = keptDefinition(policy.definition, accept, findings)
        if (definition.isEmpty() && policy.definition.isNotEmpty()) return emptyList()
        return listOf(MigrationAction.DeclarePolicy(policy.name, policy.pattern, definition, policy.vhost, policy.applyTo, policy.priority))
    }

    private fun operatorPolicyActions(policy: OperatorPolicyDefinition, accept: ItemDecision.Accept, findings: List<Finding>): List<MigrationAction> {
        val definition = keptDefinition(policy.definition, accept, findings)
        if (definition.isEmpty() && policy.definition.isNotEmpty()) return emptyList()
        return listOf(MigrationAction.DeclareOperatorPolicy(policy.name, policy.pattern, definition, policy.vhost, policy.applyTo, policy.priority))
    }

    private fun bindingAction(binding: BindingDefinition) = MigrationAction.DeclareBinding(
        binding.source, binding.destination, binding.routingKey, binding.vhost,
        if (binding.destinationKind == BindingTarget.Exchange) BindingDestinationType.EXCHANGE else BindingDestinationType.QUEUE,
        binding.arguments,
    )

    /** The note text when an end of the binding is not written, else null. */
    private fun bindingLeftOut(binding: BindingDefinition, unavailable: Map<End, String>): String? {
        val destinationKind = if (binding.destinationKind == BindingTarget.Exchange) ItemKind.Exchange else ItemKind.Queue
        val ends = listOf(
            Triple("exchange", binding.source, End(ItemKind.Exchange, binding.vhost, binding.source)),
            Triple(if (destinationKind == ItemKind.Exchange) "exchange" else "queue", binding.destination, End(destinationKind, binding.vhost, binding.destination)),
        )
        for ((label, name, end) in ends) {
            val why = unavailable[end] ?: continue
            return "The binding from \"${binding.source}\" to \"${binding.destination}\" is left out because the $label \"$name\" $why"
        }
        return null
    }

    // ---- files ----

    private fun files(actions: List<MigrationAction>, request: GenerationRequest): List<GeneratedMigration> {
        val byVhost = actions.groupBy { it.vhost }
        if (byVhost.isEmpty()) return emptyList()
        val highest = request.existingVersions
            .filter { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            .maxOfOrNull { it.toLongOrNull() ?: Long.MAX_VALUE } ?: 0L
        return byVhost.keys.sorted().mapIndexed { index, vhost ->
            val number = minOf(highest, LAST_VERSION) + index + 1
            check(number <= LAST_VERSION) { "the next migration version would pass $LAST_VERSION" }
            val version = number.toString().padStart(4, '0')
            val raw = MigrationParser.exportToJson(version, "Migrated from RabbitMQ 3.x ${request.source} vhost $vhost", byVhost.getValue(vhost))
            GeneratedMigration(vhost, parsed(raw, request.existingVersions))
        }
    }

    private fun parsed(raw: String, existingVersions: Set<String>): MigrationFile = when (val result = MigrationParser.parse(raw, existingVersions)) {
        is MigrationValidationResult.Valid -> result.migration
        is MigrationValidationResult.Invalid -> error("a generated migration does not parse: ${result.reason}")
    }
}
