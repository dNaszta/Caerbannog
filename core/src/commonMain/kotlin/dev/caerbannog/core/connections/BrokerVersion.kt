package dev.caerbannog.core.connections

/**
 * The major version out of the text `rabbitmq_version` of `GET /api/overview` (for example "4.4.0" gives 4, "3.13.7" gives 3): the leading
 * ASCII digits of the text before the first ".". Null for null, an empty text, a text that does not start with a digit, or a number that does
 * not fit an Int. A connection gets a migration executor only for a major version of 4 or more; null (unknown) fails closed.
 */
fun brokerMajorVersion(text: String?): Int? =
    text?.substringBefore('.')?.takeWhile { it in '0'..'9' }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * True for a known major version below 4: such a connection is a migration source only. Unknown (null, odd text) is NOT a source and not
 * 4.x either: it keeps the "version could not be read" behaviour.
 */
fun isMigrationSourceOnly(version: String?): Boolean =
    displayableBrokerVersion(version)?.let { brokerMajorVersion(it) }?.let { it < 4 } == true

private const val MAX_VERSION_LENGTH = 32
private val VERSION_SHAPE = Regex("[0-9]+(\\.[0-9]+)*([-+~][0-9A-Za-z.+~_-]*)?")

/**
 * The version text as it may be shown (banner, pill row, report source): trimmed, at most 32 characters, digits and dots first and then an
 * optional suffix such as `-rc.1` or `+build`. Anything else (a space inside, a line break, a long text) is null, "unknown", everywhere:
 * the broker's text is data from the network and never reaches the screen unchecked.
 */
fun displayableBrokerVersion(text: String?): String? =
    text?.trim()?.takeIf { it.length <= MAX_VERSION_LENGTH && VERSION_SHAPE.matches(it) }
