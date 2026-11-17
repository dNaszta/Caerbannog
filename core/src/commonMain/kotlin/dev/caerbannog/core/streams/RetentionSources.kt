package dev.caerbannog.core.streams

import dev.caerbannog.core.json.ArgumentValue

/**
 * Which retention a stream has when it can come from its own arguments and from the policy that applies to it
 * (M9-08 slice 1). A policy's retention is in `effective_policy_definition`, not in `arguments` (S-F1d).
 */
object RetentionSources {
    /**
     * The policy keys whose policy value beats the argument when both are present. Only `max-length-bytes` is measured (S-14c-4: the policy
     * decides in both directions). `max-age` and `stream-max-segment-size-bytes` are NOT measured: the argument stays; one line to change.
     */
    val policyWinsOverArgument: Set<String> = setOf("max-length-bytes")

    /** Merges the stream's own [arguments] with the retention keys of [policyDefinition]; a value of another type is ignored, never guessed. */
    fun merge(arguments: Retention, policyDefinition: Map<String, ArgumentValue>): Retention {
        val age = (policyDefinition["max-age"] as? ArgumentValue.Text)?.value
        val bytes = (policyDefinition["max-length-bytes"] as? ArgumentValue.Whole)?.value
        val segment = (policyDefinition["stream-max-segment-size-bytes"] as? ArgumentValue.Whole)?.value
        return Retention(
            maxAge = pick("max-age", arguments.maxAge, age),
            maxLengthBytes = pick("max-length-bytes", arguments.maxLengthBytes, bytes),
            segmentSizeBytes = pick("stream-max-segment-size-bytes", arguments.segmentSizeBytes, segment),
        )
    }

    private fun <T : Any> pick(key: String, argument: T?, policy: T?): T? =
        if (key in policyWinsOverArgument) policy ?: argument else argument ?: policy
}
