package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/** What the planner can say about the destination of a route. A stream is a queue for a binding, so the action cannot tell. */
enum class MqttDestinationKind { Queue, Stream }

/** A recognised route: the topic [filter], the binding [key] it is saved as and the [destination] name. */
data class MqttRoute(val filter: String, val key: String, val destination: String)

/** The two lines a route is shown with: a title and a detail line. */
data class MqttRouteWords(val title: String, val detail: String)

/**
 * Recognition of an MQTT route (A10) and its words. The planner list, the review graph, the wizard summary and the Migrations rows all call
 * this, so a route is worded the same everywhere (the problem: four screens, one rule).
 */
object MqttRoutes {
    const val EXCHANGE: String = "amq.topic"

    /** A plain binding on amq.topic to a queue or stream whose key gives a filter that gives the same key again; null for anything else. */
    fun routeOf(action: MigrationAction): MqttRoute? {
        if (action !is MigrationAction.DeclareBinding) return null
        if (action.exchange != EXCHANGE || action.destinationType != BindingDestinationType.QUEUE || action.arguments.isNotEmpty()) return null
        val filter = MqttTopicTranslation.toFilter(action.routingKey) ?: return null
        if (MqttTopicTranslation.toBindingKey(filter) != Translation.Key(action.routingKey)) return null
        return MqttRoute(filter, action.routingKey, action.queue)
    }

    /** The kind of [destination]: from the plan's own declarations first, then from what the broker has; null when nobody knows. */
    fun kindOf(destination: String, plan: List<MigrationAction>, known: KnownTopology): MqttDestinationKind? = when {
        plan.any { it is MigrationAction.DeclareStream && it.name == destination } -> MqttDestinationKind.Stream
        plan.any { it is MigrationAction.DeclareQueue && it.name == destination } -> MqttDestinationKind.Queue
        destination in known.streams -> MqttDestinationKind.Stream
        destination in known.queues -> MqttDestinationKind.Queue
        else -> null
    }

    /** The words of a route, or null when [action] is not one (the caller then uses the action's own description and detail). */
    fun words(action: MigrationAction, plan: List<MigrationAction>, known: KnownTopology = KnownTopology.None): MqttRouteWords? {
        val route = routeOf(action) ?: return null
        val to = when (kindOf(route.destination, plan, known)) {
            MqttDestinationKind.Stream -> "stream ${route.destination}"
            MqttDestinationKind.Queue -> "queue ${route.destination}"
            null -> route.destination
        }
        return MqttRouteWords(PlannerTexts.mqttRouteTitle(route.filter, to), PlannerTexts.mqttRouteDetail(route.key))
    }
}
