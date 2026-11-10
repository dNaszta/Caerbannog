package dev.caerbannog.core.connections

/**
 * The major version out of the text `rabbitmq_version` of `GET /api/overview` (for example "4.4.0" gives 4, "3.13.7" gives 3): the leading
 * ASCII digits of the text before the first ".". Null for null, an empty text, a text that does not start with a digit, or a number that does
 * not fit an Int. A connection gets a migration executor only for a major version of 4 or more; null (unknown) fails closed.
 */
fun brokerMajorVersion(text: String?): Int? =
    text?.substringBefore('.')?.takeWhile { it in '0'..'9' }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/** M8-02a STUB (wrong on purpose until implemented): true for a known major version below 4; unknown (null, unparsable) is NOT a source. */
fun isMigrationSourceOnly(version: String?): Boolean = false
