package dev.caerbannog.core.migrations

private const val MAX_ECHOED_NAME_LENGTH = 64

/**
 * The one echo rule: true when a name from a file or a broker may be put in a message. It must be 1 to 64 characters and either exactly
 * "/" or made only of A-Z a-z 0-9 . _ - and not "." or "..". Anything else (quote, backslash, newline, space, markup, 65 characters) is
 * never echoed and the wording of the message stays the same without the name. M8-04's rule for an unknown ACTION name is a different thing.
 */
fun echoableName(name: String): Boolean {
    if (name.length !in 1..MAX_ECHOED_NAME_LENGTH) return false
    if (name == "/") return true
    if (name == "." || name == "..") return false
    return name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
}
