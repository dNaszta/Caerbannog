package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration

/** What the export wrote ([actions]) and the one plain line about what it could not carry ([note], null when nothing was left out). */
internal data class TopologyExportResult(val actions: List<MigrationAction>, val note: String?)

/**
 * M9-14: turns what the catalog read into the actions of the exported migration. Pure, so the rules are unit tested without a broker.
 * The rules are measured facts, see `docs/tasks/M9-14-spike-results.md`:
 *  - `x-queue-type` is the `type` key and is never an argument of the file;
 *  - exclusive queues, server-named `amq.gen-` queues and MQTT `mqtt-subscription-` queues belong to a connection and are left out silently
 *    (exclusive ones are named in the note); a non-durable queue cannot be applied to a default 4.x broker and is left out as "transient";
 *  - the bindings of a left-out queue go with it; a binding on a built-in exchange is kept, the exchange itself is not declared;
 *  - `declareStream` carries no arguments, so a stream's own arguments (retention, leader locator) are named in the note;
 *  - order: queues, exchanges (today's order, so a topology without streams, policies or arguments gives today's bytes), streams, bindings,
 *    policies, operator policies; the broker only needs both ends of a binding first.
 */
internal object TopologyExport {
    private const val QUEUE_TYPE_ARGUMENT = "x-queue-type"
    private val connectionQueuePrefixes = listOf("amq.gen-", "mqtt-subscription-")

    // The wording of the line under the export result: the owner's review item, one place to change.
    const val NOTE_PREFIX = "Left out: "
    const val NOTE_SEPARATOR = "; "
    fun streamRetentionNote(name: String) = "retention of stream $name"
    fun exclusiveQueueNote(name: String) = "exclusive queue $name"
    fun transientQueueNote(name: String) = "transient queue $name (cannot be applied)"

    fun actions(
        vhost: String,
        declarations: List<QueueDeclaration>,
        exchanges: List<ExchangeRow>,
        bindings: List<BindingRow>,
        policies: List<PolicyRow>,
        operatorPolicies: List<PolicyRow>,
    ): TopologyExportResult {
        val (streams, queues) = declarations.partition { it.type == "stream" }
        val exported = queues.filter { !isConnectionQueue(it) && it.durable }
        val keptNames = (exported + streams).map { it.name }.toSet()
        val leftOut = queues.filter { it !in exported }

        val bindingActions = bindings.filter { it.keepsBothEnds(keptNames) }.mapNotNull { bindingAction(it, vhost) }
        val actions = exported.map { queueAction(it, vhost) } + exchanges.mapNotNull { exchangeAction(it, vhost) } +
            streams.map { MigrationAction.DeclareStream(name = it.name, vhost = vhost) } + bindingActions + policies.map { policyAction(it, vhost) } +
            operatorPolicies.map { operatorPolicyAction(it, vhost) }
        return TopologyExportResult(actions, note(streams, leftOut))
    }

    private fun isConnectionQueue(queue: QueueDeclaration): Boolean =
        queue.exclusive || connectionQueuePrefixes.any { queue.name.startsWith(it) }

    private fun BindingRow.keepsBothEnds(keptQueues: Set<String>): Boolean =
        destinationType != BindingDestinationType.QUEUE || destination in keptQueues

    private fun ownArguments(arguments: Map<String, ArgumentValue>): Map<String, ArgumentValue> =
        arguments.filterKeys { it != QUEUE_TYPE_ARGUMENT }

    private fun note(streams: List<QueueDeclaration>, leftOut: List<QueueDeclaration>): String? {
        val parts = streams.filter { ownArguments(it.arguments).isNotEmpty() }.map { streamRetentionNote(it.name) } +
            leftOut.filter { it.exclusive }.map { exclusiveQueueNote(it.name) } +
            leftOut.filter { !it.exclusive && !it.durable && connectionQueuePrefixes.none { p -> it.name.startsWith(p) } }.map { transientQueueNote(it.name) }
        return if (parts.isEmpty()) null else NOTE_PREFIX + parts.joinToString(NOTE_SEPARATOR)
    }

    private fun queueAction(queue: QueueDeclaration, vhost: String): MigrationAction =
        MigrationAction.DeclareQueue(
            name = queue.name,
            type = queue.type,
            vhost = vhost,
            durable = queue.durable,
            arguments = ownArguments(queue.arguments),
            autoDelete = queue.autoDelete,
        )

    private fun exchangeAction(row: ExchangeRow, vhost: String): MigrationAction? =
        if (row.name.isEmpty() || row.name.startsWith("amq.")) {
            null
        } else {
            MigrationAction.DeclareExchange(
                name = row.name,
                type = row.type,
                vhost = vhost,
                durable = row.durable,
                autoDelete = row.autoDelete,
                internal = row.internal,
                arguments = row.typedArguments,
            )
        }

    private fun bindingAction(row: BindingRow, vhost: String): MigrationAction? =
        if (row.source.isEmpty() || row.destination.isEmpty()) {
            null
        } else {
            MigrationAction.DeclareBinding(
                exchange = row.source,
                queue = row.destination,
                routingKey = row.routingKey,
                vhost = vhost,
                destinationType = row.destinationType,
                arguments = row.typedArguments,
            )
        }

    private fun policyAction(row: PolicyRow, vhost: String) =
        MigrationAction.DeclarePolicy(row.name, row.pattern, row.definition, vhost, row.applyTo, row.priority)

    private fun operatorPolicyAction(row: PolicyRow, vhost: String) =
        MigrationAction.DeclareOperatorPolicy(row.name, row.pattern, row.definition, vhost, row.applyTo, row.priority)
}
