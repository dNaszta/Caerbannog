package dev.caerbannog.core.access

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place the redaction rules of the Access view live (spec "Access view (M10)", "Redaction rules"); the gateways, the UI, the logs and the MCP
 * server call it, so a later screen or tool cannot forget a rule. Pure and stateless: the held secret is a parameter of every call, is never kept,
 * logged or put in a result, and the guard prints and logs nothing.
 *
 * - [redact]: a value that matches a secret shape (JWT, `Bearer `, `scheme://user:password@`, `password=`, `secret=`, `token=`, case-insensitive) or
 *   holds the held secret is replaced WHOLE by [HIDDEN]. A held secret of [MIN_HELD_SEGMENT] characters or more also hides a value that holds any window
 *   of that length of it; a shorter one hides only an equal value (a short window would hide ordinary names).
 * - [guardConnection] / [guardChannel]: an allow-list, not a deny-list. Everything outside it is dropped (a client property `password` comes back
 *   verbatim from the broker), a null or non-scalar value is dropped, every kept text is scanned.
 */
object SecretGuard {
    const val HIDDEN: String = "hidden"

    /** A value holding a substring of the held secret of at least this many characters is hidden (the property test of the brief: no segment longer than 6 survives). */
    const val MIN_HELD_SEGMENT: Int = 7

    /** The fixed text of every unmapped broker error (rule 5). */
    const val GENERIC_ERROR: String = "The broker gave an error that Caerbannog does not show."

    /** The mapped reasons of the broker's `reason` texts that are safe to show. */
    const val REASON_NO_MANAGEMENT_TAG: String = "The management tag is needed."
    const val REASON_REFUSED: String = "The broker refused the sign-in."

    private val shapes: List<Regex> = listOf(
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"),
        Regex("\\bbearer\\s+\\S", RegexOption.IGNORE_CASE),
        Regex("[A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@"),
        Regex("(password|secret|token)=", RegexOption.IGNORE_CASE),
    )

    private val connectionFields: List<String> = listOf(
        "name", "user", "vhost", "node", "state", "protocol", "peer_host", "peer_port", "connected_at", "channels", "auth_mechanism", "ssl",
        "user_provided_name",
    )
    private val clientProperties: List<String> = listOf("product", "version", "platform", "connection_name")
    private val channelFields: List<String> = listOf("name", "user", "vhost", "node", "state")
    private val channelDetails: List<String> = listOf("name", "peer_host", "peer_port")

    /** Hides [value] whole when it matches a shape or holds [heldSecret]; see the class comment. */
    fun redact(value: String, heldSecret: String? = null): GuardedText =
        if (isSecret(value, heldSecret)) GuardedText(HIDDEN, hidden = true) else GuardedText(value, hidden = false)

    /** [redact] for each value, order kept, with the count of replaced values. */
    fun redactAll(values: Collection<String>, heldSecret: String? = null): GuardedTexts {
        val redacted = values.map { redact(it, heldSecret) }
        return GuardedTexts(redacted.map { it.text }, redacted.count { it.hidden })
    }

    /** A connection record of the management API reduced to the allow-list, flattened as `client_properties.<name>`. */
    fun guardConnection(record: JsonObject, heldSecret: String? = null): GuardedRecord {
        val raw = linkedMapOf<String, String>()
        for (field in connectionFields) scalar(record[field])?.let { raw[field] = it }
        val client = record["client_properties"] as? JsonObject
        if (client != null) for (field in clientProperties) scalar(client[field])?.let { raw["client_properties.$field"] = it }
        return guarded(raw, heldSecret)
    }

    /** A channel record reduced to the allow-list (spec rule 2), the connection part flattened as `connection_details.<name>`. */
    fun guardChannel(record: JsonObject, heldSecret: String? = null): GuardedRecord {
        val raw = linkedMapOf<String, String>()
        for (field in channelFields) scalar(record[field])?.let { raw[field] = it }
        val details = record["connection_details"] as? JsonObject
        if (details != null) for (field in channelDetails) scalar(details[field])?.let { raw["connection_details.$field"] = it }
        return guarded(raw, heldSecret)
    }

    /** The text for a broker error: matched on the broker's `reason` (exact), never the input. Unmapped or null: [GENERIC_ERROR]. */
    fun brokerErrorText(brokerReason: String?): String = when (brokerReason) {
        "Not management user" -> REASON_NO_MANAGEMENT_TAG
        "Not_Authorized" -> REASON_REFUSED
        else -> GENERIC_ERROR
    }

    private fun guarded(raw: Map<String, String>, heldSecret: String?): GuardedRecord {
        val fields = raw.mapValues { redact(it.value, heldSecret) }
        return GuardedRecord(fields.mapValues { it.value.text }, fields.values.count { it.hidden })
    }

    private fun scalar(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun isSecret(value: String, heldSecret: String?): Boolean =
        shapes.any { it.containsMatchIn(value) } || holdsSecret(value, heldSecret)

    private fun holdsSecret(value: String, heldSecret: String?): Boolean {
        if (heldSecret.isNullOrEmpty()) return false
        if (heldSecret.length < MIN_HELD_SEGMENT) return value == heldSecret
        return (0..heldSecret.length - MIN_HELD_SEGMENT).any { value.contains(heldSecret.substring(it, it + MIN_HELD_SEGMENT)) }
    }
}

/** [text] is the value, or [SecretGuard.HIDDEN] when [hidden]. */
data class GuardedText(val text: String, val hidden: Boolean)

/** [values] in input order, each possibly replaced by [SecretGuard.HIDDEN]; [hidden] counts the replaced ones. */
data class GuardedTexts(val values: List<String>, val hidden: Int)

/** Kept [fields] (flattened as described on [SecretGuard]) and how many kept values were hidden. */
data class GuardedRecord(val fields: Map<String, String>, val hidden: Int)
