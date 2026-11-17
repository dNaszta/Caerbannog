package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingGraph
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.GraphNode
import dev.caerbannog.core.topology.GraphNodeKind
import dev.caerbannog.core.topology.buildBindingGraph

/** The two views of the review card. Graph is first. */
enum class ReviewView { Graph, Json }

/** How a node of the review graph relates to the plan and the broker. */
enum class PlanMark { New, Existing, NotInPlan }

/** Why only the plan is drawn. */
enum class OnlyThePlanCause { NotConnected, BrokerNotRead }

/** A line above (or instead of) the graph. */
sealed interface PlanGraphNotice {
    val title: String
    val detail: String

    data object TooLarge : PlanGraphNotice {
        override val title: String get() = "This plan is too large to draw"
        override val detail: String get() = "The graph shows at most 60 items. The step list is the review."
    }

    data class OnlyThePlan(val cause: OnlyThePlanCause) : PlanGraphNotice {
        override val title: String get() = "Only the plan is drawn"
        override val detail: String
            get() = when (cause) {
                OnlyThePlanCause.NotConnected -> "Not connected: what already exists on a broker is not shown."
                OnlyThePlanCause.BrokerNotRead -> "The broker could not be read: what already exists is not shown."
            }
    }
}

/** A dead-letter or response-stream link between two nodes; filled by M9-07 and M9-09, empty until then. */
data class PlanLink(val sourceId: String, val targetId: String, val kind: PlanLinkKind)

enum class PlanLinkKind { DeadLetter, ResponseStream }

/** A plain note drawn between two nodes, never a step or an edge; filled by M9-07b. */
data class PlanGraphNote(val sourceId: String, val targetId: String, val text: String)

/**
 * The review graph of a plan: [graph] is built by the existing `buildBindingGraph`, the sets say what is new beside it so the Topology screen's
 * graph types stay untouched. Everything here is derived from a plan in memory; it never reaches the broker.
 */
data class PlanGraph(
    val graph: BindingGraph,
    val newNodeIds: Set<String>,
    val newEdgeIds: Set<String>,
    /** Referred to by a binding, not declared by the plan and not on the broker (or the broker was not read). */
    val notInPlanNodeIds: Set<String>,
    val links: List<PlanLink> = emptyList(),
    val edgeLabels: Map<String, String> = emptyMap(),
    val notice: PlanGraphNotice? = null,
    /** The edges that are MQTT routes (A10); the screen draws [PlannerTexts.MQTT_ROUTE_LABEL] on them and their filter from [edgeLabels]. */
    val mqttRouteEdgeIds: Set<String> = emptySet(),
    val notes: List<PlanGraphNote> = emptyList(),
) {
    fun markOf(nodeId: String): PlanMark = when (nodeId) {
        in newNodeIds -> PlanMark.New
        in notInPlanNodeIds -> PlanMark.NotInPlan
        else -> PlanMark.Existing
    }

    /** "queue orders, new" / "exchange events, already on the broker" / "queue ghost, not in this plan". */
    fun accessibleName(node: GraphNode): String {
        val mark = when (markOf(node.id)) {
            PlanMark.New -> "new"
            PlanMark.Existing -> "already on the broker"
            PlanMark.NotInPlan -> "not in this plan"
        }
        return "${node.kind.name.lowercase()} ${node.name}, $mark"
    }
}

/**
 * Turns [plan] and what the broker has ([known], null: nothing was read) into the review graph.
 *
 * Drawn: every exchange, queue and stream the plan declares, and the ends of the plan's bindings. The broker's other items and all its
 * bindings are not drawn. A name the plan declares and the broker already has is existing, not new (the plan adds nothing there). An end
 * of a binding that neither the plan nor the broker has is "not in this plan". [onlyThePlanCause] says why [known] is null.
 * While [pending] (a read is under way, [known] null) there is no notice and no "not in this plan" mark. Too large (more than `MAX_GRAPH_NODES` nodes) wins over the "only the plan" notice.
 */
