package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * What the broker has, as plain values the caller builds: exchanges with their types, queue names and stream names.
 * [None] stands for "not connected". `core.planner` never asks the broker itself.
 */
data class KnownTopology(
    val exchanges: Map<String, String> = emptyMap(),
    val queues: Set<String> = emptySet(),
    val streams: Set<String> = emptySet(),
    /** Type and applied policy of the queues and streams the broker has, by name; filled where the reader has them (M9-08, check notes). */
    val queueDetails: Map<String, KnownQueue> = emptyMap(),
) {
    companion object {
        val None: KnownTopology = KnownTopology()
    }
}

/**
 * One queue or stream of the broker: its [type] (`quorum`, `classic`, `stream`) and the name of the policy that applies to it, null for none or unknown.
 * [ownDeadLetterExchange] is the value of the queue's own `x-dead-letter-exchange` argument (M9-09); null when it has none or the read did not say.
 */
data class KnownQueue(val type: String, val policy: String?, val ownDeadLetterExchange: String? = null)

enum class SuggestionSource { PLAN, BROKER }

data class Suggestion(val name: String, val source: SuggestionSource)

/** What a form needs to know besides its own fields: the plan (virtual host, earlier steps), the naming style and the broker's names. */
data class FormContext(
    val plan: TopologyPlan,
    val namingStyle: NamingStyle,
    val known: KnownTopology = KnownTopology.None,
    /** The profile's display name, for the match line (`on "uat-eu"`); null or blank when the broker was not read, so no claim is made about it. */
    val connectionName: String? = null,
) {
    /** Queues and streams (or exchanges) for a binding destination: this plan first, then the broker, no name twice. */
    fun destinationSuggestions(kind: BindingDestinationType): List<Suggestion> = when (kind) {
        BindingDestinationType.QUEUE -> suggestions(
            fromPlan = plan.steps.mapNotNull { step ->
                when (val a = step.action) {
                    is MigrationAction.DeclareQueue -> a.name
                    is MigrationAction.DeclareStream -> a.name
                    else -> null
                }
            },
            fromBroker = known.queues + known.streams,
        )
        BindingDestinationType.EXCHANGE -> exchangeSuggestions()
    }

    /** The queues a dead-lettering form can pick: this plan's queues first, then the broker's, no name twice. Streams are never offered (S-D1). */
    fun queueSuggestions(): List<Suggestion> = suggestions(
        fromPlan = plan.steps.mapNotNull { (it.action as? MigrationAction.DeclareQueue)?.name },
        fromBroker = known.queues,
    )

    /** Destinations for an MQTT route: only streams or only queues when the kind is said, else both (this plan first, then the broker). */
    fun routeDestinationSuggestions(kind: MqttDestinationKind?): List<Suggestion> {
        val all = destinationSuggestions(BindingDestinationType.QUEUE)
        if (kind == null) return all
        val streams = plan.steps.mapNotNull { (it.action as? MigrationAction.DeclareStream)?.name }.toSet() + known.streams
        return all.filter { (it.name in streams) == (kind == MqttDestinationKind.Stream) }
    }

    fun exchangeSuggestions(): List<Suggestion> = suggestions(planExchanges().map { it.name }, known.exchanges.keys)

    /** The routing key is asked unless the exchange is a fanout or headers exchange whose type is known (the plan's own count as known). */
    fun asksRoutingKey(exchange: String): Boolean = exchangeType(exchange).let { it != "fanout" && it != "headers" }

    /** The type of [exchange]: the last declaration in this plan wins, then the broker's; null when neither knows it. The one place that reads it. */
    fun exchangeType(exchange: String): String? =
        planExchanges().lastOrNull { it.name == exchange }?.type ?: known.exchanges[exchange]

    /**
     * The type of the queue or stream [name]: the last declaration in this plan wins (a stream gives `stream`), then the broker's; null when neither
     * knows it. The one place that reads it, so `apply-to` and "At least once" cannot disagree.
     */
    fun queueType(name: String): String? {
        val declared = plan.steps.map { it.action }.lastOrNull {
            (it is MigrationAction.DeclareQueue && it.name == name) || (it is MigrationAction.DeclareStream && it.name == name)
        }
        return when (declared) {
            is MigrationAction.DeclareQueue -> declared.type
            is MigrationAction.DeclareStream -> "stream"
            else -> known.queueDetails[name]?.type
        }
    }

    private fun planExchanges(): List<MigrationAction.DeclareExchange> =
        plan.steps.mapNotNull { it.action as? MigrationAction.DeclareExchange }

    private fun suggestions(fromPlan: List<String>, fromBroker: Collection<String>): List<Suggestion> {
        val planned = fromPlan.distinct()
        return planned.map { Suggestion(it, SuggestionSource.PLAN) } +
            fromBroker.filterNot { it in planned }.distinct().map { Suggestion(it, SuggestionSource.BROKER) }
    }
}
