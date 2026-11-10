package dev.caerbannog.core.connections

/**
 * The major version out of the text `rabbitmq_version` of `GET /api/overview` (for example "4.4.0" gives 4, "3.13.7" gives 3): the leading
 * ASCII digits of the text before the first ".". Null for null, an empty text, a text that does not start with a digit, or a number that does
 * not fit an Int. A connection gets a migration executor only for a major version of 4 or more; null (unknown) fails closed.
 */
fun brokerMajorVersion(text: String?): Int? =
    text?.substringBefore('.')?.takeWhile { it in '0'..'9' }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * True for a known major version below 4: such a connection is a migration source only. Unknown (null, unparsable) is NOT a source and not
 * 4.x either: it keeps the "version could not be read" behaviour.
 */
fun isMigrationSourceOnly(version: String?): Boolean = brokerMajorVersion(version)?.let { it < 4 } == true

/** M8-02a STUB (wrong on purpose): only trims. Real rule: trimmed, at most 32 chars, matching the version pattern, else null. */
fun displayableBrokerVersion(text: String?): String? = text?.trim() // M8-02a STUB
