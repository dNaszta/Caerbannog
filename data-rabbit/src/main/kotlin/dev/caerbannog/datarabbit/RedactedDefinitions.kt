package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.SecretValue

/** One user of `GET /api/definitions`: the hash is a [SecretValue] (redacting toString), the only thing the reader keeps besides name, algorithm and tags. */
internal class RedactedUser(val name: String, val hash: SecretValue?, val algorithm: String?, val tags: List<String>) {
    override fun toString(): String = "RedactedUser($name)"
}

/**
 * M8-12c: reads the definitions of the 4.x target ONLY for its users and drops everything else at once (Shovel parameters and federation URIs
 * carry clear passwords). Never logs, never keeps the text. M8-12c STUB: reads nothing.
 */
internal class RedactedDefinitions private constructor(private val users: Map<String, RedactedUser>) {
    fun user(name: String): RedactedUser? = users[name]
    override fun toString(): String = "RedactedDefinitions(${users.size} users)"

    companion object {
        /** Null when the text is not a definitions export. */
        fun parse(body: String): RedactedDefinitions? = null // M8-12c STUB
    }
}
