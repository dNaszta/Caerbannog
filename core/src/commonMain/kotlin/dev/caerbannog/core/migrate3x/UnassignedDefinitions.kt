package dev.caerbannog.core.migrate3x

/** Result of [UnassignedDefinitions.assignVirtualHost]. */
sealed interface VirtualHostAssignment {
    data class Assigned(val document: DefinitionsDocument) : VirtualHostAssignment

    /** [reason] is a fixed text and never echoes the typed name. */
    data class InvalidName(val reason: String) : VirtualHostAssignment
}

/**
 * A per-virtual-host export: its entries carry no virtual host name. It is deliberately not a [DefinitionsDocument]
 * and exposes no entity list, so it cannot be analysed before the user names the virtual host.
 */
class UnassignedDefinitions internal constructor(private val content: DefinitionsDocument) {
    val sourceVersion: String? get() = content.sourceVersion
    val queueCount: Int get() = content.queues.size
    val exchangeCount: Int get() = content.exchanges.size
    val bindingCount: Int get() = content.bindings.size
    val policyCount: Int get() = content.policies.size

    /**
     * Puts [name] on every entry. Only blank names and control characters are refused: RabbitMQ's own naming
     * rules are not verified, so none are invented here. The access lists of the result stay empty.
     */
    fun assignVirtualHost(name: String): VirtualHostAssignment = when {
        name.isBlank() -> VirtualHostAssignment.InvalidName("The virtual host name must not be empty")
        name.any { it.isISOControl() } -> VirtualHostAssignment.InvalidName("The virtual host name must not contain control characters")
        else -> VirtualHostAssignment.Assigned(withVirtualHost(name))
    }

    private fun withVirtualHost(name: String): DefinitionsDocument = content.copy(
        queues = content.queues.map { it.copy(vhost = name) },
        exchanges = content.exchanges.map { it.copy(vhost = name) },
        bindings = content.bindings.map { it.copy(vhost = name) },
        policies = content.policies.map { it.copy(vhost = name) },
        operatorPolicies = content.operatorPolicies.map { it.copy(vhost = name) },
        otherParameters = content.otherParameters.map { if (it.vhost == null) it else it.copy(vhost = name) },
        origin = DefinitionsOrigin.SingleVirtualHost(name),
    )
}
