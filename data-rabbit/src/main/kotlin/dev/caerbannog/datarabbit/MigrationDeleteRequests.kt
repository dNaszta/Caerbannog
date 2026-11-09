package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * The management API request of each DELETE action of a migration, built with the same encoding rules as [MigrationRequests]
 * (a dot-only name is written with `%2E`). Pure: it builds, it sends nothing. Streams are deleted over the stream protocol, not here;
 * a binding has no request here until the executor knows its properties key.
 */
internal object MigrationDeleteRequests {

    fun requestFor(action: MigrationAction): ManagementWrite? = when (action) {
        is MigrationAction.DeleteQueue -> delete("queues", action.vhost, action.name)
        is MigrationAction.DeleteExchange -> delete("exchanges", action.vhost, action.name)
        is MigrationAction.DeletePolicy -> delete("policies", action.vhost, action.name)
        else -> null
    }

    private fun delete(collection: String, vhost: String, name: String) =
        ManagementWrite("DELETE", MigrationRequests.collectionPath(collection, vhost, name), null)

    /**
     * The DELETE path of a binding, from the properties key the BROKER reported in its binding list (never a key computed here).
     * MEASURED on 4.x: the broker decodes the segment once and then reads the key out of the decoded text again, so each `%` of the reported key
     * is written as `%25`; sent verbatim, the key deleted the wrong binding or none. `+` and `~` stay as they are.
     */
    fun bindingDeletePath(action: MigrationAction.DeleteBinding, propertiesKey: String): String {
        val list = MigrationRequests.bindingPath(action.vhost, action.exchange, BindingDestinationType.QUEUE, action.queue)
        return "$list/${propertiesKey.replace("%", "%25")}"
    }
}
