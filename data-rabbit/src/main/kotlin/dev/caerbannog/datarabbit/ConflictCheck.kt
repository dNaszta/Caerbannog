package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class PolicyComparison { SAME, DIFFERENT }

/**
 * The conflict rule for a policy that already exists: it is compared BEFORE any PUT, and a PUT would overwrite it. Same pattern, apply-to,
 * priority and every definition entry (typed, numbers by value, see [TypedJson]) is SAME. Everything else is DIFFERENT, including a body that
 * cannot be read: what cannot be compared is never overwritten.
 */
internal object ConflictCheck {

    /** A broker policy is a small flat object; far below the reader's failure point. */
    private const val MAX_BODY_DEPTH = 64

    /** [existingBody] is the broker's JSON of the policy or operator policy; [wanted] is a `DeclarePolicy` or `DeclareOperatorPolicy`. */
    fun comparePolicy(existingBody: String, wanted: MigrationAction): PolicyComparison {
        val expected = expectedPolicy(wanted)
        val existing = (parseUntrustedJson(existingBody, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject
            ?: return PolicyComparison.DIFFERENT
        val same = expected.all { (key, value) -> existing[key]?.let { TypedJson.equal(it, value) } == true }
        return if (same) PolicyComparison.SAME else PolicyComparison.DIFFERENT
    }

    /** The four fields that make a policy what it is, in the JSON form the broker uses. */
    private fun expectedPolicy(wanted: MigrationAction): JsonObject = when (wanted) {
        is MigrationAction.DeclarePolicy -> fields(wanted.pattern, wanted.applyTo, wanted.priority, wanted.definition)
        is MigrationAction.DeclareOperatorPolicy -> fields(wanted.pattern, wanted.applyTo, wanted.priority, wanted.definition)
        else -> throw IllegalArgumentException("only a policy or an operator policy can be compared")
    }

    private fun fields(pattern: String, applyTo: String, priority: Int, definition: Map<String, ArgumentValue>) = JsonObject(
        mapOf(
            "pattern" to JsonPrimitive(pattern),
            "apply-to" to JsonPrimitive(applyTo),
            "priority" to JsonPrimitive(priority),
            "definition" to ArgumentValueJson.encode(ArgumentValue.Table(definition)),
        ),
    )
}
