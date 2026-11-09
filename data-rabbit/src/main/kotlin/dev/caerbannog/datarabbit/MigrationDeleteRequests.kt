package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * The management API request of each DELETE action of a migration, built with the same encoding rules as [MigrationRequests]
 * (a dot-only name is written with `%2E`). Pure: it builds, it sends nothing. Streams are deleted over the stream protocol, not here.
 */
internal object MigrationDeleteRequests {

    /** The binding's properties key: `~` stands for an empty routing key (the form the management API documents, measured by the live test). */
    private const val EMPTY_ROUTING_KEY = "~"

    fun requestFor(action: MigrationAction): ManagementWrite? = when (action) {
        is MigrationAction.DeleteQueue -> delete("queues", action.vhost, action.name)
        is MigrationAction.DeleteExchange -> delete("exchanges", action.vhost, action.name)
        is MigrationAction.DeletePolicy -> delete("policies", action.vhost, action.name)
        is MigrationAction.DeleteBinding -> ManagementWrite("DELETE", bindingDeletePath(action), null)
        else -> null
    }

    private fun delete(collection: String, vhost: String, name: String) =
        ManagementWrite("DELETE", MigrationRequests.collectionPath(collection, vhost, name), null)

    private fun bindingDeletePath(action: MigrationAction.DeleteBinding): String {
        val list = MigrationRequests.bindingPath(action.vhost, action.exchange, BindingDestinationType.QUEUE, action.queue)
        val key = if (action.routingKey.isEmpty()) EMPTY_ROUTING_KEY else MigrationRequests.encode(action.routingKey)
        return "$list/$key"
    }
}
