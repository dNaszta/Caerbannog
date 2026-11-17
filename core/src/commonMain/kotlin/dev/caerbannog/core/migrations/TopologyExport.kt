package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration

/** M9-14b: the export either wrote its actions ([Written]) or the broker was read but a setting in it cannot be carried by a file ([NotWritable]). */
internal sealed interface TopologyExportOutcome {
    data class Written(val result: TopologyExportResult) : TopologyExportOutcome
    data object NotWritable : TopologyExportOutcome
}

/** What the export wrote ([actions]) and the one plain line about what it could not carry ([note], null when nothing was left out). */
internal data class TopologyExportResult(val actions: List<MigrationAction>, val note: String?)

/**
 * M9-14: turns what the catalog read into the actions of the exported migration. Pure, so the rules are unit tested without a broker.
 * The rules are measured facts, see `docs/tasks/M9-14-spike-results.md`:
 *  - `x-queue-type` is the `type` key and is never an argument of the file;
 *  - exclusive queues, server-named `amq.gen-` queues and MQTT `mqtt-subscription-` queues belong to a connection and are left out; the
 *    exclusive ones are named in the note whatever their name, a non-exclusive `amq.gen-` or `mqtt-subscription-` queue is left out silently;
 *    a non-durable queue cannot be applied to a default 4.x broker and is left out as "transient";
 *  - the bindings of a left-out queue go with it; a binding on a built-in exchange is kept, the exchange itself is not declared;
 *  - `declareStream` carries the four stream settings of [StreamSettingRules] (M9-14c), written with their typed values, the leader locator
 *    always (the owner's working default: the file says what the broker had); a stream argument that is none of the four, or whose value
 *    the rules refuse, or that could not be typed, is not written and the note names it as "other settings", so the export never writes
 *    a file the parser refuses;
 *  - order: queues, exchanges (today's order, so a topology without streams, policies or arguments gives today's bytes), streams, bindings,
 *    policies, operator policies; the broker only needs both ends of a binding first.
 */
internal object TopologyExport {
    private const val QUEUE_TYPE_ARGUMENT = "x-queue-type"
    private val connectionQueuePrefixes = listOf("amq.gen-", "mqtt-subscription-")

    // The wording of the line under the export result: the owner's review items, one place to change.
    const val NOTE_PREFIX = "Left out: "
    const val NOTE_SEPARATOR = "; "
    const val MAX_NOTE_ENTRIES = 10
    const val OTHER_SETTINGS_WORD = "other settings"
    private const val WORDS_LAST_JOINER = " and "
    private const val WORDS_JOINER = ", "

    /** [words] joined "a, b and c", then "of stream [name]". */
    fun streamNote(words: List<String>, name: String) = "${joinWords(words)} of stream $name"
    fun superStreamNote(words: List<String>, name: String, partitions: Int) =
        "${joinWords(words)} of super stream $name (${if (partitions == 1) "1 partition" else "$partitions partitions"})"
    fun moreNote(count: Int) = "and $count more"
    fun exclusiveQueueNote(name: String) = "exclusive queue $name"
    fun transientQueueNote(name: String) = "transient queue $name (cannot be applied)"

    private fun joinWords(words: List<String>): String =
        if (words.size <= 1) words.joinToString("") else words.dropLast(1).joinToString(WORDS_JOINER) + WORDS_LAST_JOINER + words.last()

    fun actions(
        vhost: String,
        declarations: List<QueueDeclaration>,
        exchanges: List<ExchangeRow>,
        bindings: List<BindingRow>,
        policies: List<PolicyRow>,
        operatorPolicies: List<PolicyRow>,
    ): TopologyExportOutcome {
        val (streams, queues) = declarations.partition { it.type == "stream" }
        val exported = queues.filter { !isConnectionQueue(it) && it.durable }
        val keptNames = (exported + streams).map { it.name }.toSet()
        val leftOut = queues.filter { it !in exported }

        // Only what the file carries is checked: a setting the model refused would be dropped from it silently, so the export fails instead.
        // A stream carries its four settings (what else it holds is named in the note), a left-out queue is not written, and neither is a built-in exchange.
        val writtenBindings = bindings.filter { it.keepsBothEnds(keptNames) && it.source.isNotEmpty() && it.destination.isNotEmpty() }
        val writtenExchanges = exchanges.filter { it.name.isNotEmpty() && !it.name.startsWith("amq.") }
        val incomplete = exported.any { !it.typedArgumentsComplete } || writtenExchanges.any { !it.typedArgumentsComplete } ||
            writtenBindings.any { !it.typedArgumentsComplete } || policies.any { !it.definitionComplete } || operatorPolicies.any { !it.definitionComplete }
        if (incomplete) return TopologyExportOutcome.NotWritable

        val bindingActions = writtenBindings.mapNotNull { bindingAction(it, vhost) }
        val actions = exported.map { queueAction(it, vhost) } + writtenExchanges.mapNotNull { exchangeAction(it, vhost) } +
            streams.map { MigrationAction.DeclareStream(name = it.name, vhost = vhost, arguments = writtenSettings(it)) } + bindingActions + policies.map { policyAction(it, vhost) } +
            operatorPolicies.map { operatorPolicyAction(it, vhost) }
        return TopologyExportOutcome.Written(TopologyExportResult(actions, note(streams, exchanges, bindings, leftOut)))
    }

