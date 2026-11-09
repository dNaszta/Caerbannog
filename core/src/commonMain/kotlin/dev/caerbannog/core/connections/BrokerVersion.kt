package dev.caerbannog.core.connections

/**
 * M5-08a R1: the major version out of the text `rabbitmq_version` of `GET /api/overview` (for example "4.4.0" gives 4, "3.13.7" gives 3): the
 * leading ASCII digits of the text before the first ".", null for null, an empty text, a text that does not start with a digit, or a number that
 * does not fit an Int. A connection gets a migration executor only for a major version of 4 or more; null (unknown) fails closed.
 * M5-08a R1 STUB: returns null for everything (wrong).
 */
fun brokerMajorVersion(text: String?): Int? = null
