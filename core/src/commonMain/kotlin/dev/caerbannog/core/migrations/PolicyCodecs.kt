package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

private const val POLICY_NAME_REQUIRED = "policy name is required"

/** What a policy and an operator policy have in common; both actions are built from it. */
private data class PolicyFields(
    val name: String,
    val pattern: String,
    val definition: Map<String, ArgumentValue>,
    val vhost: String,
    val applyTo: String,
    val priority: Int,
)

private fun readPolicy(entry: JsonObject, create: (PolicyFields) -> MigrationAction): ActionReadResult {
    val name = entry.identifier("name")
    val definition = entry.typedEntries("definition")
    val applyTo = entry.applyTo()
    val priority = entry.priority()
    return when {
        name.isNullOrBlank() -> refused(POLICY_NAME_REQUIRED)
        definition == null -> refused(ARGUMENTS_INVALID)
        applyTo == null -> refused(APPLY_TO_UNSUPPORTED)
        priority == null -> refused(PRIORITY_NOT_WHOLE)
        else -> accepted(create(PolicyFields(name, entry.string("pattern") ?: ".*", definition, entry.vhost(), applyTo, priority)))
    }
}

private fun writePolicy(out: JsonObjectBuilder, name: String, pattern: String, definition: Map<String, ArgumentValue>, applyTo: String, priority: Int) {
    out.putText("name", name)
    out.putText("pattern", pattern)
    out.putTyped("definition", definition)
    if (applyTo != "all") out.putText("applyTo", applyTo)
    if (priority != 0) out.put("priority", JsonPrimitive(priority))
}

internal val declarePolicyCodec = codec<MigrationAction.DeclarePolicy>(
    name = "declarePolicy",
    read = { entry ->
        readPolicy(entry) { MigrationAction.DeclarePolicy(it.name, it.pattern, it.definition, it.vhost, it.applyTo, it.priority) }
    },
    write = { action, out -> writePolicy(out, action.name, action.pattern, action.definition, action.applyTo, action.priority) },
)

internal val declareOperatorPolicyCodec = codec<MigrationAction.DeclareOperatorPolicy>(
    name = "declareOperatorPolicy",
    read = { entry ->
        readPolicy(entry) { MigrationAction.DeclareOperatorPolicy(it.name, it.pattern, it.definition, it.vhost, it.applyTo, it.priority) }
    },
    write = { action, out -> writePolicy(out, action.name, action.pattern, action.definition, action.applyTo, action.priority) },
)
