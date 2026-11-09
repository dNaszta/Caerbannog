package dev.caerbannog.core.migrate3x

/** Where a [DefinitionsDocument] came from. */
sealed interface DefinitionsOrigin {
    /** A whole-broker export. */
    data object FullExport : DefinitionsOrigin

    /**
     * A per-virtual-host export whose virtual host the user named. The lists `virtualHosts`, `users`,
     * `permissions` and `topicPermissions` of the document are always empty (such an export holds none of them).
     */
    data class SingleVirtualHost(val assignedName: String) : DefinitionsOrigin
}

enum class BindingTarget { Queue, Exchange }

sealed interface QueueKind {
    data object Classic : QueueKind
    data object Quorum : QueueKind
    data object Stream : QueueKind

    /** A type name this app does not know. */
    data class Other(val name: String) : QueueKind
}

data class VirtualHostDefinition(val name: String, val description: String, val tags: List<String>, val defaultQueueType: String?)

data class QueueDefinition(
    val vhost: String,
    val name: String,
    val durable: Boolean = true,
    val autoDelete: Boolean = false,
    val type: QueueKind,
    val arguments: Map<String, ArgumentValue>,
)

data class ExchangeDefinition(
    val vhost: String,
    val name: String,
    val type: String,
    val durable: Boolean = true,
    val autoDelete: Boolean = false,
    val internal: Boolean = false,
    val arguments: Map<String, ArgumentValue>,
)

data class BindingDefinition(
    val vhost: String,
    val source: String,
    val destination: String,
    val destinationKind: BindingTarget,
    val routingKey: String = "",
    val arguments: Map<String, ArgumentValue>,
)

data class PolicyDefinition(
    val vhost: String,
    val name: String,
    val pattern: String,
    val applyTo: String = "all",
    val priority: Int = 0,
    val definition: Map<String, ArgumentValue>,
)

data class OperatorPolicyDefinition(
    val vhost: String,
    val name: String,
    val pattern: String,
    val applyTo: String = "all",
    val priority: Int = 0,
    val definition: Map<String, ArgumentValue>,
)

/** A user of the export. The password hash is a [SecretValue]; no `String` field holds it. */
data class UserDefinition(
    val name: String,
    val passwordHash: SecretValue?,
    val hashingAlgorithm: String?,
    val tags: List<String>,
    val limits: Map<String, ArgumentValue>,
)

data class PermissionDefinition(val vhost: String, val user: String, val configure: String, val read: String, val write: String)

data class TopicPermissionDefinition(val vhost: String, val user: String, val exchange: String, val write: String, val read: String)

/**
 * A parameter this model does not interpret (shovel, federation, global). It has no value field on purpose:
 * parameter values can carry clear passwords and are never read into the model. [vhost] is null for global parameters.
 */
data class UnparsedParameter(val vhost: String?, val component: String, val name: String)

/** A definitions export, read into typed immutable data. Holds no reference to the text or JSON tree it came from. */
data class DefinitionsDocument(
    val sourceVersion: String?,
    val virtualHosts: List<VirtualHostDefinition>,
    val queues: List<QueueDefinition>,
    val exchanges: List<ExchangeDefinition>,
    val bindings: List<BindingDefinition>,
    val policies: List<PolicyDefinition>,
    val operatorPolicies: List<OperatorPolicyDefinition>,
    val users: List<UserDefinition>,
    val permissions: List<PermissionDefinition>,
    val topicPermissions: List<TopicPermissionDefinition>,
    val otherParameters: List<UnparsedParameter>,
    val origin: DefinitionsOrigin = DefinitionsOrigin.FullExport,
)
