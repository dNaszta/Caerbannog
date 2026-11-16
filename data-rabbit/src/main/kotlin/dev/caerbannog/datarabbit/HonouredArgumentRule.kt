package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The two rules of the check (M9-05b) that [DeclaredResourceMatcher] must NOT hold, because the executor's read-back shares the matcher and has
 * to keep passing after a successful first declare. They predict the 400 that a repeated PUT gets from the broker, which the read-back never sees:
 *  - rule 3, [extraOnBroker]: an argument the broker's type HONOURS is on the broker and the declare lacks it;
 *  - rule 4, [otherNumberKind]: a declared honoured number is a whole number where the broker holds a decimal, or the other way round.
 *
 * Measured on RabbitMQ 4.4.0 (M9-00c); any other argument is treated as ignored. Every function is total: a body that is not an object with
 * `arguments` finds nothing.
 */
internal object HonouredArgumentRule {

    /**
     * Copy of the measured sets of the test-support `DeclareEquivalenceTable` (argument names are RabbitMQ knowledge that `core` must not hold, and the
     * table must stay an independent record), pinned to it by `HonouredArgumentRuleTest`. `x-queue-type` is judged by the type rule, not here.
     */
    val queueHonoured: Map<String, Set<String>> = mapOf(
        "quorum" to setOf(
            "x-message-ttl", "x-expires", "x-max-length", "x-max-length-bytes", "x-overflow", "x-dead-letter-exchange", "x-dead-letter-routing-key",
            "x-dead-letter-strategy", "x-delivery-limit", "x-queue-leader-locator", "x-quorum-initial-group-size", "x-single-active-consumer",
            "x-consumer-disconnected-timeout",
        ),
        "classic" to setOf(
            "x-message-ttl", "x-expires", "x-max-length", "x-max-length-bytes", "x-overflow", "x-dead-letter-exchange", "x-dead-letter-routing-key",
            "x-queue-leader-locator", "x-single-active-consumer", "x-max-priority", "x-queue-mode", "x-queue-version",
        ),
        "stream" to setOf(
            "x-max-length-bytes", "x-queue-leader-locator", "x-initial-cluster-size", "x-max-age", "x-stream-max-segment-size-bytes",
            "x-stream-filter-size-bytes",
        ),
    )

    val exchangeHonoured: Set<String> = setOf("alternate-exchange")

    private const val MAX_BODY_DEPTH = 64

    /** Name of the first honoured argument that is on the broker and not declared, or null. */
    fun extraOnBroker(action: MigrationAction, body: String): String? {
        val honoured = honouredFor(action) ?: return null
        val onBroker = brokerArguments(body) ?: return null
        val declared = declaredArguments(action)
        return onBroker.keys.firstOrNull { it in honoured && it !in declared }
    }

    /** Name of the first declared honoured number whose literal kind (whole or decimal) differs from the broker's, or null. */
    fun otherNumberKind(action: MigrationAction, body: String): String? {
        val honoured = honouredFor(action) ?: return null
        val onBroker = brokerArguments(body) ?: return null
        val declared = when (action) {
            is MigrationAction.DeclareQueue -> action.arguments
            is MigrationAction.DeclareExchange -> action.arguments
            else -> return null
        }
        return declared.entries.firstOrNull { (key, value) ->
            key in honoured && isDifferentKind(ArgumentValueJson.encode(value), onBroker[key])
        }?.key
    }

    private fun isDifferentKind(declared: Any?, actual: Any?): Boolean {
        val a = declared as? JsonPrimitive ?: return false
        val b = actual as? JsonPrimitive ?: return false
        if (a.isString || b.isString) return false
        if (a.content.toBigDecimalOrNull() == null || b.content.toBigDecimalOrNull() == null) return false
        return isDecimalLiteral(a.content) != isDecimalLiteral(b.content)
    }

    private fun isDecimalLiteral(text: String): Boolean = text.any { it == '.' || it == 'e' || it == 'E' }

    private fun honouredFor(action: MigrationAction): Set<String>? = when (action) {
        is MigrationAction.DeclareQueue -> queueHonoured[action.type]
        is MigrationAction.DeclareExchange -> exchangeHonoured
        else -> null
    }

    private fun declaredArguments(action: MigrationAction): Set<String> = when (action) {
        is MigrationAction.DeclareQueue -> action.arguments.keys
        is MigrationAction.DeclareExchange -> action.arguments.keys
        else -> emptySet()
    }

    private fun brokerArguments(body: String): JsonObject? {
        val root = (parseUntrustedJson(body, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject ?: return null
        return root["arguments"] as? JsonObject
    }
}
