package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingGraph
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.GraphNode
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
    val declaredQueues = actions.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }
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
    val graph = buildBindingGraph(
        exchanges = exchangeNames.map { name ->
            ExchangeRow(name, plan.vhost, brokerExchanges[name] ?: declaredExchanges[name] ?: "direct", durable = true, autoDelete = false, internal = false)
        },
        queues = emptyList(),
        bindings = bindings.map { BindingRow(it.exchange, plan.vhost, it.queue, it.destinationType, it.routingKey) },
        streams = streamNames.map { StreamSummary(it, plan.vhost, null, 0, null, null, null, null, emptyList(), emptyList()) },
        extraExchanges = declaredExchanges.keys.toList(),
        extraQueues = declaredQueues + declaredStreams,
    )

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
            // While the broker has not answered, an end that is not declared may well exist: no verdict yet.
            pending && known == null -> Unit
            else -> notInPlan += node.id
        }
    }

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
        notice = notice,
    )
}

private fun GraphNode.rawName(): String = id.substring(2)
