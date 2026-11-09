package dev.caerbannog.core.migrate3x

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts the JSON tree of a definitions export into the typed model, section by section.
 * Throws [MalformedSectionException] (caught by [DefinitionsParser]) when a section is not shaped as expected.
 */
internal class DefinitionsReader(private val root: JsonObject) {

    private val scoped: Map<String, List<EntryReader>> = SCOPED_SECTIONS.associateWith { entries(it) }
    private val users = entries("users")
    private val virtualHosts = entries("vhosts")
    private val permissions = entries("permissions")
    private val topicPermissions = entries("topic_permissions")
    private val globalParameters = entries("global_parameters")

    fun read(): DefinitionsParseResult {
        val firstVhostless = SCOPED_SECTIONS.firstOrNull { section -> scoped.getValue(section).any { !it.has("vhost") } }
        return when {
            firstVhostless == null -> DefinitionsParseResult.Parsed(document(vhostless = false))
            isMixedOrHasAccessSections() -> throw MalformedSectionException(firstVhostless)
            else -> DefinitionsParseResult.NeedsVirtualHost(UnassignedDefinitions(document(vhostless = true)))
        }
    }

    private fun isMixedOrHasAccessSections(): Boolean =
        scoped.values.any { section -> section.any { it.has("vhost") } } ||
            (users + virtualHosts + permissions + topicPermissions).isNotEmpty()

    /** In a per-virtual-host export entries carry no vhost; they get [UNASSIGNED] until the user names it. */
    private fun document(vhostless: Boolean): DefinitionsDocument {
        fun EntryReader.vhost(): String = if (vhostless) UNASSIGNED else text("vhost")
        val parameters = scoped.getValue("parameters")
        val (operatorPolicies, others) = parameters.partition { it.text("component") == OPERATOR_POLICY }
        return DefinitionsDocument(
            sourceVersion = sourceVersion(),
            virtualHosts = virtualHosts.map(::virtualHost),
            queues = scoped.getValue("queues").map { queue(it, it.vhost()) },
            exchanges = scoped.getValue("exchanges").map { exchange(it, it.vhost()) },
            bindings = scoped.getValue("bindings").map { binding(it, it.vhost()) },
            policies = scoped.getValue("policies").map { policy(it, it.vhost()) },
            operatorPolicies = operatorPolicies.map { operatorPolicy(it, it.vhost()) },
            users = users.map(::user),
            permissions = permissions.map(::permission),
            topicPermissions = topicPermissions.map(::topicPermission),
            otherParameters = others.map { UnparsedParameter(it.vhost(), it.text("component"), it.text("name")) } +
                globalParameters.map { UnparsedParameter(null, GLOBAL, it.text("name")) },
        )
    }

    private fun entries(section: String): List<EntryReader> = when (val value = root[section]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.map { EntryReader(section, it as? JsonObject ?: throw MalformedSectionException(section)) }
        else -> throw MalformedSectionException(section)
    }

    private fun sourceVersion(): String? = VERSION_KEYS.firstNotNullOfOrNull { key ->
        (root[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }

    private fun virtualHost(entry: EntryReader): VirtualHostDefinition {
        val metadata = entry.objectOrNull("metadata")
        return VirtualHostDefinition(
            name = entry.text("name"),
            description = metadata?.textOrNull("description") ?: entry.text("description", ""),
            tags = metadata?.tagsOrNull("tags") ?: entry.tagsOrNull("tags") ?: emptyList(),
            defaultQueueType = metadata?.textOrNull("default_queue_type") ?: entry.textOrNull("default_queue_type"),
        )
    }

    private fun queue(entry: EntryReader, vhost: String): QueueDefinition {
        val arguments = entry.arguments("arguments")
        val typeName = entry.textOrNull("type") ?: (arguments["x-queue-type"] as? ArgumentValue.Text)?.value
        return QueueDefinition(
            vhost = vhost,
            name = entry.text("name"),
            durable = entry.flag("durable", true),
            autoDelete = entry.flag("auto_delete", false),
            type = queueKind(typeName),
            arguments = arguments,
        )
    }

    private fun queueKind(name: String?): QueueKind = when (name) {
        null, "classic" -> QueueKind.Classic
        "quorum" -> QueueKind.Quorum
        "stream" -> QueueKind.Stream
        else -> QueueKind.Other(name)
    }

    private fun exchange(entry: EntryReader, vhost: String) = ExchangeDefinition(
        vhost = vhost,
        name = entry.text("name"),
        type = entry.text("type"),
        durable = entry.flag("durable", true),
        autoDelete = entry.flag("auto_delete", false),
        internal = entry.flag("internal", false),
        arguments = entry.arguments("arguments"),
    )

    private fun binding(entry: EntryReader, vhost: String) = BindingDefinition(
        vhost = vhost,
        source = entry.text("source"),
        destination = entry.text("destination"),
        destinationKind = when (entry.text("destination_type")) {
            "queue" -> BindingTarget.Queue
            "exchange" -> BindingTarget.Exchange
            else -> entry.malformed()
        },
        routingKey = entry.text("routing_key", ""),
        arguments = entry.arguments("arguments"),
    )

    private fun policy(entry: EntryReader, vhost: String) = PolicyDefinition(
        vhost = vhost,
        name = entry.text("name"),
        pattern = entry.text("pattern"),
        applyTo = entry.text("apply-to", "all"),
        priority = entry.whole("priority", 0),
        definition = entry.arguments("definition"),
    )

    /** An operator policy is a parameter entry; only its typed fields are read from `value`. */
    private fun operatorPolicy(entry: EntryReader, vhost: String): OperatorPolicyDefinition {
        val value = entry.objectOrNull("value") ?: entry.malformed()
        return OperatorPolicyDefinition(
            vhost = vhost,
            name = entry.text("name"),
            pattern = value.text("pattern"),
            applyTo = value.text("apply-to", "all"),
            priority = value.whole("priority", 0),
            definition = value.arguments("definition"),
        )
    }

    private fun user(entry: EntryReader) = UserDefinition(
        name = entry.text("name"),
        passwordHash = entry.textOrNull("password_hash")?.let(SecretValue::of),
        hashingAlgorithm = entry.textOrNull("hashing_algorithm"),
        tags = entry.tagsOrNull("tags") ?: emptyList(),
        limits = entry.arguments("limits"),
    )

    private fun permission(entry: EntryReader) = PermissionDefinition(
        vhost = entry.text("vhost"),
        user = entry.text("user"),
        configure = entry.text("configure"),
        read = entry.text("read"),
        write = entry.text("write"),
    )

    private fun topicPermission(entry: EntryReader) = TopicPermissionDefinition(
        vhost = entry.text("vhost"),
        user = entry.text("user"),
        exchange = entry.text("exchange"),
        write = entry.text("write"),
        read = entry.text("read"),
    )

    private companion object {
        val SCOPED_SECTIONS = listOf("queues", "exchanges", "bindings", "policies", "parameters")
        val VERSION_KEYS = listOf("rabbitmq_version", "rabbit_version", "product_version")
        const val OPERATOR_POLICY = "operator_policy"
        const val GLOBAL = "global"
        const val UNASSIGNED = ""
    }
}
