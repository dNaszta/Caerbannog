package dev.caerbannog.core.migrations

/**
 * The file-level rerun rule: a migration that declares and deletes the same item cannot be rerun safely, because the rerun after a
 * failure would delete what the first run created. An item is a kind, a virtual host and a name; a binding is its virtual host, source,
 * destination and routing key. A policy and an operator policy are different kinds.
 */
internal object RerunSafety {

    /** The refusal reason for the first item a file both declares and deletes, or null when the file is safe. */
    fun conflict(actions: List<MigrationAction>): String? {
        val declared = actions.mapNotNull(::declaredItem).toSet()
        val clash = actions.firstNotNullOfOrNull { action -> deletedItem(action)?.takeIf { it.key in declared } } ?: return null
        val named = clash.name?.takeIf(::echoableName)?.let { " \"$it\"" }.orEmpty()
        return "the file declares and deletes$named; split it into two migrations"
    }

    /** [name] is what the message may show; a binding has no single name. */
    private data class Item(val key: List<String>, val name: String?)

    private fun declaredItem(action: MigrationAction): List<String>? = when (action) {
        is MigrationAction.DeclareQueue -> listOf("queue", action.vhost, action.name)
        is MigrationAction.DeclareExchange -> listOf("exchange", action.vhost, action.name)
        is MigrationAction.DeclareStream -> listOf("stream", action.vhost, action.name)
        is MigrationAction.DeclarePolicy -> listOf("policy", action.vhost, action.name)
        is MigrationAction.DeclareBinding -> listOf("binding", action.vhost, action.exchange, action.queue, action.routingKey)
        else -> null
    }

    private fun deletedItem(action: MigrationAction): Item? = when (action) {
        is MigrationAction.DeleteQueue -> Item(listOf("queue", action.vhost, action.name), action.name)
        is MigrationAction.DeleteExchange -> Item(listOf("exchange", action.vhost, action.name), action.name)
        is MigrationAction.DeleteStream -> Item(listOf("stream", action.vhost, action.name), action.name)
        is MigrationAction.DeletePolicy -> Item(listOf("policy", action.vhost, action.name), action.name)
        is MigrationAction.DeleteBinding ->
            Item(listOf("binding", action.vhost, action.exchange, action.queue, action.routingKey), name = null)
        else -> null
    }
}
