package dev.caerbannog.core.access

import kotlinx.serialization.json.JsonObject

/**
 * M10-02 contract (test developer, COMPILE-ONLY STUB: every function below is a placeholder that makes `SecretGuardTest` fail on assertions).
 * Spec "Access view (M10)", "Redaction rules". The one place the rules live; the gateway, the UI, the logs and the MCP server call it.
 *
 * Contract the tests pin:
 *  - STATELESS: no non-static field; the held secret is a parameter of every call and is never kept, logged or put in a result.
 *  - Never prints, never logs.
 *  - [redact]: a value that matches a shape (JWT, `Bearer `, `scheme://user:password@`, `password=`, `secret=`, `token=`, case-insensitive) or contains
 *    the held secret, or any substring of it of [MIN_HELD_SEGMENT] characters or more, is replaced WHOLE by [HIDDEN].
 *  - [guardConnection] / [guardChannel]: allow-list, not deny-list. Kept: top-level scalar fields by their broker name, and of `client_properties` only
 *    `product`, `version`, `platform`, `connection_name`, under the key `client_properties.<name>`. `user_provided_name` is scanned like
 *    `connection_name`. Everything else is dropped (not masked). A null or non-scalar value is dropped. Every kept string is scanned.
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

    fun redact(value: String, heldSecret: String? = null): GuardedText = GuardedText(value, hidden = false)

    fun redactAll(values: Collection<String>, heldSecret: String? = null): GuardedTexts = GuardedTexts(values.toList(), hidden = 0)

    fun guardConnection(record: JsonObject, heldSecret: String? = null): GuardedRecord = GuardedRecord(emptyMap(), hidden = 0)

    fun guardChannel(record: JsonObject, heldSecret: String? = null): GuardedRecord = GuardedRecord(emptyMap(), hidden = 0)

    /** The text for a broker error: matched on the broker's `reason` (exact), never the input. Unmapped or null: [GENERIC_ERROR]. */
    fun brokerErrorText(brokerReason: String?): String = brokerReason.orEmpty()
}

/** [text] is the value, or [SecretGuard.HIDDEN] when [hidden]. */
data class GuardedText(val text: String, val hidden: Boolean)

/** [values] in input order, each possibly replaced by [SecretGuard.HIDDEN]; [hidden] counts the replaced ones. */
data class GuardedTexts(val values: List<String>, val hidden: Int)

/** Kept [fields] (flattened as described on [SecretGuard]) and how many kept values were hidden. */
data class GuardedRecord(val fields: Map<String, String>, val hidden: Int)
