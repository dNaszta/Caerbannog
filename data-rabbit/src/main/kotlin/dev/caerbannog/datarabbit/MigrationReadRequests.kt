package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * The GET that reads back the resource an action is about (declare or delete alike): the item itself for a queue, stream, exchange, policy
 * and operator policy, the list of the bindings between its source and destination for a binding. Always a plain GET of an allowed shape;
 * never a path under a queue's name.
 */
internal object MigrationReadRequests {

    fun pathFor(action: MigrationAction): String = when (action) {
        is MigrationAction.DeclareQueue -> item("queues", action.vhost, action.name)
        is MigrationAction.DeleteQueue -> item("queues", action.vhost, action.name)
        is MigrationAction.DeclareStream -> item("queues", action.vhost, action.name)
        is MigrationAction.DeleteStream -> item("queues", action.vhost, action.name)
        is MigrationAction.DeclareExchange -> item("exchanges", action.vhost, action.name)
        is MigrationAction.DeleteExchange -> item("exchanges", action.vhost, action.name)
        is MigrationAction.DeclarePolicy -> item("policies", action.vhost, action.name)
        is MigrationAction.DeletePolicy -> item("policies", action.vhost, action.name)
        is MigrationAction.DeclareOperatorPolicy -> item("operator-policies", action.vhost, action.name)
        is MigrationAction.DeclareBinding ->
            MigrationRequests.bindingPath(action.vhost, action.exchange, action.destinationType, action.queue)
        is MigrationAction.DeleteBinding ->
            MigrationRequests.bindingPath(action.vhost, action.exchange, BindingDestinationType.QUEUE, action.queue)
    }

    private fun item(collection: String, vhost: String, name: String) = MigrationRequests.collectionPath(collection, vhost, name)
}
