package dev.caerbannog.core.migrations

/**
 * M5-08a: true when a name from a file or a broker may be put in a message: 1 to 64 characters and either exactly "/" or made only of
 * A-Z a-z 0-9 . _ - and not "." or "..". Anything else (quote, backslash, newline, space, markup, 65 characters) is never echoed; the wording
 * of the message stays the same without the name. M8-04's rule for an unknown ACTION name (letters and digits) is a different thing.
 * M5-08a STUB: this body is the old letters-and-digits rule and is wrong for `order-events`, `orders.v2` and `/`.
 */
fun echoableName(name: String): Boolean = name.length in 1..64 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