fun planPreview(
    plan: TopologyPlan,
    known: KnownTopology?,
    onlyThePlanCause: OnlyThePlanCause = OnlyThePlanCause.NotConnected,
    pending: Boolean = false,
): PlanGraph {
    val actions = plan.steps.map { it.action }
    val declaredExchanges = actions.filterIsInstance<MigrationAction.DeclareExchange>().associate { it.name to it.type }
    val declaredQueueTypes = actions.filterIsInstance<MigrationAction.DeclareQueue>().associate { it.name to it.type }
    val declaredQueues = declaredQueueTypes.keys.toList()
    val declaredStreams = actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name }
    val bindings = actions.filterIsInstance<MigrationAction.DeclareBinding>()

    val brokerExchanges = known?.exchanges.orEmpty()
    val brokerQueues = known?.queues.orEmpty()
    val brokerStreams = known?.streams.orEmpty()

    val exchangeNames = linkedSetOf<String>()
    val queueNames = linkedSetOf<String>()
    exchangeNames += declaredExchanges.keys
    queueNames += declaredQueues + declaredStreams
    bindings.forEach { b ->
        exchangeNames += b.exchange
        if (b.destinationType == BindingDestinationType.EXCHANGE) exchangeNames += b.queue else queueNames += b.queue
    }

    val streamNames = (declaredStreams + queueNames.filter { it in brokerStreams }).toSet()
    fun exchangeType(name: String): String = brokerExchanges[name] ?: declaredExchanges[name] ?: builtInExchangeType(name) ?: "direct"
    val drawn = buildBindingGraph(
        exchanges = exchangeNames.map { name ->
            ExchangeRow(name, plan.vhost, exchangeType(name), durable = true, autoDelete = false, internal = false)
        },
        queues = emptyList(),
        bindings = bindings.map { BindingRow(it.exchange, plan.vhost, it.queue, it.destinationType, it.routingKey) },
        streams = streamNames.map { StreamSummary(it, plan.vhost, null, 0, null, null, null, null, emptyList(), emptyList()) },
        extraExchanges = declaredExchanges.keys.toList(),
        extraQueues = declaredQueues + declaredStreams,
    )
    // The review graph words a node as the mockup does ("exchange, direct", "queue, quorum", "stream"); the Topology screen keeps its own words.
    val graph = drawn.copy(nodes = drawn.nodes.map { it.copy(node = it.node.copy(detail = subtitleOf(it.node, ::exchangeType, declaredQueueTypes))) })

    val isNew = mutableSetOf<String>()
    val notInPlan = mutableSetOf<String>()
    graph.nodes.map { it.node }.forEach { node ->
        val name = node.rawName()
        val isExchange = node.id.startsWith("e:")
        val onBroker = if (isExchange) name in brokerExchanges else name in brokerQueues || name in brokerStreams
        val declared = if (isExchange) name in declaredExchanges else name in declaredQueues || name in declaredStreams
        when {
            onBroker -> Unit
            declared -> isNew += node.id
            // The broker reserves the names amq.* and has the default exchanges in every virtual host, read or not (O15).
            isExchange && name.startsWith(BUILT_IN_EXCHANGE_PREFIX) -> Unit
            // While the broker has not answered, an end that is not declared may well exist: no verdict yet.
            pending && known == null -> Unit
            else -> notInPlan += node.id
        }
    }

    val routeLabels = mqttRouteLabels(bindings, graph)
    val notice = when {
        graph.isTooLarge -> PlanGraphNotice.TooLarge
        pending && known == null -> null
        known == null -> PlanGraphNotice.OnlyThePlan(onlyThePlanCause)
        else -> null
    }
    return PlanGraph(
        graph = graph,
        newNodeIds = isNew,
        newEdgeIds = graph.edges.map { it.edge.id }.toSet(),
        notInPlanNodeIds = notInPlan,
        links = responseLinks(plan),
        notice = notice,
        edgeLabels = routeLabels,
        mqttRouteEdgeIds = routeLabels.keys,
        notes = planGraphNotes(plan),
    )
}

/**
 * The notes of the review graph: one for every request stream whose response stream is the destination of an MQTT route, standing between the
 * request stream and `amq.topic` (owner decision O13). The pair is recognised in [plan] only (A11), the same rule the summary sentence uses,
 * so the two cannot disagree. A note is not a step, an edge or stored.
 */
fun planGraphNotes(plan: TopologyPlan): List<PlanGraphNote> =
    PlanPairs.requestsAnsweredOverMqtt(plan.steps.map { it.action }).map {
        PlanGraphNote(sourceId = "q:$it", targetId = "e:${MqttRoutes.EXCHANGE}", text = PlannerTexts.SERVICE_NOT_PLANNED)
    }

/**
 * Edge id to topic filter for every drawn edge that is a recognised MQTT route. An edge is the route's binding: same exchange, destination
 * and key (a binding the graph draws once even when the plan holds it twice).
 */
private fun mqttRouteLabels(bindings: List<MigrationAction.DeclareBinding>, graph: BindingGraph): Map<String, String> {
    val routes = bindings.mapNotNull { MqttRoutes.routeOf(it) }
    return graph.edges.map { it.edge }.mapNotNull { edge ->
        val route = routes.firstOrNull { edge.sourceId == "e:${MqttRoutes.EXCHANGE}" && edge.targetId == "q:${it.destination}" && edge.routingKey == it.key }
        route?.let { edge.id to it.filter }
    }.toMap()
}

/** One link per recognised pair, from the request stream to its response stream; a link is not an edge, so it adds no routing. */
private fun responseLinks(plan: TopologyPlan): List<PlanLink> = PlanPairs.responseOf(plan).mapNotNull { (response, request) ->
    val from = (plan.steps.firstOrNull { it.id == request }?.action as? MigrationAction.DeclareStream)?.name
    val to = (plan.steps.firstOrNull { it.id == response }?.action as? MigrationAction.DeclareStream)?.name
    if (from == null || to == null) null else PlanLink("q:$from", "q:$to", PlanLinkKind.ResponseStream)
}.distinct()

private const val BUILT_IN_EXCHANGE_PREFIX = "amq."

/** The type of a default exchange, from its name, for when the broker was not read. Other amq.* names are not guessed. */
private fun builtInExchangeType(name: String): String? = when (name) {
    "amq.topic" -> "topic"
    "amq.direct" -> "direct"
    "amq.fanout" -> "fanout"
    "amq.headers" -> "headers"
    else -> null
}

private fun GraphNode.rawName(): String = id.substring(2)

private fun subtitleOf(node: GraphNode, exchangeType: (String) -> String, queueTypes: Map<String, String>): String {
    val name = node.rawName()
    return when (node.kind) {
        GraphNodeKind.Exchange -> "exchange, ${exchangeType(name)}"
        GraphNodeKind.Stream -> "stream"
        // The broker's queue list carries no type here, so a queue only the broker has is just "queue".
        else -> queueTypes[name]?.let { "queue, $it" } ?: "queue"
    }
}
