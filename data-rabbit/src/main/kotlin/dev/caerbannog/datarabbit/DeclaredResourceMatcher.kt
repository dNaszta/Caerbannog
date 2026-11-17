package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Judges what the broker answered for a resource against what the action declared. Only what the action declared counts: arguments the
 * broker added are ignored, and every declared one must be there with the same type and value (see [TypedJson]). A body that is not the
 * expected shape does not match; it never throws.
 */
internal object DeclaredResourceMatcher {

    /** A queue or exchange detail is a shallow object; far below the reader's failure point. */
    private const val MAX_BODY_DEPTH = 64

    fun matches(action: MigrationAction, body: String): Boolean {
        if (action is MigrationAction.DeclarePolicy || action is MigrationAction.DeclareOperatorPolicy) {
            return ConflictCheck.comparePolicy(body, action) == PolicyComparison.SAME
        }
        val root = (parseUntrustedJson(body, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element ?: return false
        return when (action) {
            is MigrationAction.DeclareQueue -> root is JsonObject && queueMatches(action, root)
            is MigrationAction.DeclareExchange -> root is JsonObject && exchangeMatches(action, root)
            is MigrationAction.DeclareStream -> root is JsonObject && root.text("type") == "stream" && argumentsContained(action.arguments, root["arguments"])
            is MigrationAction.DeclareBinding -> root is JsonArray && root.any { it is JsonObject && bindingMatches(action, it) }
            else -> false
        }
    }

    private fun queueMatches(action: MigrationAction.DeclareQueue, root: JsonObject): Boolean =
        root.text("type") == action.type &&
            root.flag("durable") == action.durable &&
            root.flag("auto_delete") == action.autoDelete &&
            argumentsContained(action.arguments, root["arguments"])

    private fun exchangeMatches(action: MigrationAction.DeclareExchange, root: JsonObject): Boolean =
        root.text("type") == action.type &&
            root.flag("durable") == action.durable &&
            root.flag("auto_delete") == action.autoDelete &&
            root.flag("internal") == action.internal &&
            argumentsContained(action.arguments, root["arguments"])

    /** A binding is its routing key and its arguments: both must be exactly the declared ones. */
    private fun bindingMatches(action: MigrationAction.DeclareBinding, binding: JsonObject): Boolean =
        binding.text("routing_key") == action.routingKey &&
            (binding["arguments"] ?: JsonObject(emptyMap())).let { TypedJson.equal(it, ArgumentValueJson.encode(ArgumentValue.Table(action.arguments))) }

    private fun argumentsContained(declared: Map<String, ArgumentValue>, actual: JsonElement?): Boolean {
        if (declared.isEmpty()) return true
        val arguments = actual as? JsonObject ?: return false
        return declared.all { (key, value) -> arguments[key]?.let { TypedJson.equal(it, ArgumentValueJson.encode(value)) } == true }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
