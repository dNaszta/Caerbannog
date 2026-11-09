package dev.caerbannog.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// M5-07 TEST-FIRST STUBS (test developer). Compile stubs only, with deliberately wrong behaviour so the tests are red for the
// right reason. The senior developer DELETES this file: the real code is core/.../migrate3x/JsonNestingScan.kt moved here as
// core/.../json/JsonNestingScan.kt (public, findTooDeep(text, maxDepth)), plus UntrustedJson.kt with parseUntrustedJson.

public object JsonNestingScan {
    /** [section] is the top-level key that holds the deep value, or null when there is none. */
    public data class TooDeep(val section: String?)

    /** STUB: never finds anything. */
    public fun findTooDeep(text: String, maxDepth: Int): TooDeep? = null
}

public sealed interface UntrustedJson {
    public data class Parsed(val element: JsonElement) : UntrustedJson
    public data object TooDeep : UntrustedJson
    public data object NotJson : UntrustedJson
}

/** STUB: not implemented. */
public fun parseUntrustedJson(text: String, maxDepth: Int, json: Json = Json): UntrustedJson =
    throw NotImplementedError("M5-07 stub: parseUntrustedJson")
