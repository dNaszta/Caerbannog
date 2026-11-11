package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One user of `GET /api/definitions`: the hash is a [SecretValue] (redacting toString), the only thing the reader keeps besides name, algorithm and tags. */
internal class RedactedUser(val name: String, val hash: SecretValue?, val algorithm: String?, val tags: List<String>) {
    override fun toString(): String = "RedactedUser($name)"
}

/**
 * M8-12c: reads the definitions of the 4.x target ONLY for its users and drops everything else at once (Shovel parameters and federation URIs
 * carry clear passwords). Never logs, never keeps the text or a JSON tree: the only field is the map of users.
 */
internal class RedactedDefinitions private constructor(private val users: Map<String, RedactedUser>) {
    fun user(name: String): RedactedUser? = users[name]
    override fun toString(): String = "RedactedDefinitions(${users.size} users)"

    companion object {
        private const val MAX_DEPTH = 64

        /** Null when the text is not a definitions export. */
        fun parse(body: String): RedactedDefinitions? {
            val root = (parseUntrustedJson(body, MAX_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject ?: return null
            val users = (root["users"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull(::readUser).associateBy { it.name }
            return RedactedDefinitions(users)
        }

        private fun text(entry: JsonObject, key: String): String? = (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun readUser(entry: JsonObject): RedactedUser? {
            val name = text(entry, "name") ?: return null
            val tags = when (val raw = entry["tags"]) {
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                is JsonPrimitive -> if (raw.isString) raw.content.split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                else -> emptyList()
            }
            return RedactedUser(name, text(entry, "password_hash")?.let { SecretValue.of(it) }, text(entry, "hashing_algorithm"), tags)
        }
    }
}
