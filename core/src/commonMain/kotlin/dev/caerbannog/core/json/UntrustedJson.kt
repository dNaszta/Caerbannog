package dev.caerbannog.core.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Outcome of [parseUntrustedJson]. */
public sealed interface UntrustedJson {
    public data class Parsed(val element: JsonElement) : UntrustedJson

    /** Nesting went past the limit; the text was not handed to the reader. */
    public data object TooDeep : UntrustedJson

    public data object NotJson : UntrustedJson
}

/**
 * Parses [text] into a JSON tree after checking the nesting depth, so hostile input cannot overflow the reader's stack.
 * Only the reader's own failures are caught; a `StackOverflowError` is never swallowed, the scan prevents it.
 */
public fun parseUntrustedJson(text: String, maxDepth: Int, json: Json = Json): UntrustedJson {
    if (JsonNestingScan.findTooDeep(text, maxDepth) != null) return UntrustedJson.TooDeep
    return try {
        UntrustedJson.Parsed(json.parseToJsonElement(text))
    } catch (_: SerializationException) {
        UntrustedJson.NotJson
    } catch (_: IllegalArgumentException) {
        UntrustedJson.NotJson
    }
}
