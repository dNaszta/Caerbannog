package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow

/**
 * Fake implementation of [TopologyMigrationExecutor] for BDD and unit tests.
 * Directly applies changes to [FakeTopologyCatalog] and/or [FakeStreamCatalog].
 */
class FakeTopologyMigrationExecutor(
    private val topologyCatalog: FakeTopologyCatalog? = null,
    private val streamCatalog: FakeStreamCatalog? = null,
) : TopologyMigrationExecutor {

    val executedActions = mutableListOf<MigrationAction>()

    // M8-04: what the broker would hold, keyed by (vhost, name), so tests can assert typed values. Read-only views.
    private val queues = linkedMapOf<Pair<String, String>, MigrationAction.DeclareQueue>()
    private val policies = linkedMapOf<Pair<String, String>, MigrationAction.DeclarePolicy>()
    private val operatorPolicies = linkedMapOf<Pair<String, String>, MigrationAction.DeclareOperatorPolicy>()
    val declaredQueues: Map<Pair<String, String>, MigrationAction.DeclareQueue> get() = queues
    val declaredPolicies: Map<Pair<String, String>, MigrationAction.DeclarePolicy> get() = policies
    val declaredOperatorPolicies: Map<Pair<String, String>, MigrationAction.DeclareOperatorPolicy> get() = operatorPolicies
    var failingActionIndex: Int? = null
    var failingActionName: String? = null
    var failureReason: String = "Action refused by broker"

    override suspend fun execute(action: MigrationAction): ActionResult {
        val currentIndex = executedActions.size
        executedActions += action

        if (failingActionIndex == currentIndex || action.actionName == failingActionName) {
            return ActionResult.Failed(failureReason)
        }

        // Apply changes to fake catalogs idempotently
        when (action) {
            is MigrationAction.DeclareQueue -> {
                queues[action.vhost to action.name] = action
                topologyCatalog?.addQueue(
                    QueueRow(
                        name = action.name,
                        vhost = action.vhost,
                        type = action.type,
                        readyMessages = 0,
                        unackedMessages = 0,
                        consumers = 0,
                        leader = "rabbit@node-1",
                        members = listOf("rabbit@node-1"),
                        online = listOf("rabbit@node-1"),
                        policy = null,
                        state = "running",
                    )
                )
            }

            is MigrationAction.DeleteQueue -> {
                topologyCatalog?.deleteQueue(action.name, action.vhost)
            }

            is MigrationAction.DeclareExchange -> {
                topologyCatalog?.addExchange(
                    ExchangeRow(
                        name = action.name,
                        vhost = action.vhost,
                        type = action.type,
                        durable = action.durable,
                        autoDelete = action.autoDelete,
                        internal = action.internal,
                    )
                )
            }

            is MigrationAction.DeleteExchange -> {
                topologyCatalog?.deleteExchange(action.name, action.vhost)
            }

            is MigrationAction.DeclareBinding -> {
                topologyCatalog?.addBinding(
                    BindingRow(
                        source = action.exchange,
                        vhost = action.vhost,
                        destination = action.queue,
                        destinationType = action.destinationType,
                        routingKey = action.routingKey,
                    )
                )
            }

            is MigrationAction.DeleteBinding -> {
                topologyCatalog?.deleteBinding(action.exchange, action.queue, action.routingKey, action.vhost)
            }

            is MigrationAction.DeclareStream -> {
                topologyCatalog?.addStream(action.name, action.vhost)
                streamCatalog?.addStream(FakeStreamCatalog.stream(action.name, vhost = action.vhost))
            }

            is MigrationAction.DeleteStream -> {
                topologyCatalog?.deleteQueue(action.name, action.vhost)
                streamCatalog?.deleteStream(action.name, action.vhost)
            }

            is MigrationAction.DeclarePolicy -> policies[action.vhost to action.name] = action
            is MigrationAction.DeclareOperatorPolicy -> operatorPolicies[action.vhost to action.name] = action
            is MigrationAction.DeletePolicy -> policies.remove(action.vhost to action.name)
        }

        return ActionResult.Success
    }

    fun clear() {
        executedActions.clear()
        queues.clear(); policies.clear(); operatorPolicies.clear()
        failingActionIndex = null
        failingActionName = null
    }
}