    private fun isConnectionQueue(queue: QueueDeclaration): Boolean =
        queue.exclusive || connectionQueuePrefixes.any { queue.name.startsWith(it) }

    private fun BindingRow.keepsBothEnds(keptQueues: Set<String>): Boolean =
        destinationType != BindingDestinationType.QUEUE || destination in keptQueues

    private fun ownArguments(arguments: Map<String, ArgumentValue>): Map<String, ArgumentValue> =
        arguments.filterKeys { it != QUEUE_TYPE_ARGUMENT }

    /** The stream's settings the file can carry: the four keys with a value [StreamSettingRules] accepts, so the parser accepts the file again. */
    private fun writtenSettings(stream: QueueDeclaration): Map<String, ArgumentValue> =
        ownArguments(stream.arguments).filter { (key, value) -> key in StreamSettingRules.keys && StreamSettingRules.refusal(key, value) == null }

    /** The words for what a stream's arguments say that the file cannot carry: only "other settings", or none. */
    private fun words(streams: List<QueueDeclaration>): List<String> {
        val other = streams.any { stream ->
            ownArguments(stream.arguments).size != writtenSettings(stream).size || !stream.typedArgumentsComplete
        }
        return listOfNotNull(OTHER_SETTINGS_WORD.takeIf { other })
    }

    private fun note(streams: List<QueueDeclaration>, exchanges: List<ExchangeRow>, bindings: List<BindingRow>, leftOut: List<QueueDeclaration>): String? {
        // A partition belongs to the first super stream (by exchange name) whose binding points at it.
        val streamNames = streams.map { it.name }.toSet()
        val partitionsOf = mutableMapOf<String, MutableList<QueueDeclaration>>()
        val claimed = mutableSetOf<String>()
        for (exchange in exchanges.filter { it.superStream }.sortedBy { it.name }) {
            val own = bindings.filter { it.source == exchange.name && it.destinationType == BindingDestinationType.QUEUE && it.destination in streamNames }
                .map { it.destination }.distinct().filter { claimed.add(it) }
            partitionsOf[exchange.name] = streams.filter { it.name in own }.toMutableList()
        }
        val plain = streams.filter { it.name !in claimed }
        val superStreams = partitionsOf.filterValues { it.isNotEmpty() }
        val entries = plain.mapNotNull { s -> words(listOf(s)).takeIf { it.isNotEmpty() }?.let { streamNote(it, s.name) } } +
            superStreams.mapNotNull { (name, partitions) -> words(partitions).takeIf { it.isNotEmpty() }?.let { superStreamNote(it, name, partitions.size) } } +
            leftOut.filter { it.exclusive }.map { exclusiveQueueNote(it.name) } +
            leftOut.filter { isTransient(it) }.map { transientQueueNote(it.name) }
        if (entries.isEmpty()) return null
        val shown = if (entries.size > MAX_NOTE_ENTRIES) entries.take(MAX_NOTE_ENTRIES) + moreNote(entries.size - MAX_NOTE_ENTRIES) else entries
        return NOTE_PREFIX + shown.joinToString(NOTE_SEPARATOR)
    }

    /** Not exclusive, not a connection's queue, not durable: the note names it because the file cannot apply it. */
    private fun isTransient(queue: QueueDeclaration): Boolean =
        !queue.exclusive && !queue.durable && connectionQueuePrefixes.none { queue.name.startsWith(it) }

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
