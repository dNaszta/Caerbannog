package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelScrubber
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The one mapping of `GET /api/shovels` entries to [ShovelStatus], shared by the shovel gateway and the read-only status reader. Only the listed
 * fields are read: an address field (`src_uri`, `dest_uri`) never reaches a result, and the reason is scrubbed.
 */
internal object ShovelStatusMapping {
    private const val MAX_DEPTH = 64

    /** The entries of the list, or null when the text is not a JSON list. */
    fun entries(body: String): List<JsonObject> = parseList(body).orEmpty()

    fun parseList(body: String): List<JsonObject>? =
        ((parseUntrustedJson(body, MAX_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonArray)?.mapNotNull { it as? JsonObject }

    fun matches(entry: JsonObject, ref: ShovelRef): Boolean =
        (entry["name"] as? JsonPrimitive)?.contentOrNull == ref.name && (entry["vhost"] as? JsonPrimitive)?.contentOrNull == ref.vhost

    fun statusOf(entry: JsonObject): ShovelStatus {
        fun text(key: String) = (entry[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val state = when (text("state")) {
            "running" -> ShovelRunState.RUNNING
            "starting" -> ShovelRunState.STARTING
            "terminated" -> ShovelRunState.TERMINATED
            else -> ShovelRunState.OTHER
        }
        // The broker sends the reason as an Erlang-quoted string ("\"failed...\""); broker text is scrubbed before it can be shown.
        val reason = text("reason")?.removeSurrounding("\"")?.let { ShovelScrubber.scrub(it) }
        val ref = ShovelRef(text("vhost").orEmpty(), text("name").orEmpty())
        return ShovelStatus(ref, state, reason, (entry["forwarded"] as? JsonPrimitive)?.longOrNull, (entry["remaining"] as? JsonPrimitive)?.longOrNull)
    }
}
