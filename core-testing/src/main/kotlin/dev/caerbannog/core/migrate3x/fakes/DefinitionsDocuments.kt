package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrate3x.BindingDefinition
import dev.caerbannog.core.migrate3x.BindingTarget
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.DefinitionsOrigin
import dev.caerbannog.core.migrate3x.ExchangeDefinition
import dev.caerbannog.core.migrate3x.LiveSourceFacts
import dev.caerbannog.core.migrate3x.OperatorPolicyDefinition
import dev.caerbannog.core.migrate3x.PermissionDefinition
import dev.caerbannog.core.migrate3x.PolicyDefinition
import dev.caerbannog.core.migrate3x.QueueDefinition
import dev.caerbannog.core.migrate3x.QueueKind
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.SecretValue
import dev.caerbannog.core.migrate3x.TopicPermissionDefinition
import dev.caerbannog.core.migrate3x.UnparsedParameter
import dev.caerbannog.core.migrate3x.UserDefinition
import dev.caerbannog.core.migrate3x.VirtualHostDefinition

/**
 * M8-03: hand-made [DefinitionsDocument]s for analyzer tests and scenarios, so no JSON text is needed. Defaults describe a plain
 * durable queue / sha256 user. Password hashes are SENTINEL values only. Lives in core-testing; never used by production code.
 */
object DefinitionsDocuments {
    const val SHA256: String = "rabbit_password_hashing_sha256"

    /** Maps plain values to typed arguments: Int/Long -> Whole, Double -> Decimal, Boolean -> Flag, String -> Text, ArgumentValue as is. */
    fun args(vararg pairs: Pair<String, Any>): Map<String, ArgumentValue> = pairs.associate { (k, v) -> k to value(v) }

    fun value(v: Any): ArgumentValue = when (v) {
        is ArgumentValue -> v
        is Int -> ArgumentValue.Whole(v.toLong())
        is Long -> ArgumentValue.Whole(v)
        is Double -> ArgumentValue.Decimal(v)
        is Boolean -> ArgumentValue.Flag(v)
        is String -> ArgumentValue.Text(v)
        else -> error("unsupported argument value ${v::class}")
    }

    fun document(
        queues: List<QueueDefinition> = emptyList(),
        exchanges: List<ExchangeDefinition> = emptyList(),
        bindings: List<BindingDefinition> = emptyList(),
        policies: List<PolicyDefinition> = emptyList(),
        operatorPolicies: List<OperatorPolicyDefinition> = emptyList(),
        virtualHosts: List<VirtualHostDefinition> = emptyList(),
        users: List<UserDefinition> = emptyList(),
        permissions: List<PermissionDefinition> = emptyList(),
        topicPermissions: List<TopicPermissionDefinition> = emptyList(),
        otherParameters: List<UnparsedParameter> = emptyList(),
        sourceVersion: String? = "3.13.7",
        origin: DefinitionsOrigin = DefinitionsOrigin.FullExport,
    ): DefinitionsDocument = DefinitionsDocument(
        sourceVersion, virtualHosts, queues, exchanges, bindings, policies, operatorPolicies, users, permissions, topicPermissions,
        otherParameters, origin,
    )

    fun queue(
        name: String,
        type: QueueKind = QueueKind.Classic,
        vhost: String = "/",
        durable: Boolean = true,
        autoDelete: Boolean = false,
        arguments: Map<String, ArgumentValue> = emptyMap(),
    ) = QueueDefinition(vhost, name, durable, autoDelete, type, arguments)

    fun classicQueue(name: String, vhost: String = "/", durable: Boolean = true, autoDelete: Boolean = false, arguments: Map<String, ArgumentValue> = emptyMap()) =
        queue(name, QueueKind.Classic, vhost, durable, autoDelete, arguments)

    fun quorumQueue(name: String, vhost: String = "/", arguments: Map<String, ArgumentValue> = emptyMap()) =
        queue(name, QueueKind.Quorum, vhost, arguments = arguments)

    fun streamQueue(name: String, vhost: String = "/") = queue(name, QueueKind.Stream, vhost)

    fun exchange(name: String, type: String = "direct", vhost: String = "/") = ExchangeDefinition(vhost, name, type, arguments = emptyMap())

    fun binding(source: String, destination: String, routingKey: String = "", vhost: String = "/", target: BindingTarget = BindingTarget.Queue) =
        BindingDefinition(vhost, source, destination, target, routingKey, emptyMap())

    fun policy(name: String, pattern: String, definition: Map<String, ArgumentValue>, applyTo: String = "all", priority: Int = 0, vhost: String = "/") =
        PolicyDefinition(vhost, name, pattern, applyTo, priority, definition)

    fun operatorPolicy(name: String, pattern: String, definition: Map<String, ArgumentValue>, applyTo: String = "all", priority: Int = 0, vhost: String = "/") =
        OperatorPolicyDefinition(vhost, name, pattern, applyTo, priority, definition)

    fun virtualHost(name: String) = VirtualHostDefinition(name, "", emptyList(), null)

    /** [hash] null means "no password hash in the export"; the default is a SENTINEL value that must never appear in any report. */
    fun user(name: String, hash: String? = "SENTINEL-HASH-$name", algorithm: String? = SHA256, tags: List<String> = emptyList()) =
        UserDefinition(name, hash?.let { SecretValue.of(it) }, algorithm, tags, emptyMap())

    fun permission(vhost: String, user: String) = PermissionDefinition(vhost, user, ".*", ".*", ".*")

    fun topicPermission(vhost: String, user: String, exchange: String) = TopicPermissionDefinition(vhost, user, exchange, ".*", ".*")

    fun parameter(component: String, name: String, vhost: String? = "/") = UnparsedParameter(vhost, component, name)

    /** The fake facts of a live 3.x connection (what M8-02 will fill). A classic queue missing from [indexVersions] counts as Unknown. */
    fun liveFacts(
        connectionName: String = "legacy-eu",
        brokerVersion: String? = "3.13.7",
        indexVersions: Map<QueueRef, ClassicIndexVersion> = emptyMap(),
        deprecatedFeatures: Set<String> = emptySet(),
    ) = LiveSourceFacts(connectionName, brokerVersion, indexVersions, deprecatedFeatures)
}
