package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.SecretValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

// The four access actions (M8-12a). This file is one of the two places that call SecretValue.expose(): the writer of the hash into the file.

private const val USER_INCOMPLETE = "a user needs a name"
private const val VHOST_INCOMPLETE = "a virtual host needs a name"
private const val PERMISSION_INCOMPLETE = "a permission needs a virtual host and a user"
private const val TOPIC_INCOMPLETE = "a topic permission needs a virtual host, a user and an exchange"
private const val HASH_MISSING = "a user needs a password hash"
private const val ALGORITHM_UNSUPPORTED = "a user needs a supported password hashing algorithm"

/** The algorithms the broker can verify (spec: sha256, sha512, md5); an unknown name is never echoed. */
private val HASHING_ALGORITHMS = setOf("rabbit_password_hashing_sha256", "rabbit_password_hashing_sha512", "rabbit_password_hashing_md5")

/** A JSON array of strings under [key]: empty when absent; anything else is a [WrongTypeException]. */
private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    null, JsonNull -> emptyList()
    is JsonArray -> value.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw WrongTypeException(key) }
    else -> throw WrongTypeException(key)
}

private fun JsonObjectBuilder.putTags(tags: List<String>) {
    if (tags.isNotEmpty()) put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
}

internal val declareVhostCodec = codec<MigrationAction.DeclareVhost>(
    name = "declareVhost",
    read = { entry ->
        val name = entry.identifier("name")
        if (name.isNullOrBlank()) refused(VHOST_INCOMPLETE)
        else accepted(MigrationAction.DeclareVhost(name, entry.string("description") ?: "", entry.stringList("tags"), entry.string("defaultQueueType")))
    },
    write = { action, out ->
        out.putText("name", action.name)
        if (action.vhostDescription.isNotEmpty()) out.putText("description", action.vhostDescription)
        out.putTags(action.tags)
        action.defaultQueueType?.let { out.putText("defaultQueueType", it) }
    },
)

internal val declareUserCodec = codec<MigrationAction.DeclareUser>(
    name = "declareUser",
    read = { entry ->
        val name = entry.identifier("name")
        val hash = entry.string("passwordHash")
        val algorithm = entry.string("hashingAlgorithm")
        val tags = entry.stringList("tags")
        when {
            name.isNullOrBlank() -> refused(USER_INCOMPLETE)
            hash.isNullOrEmpty() -> refused(HASH_MISSING)
            algorithm !in HASHING_ALGORITHMS -> refused(ALGORITHM_UNSUPPORTED)
            else -> accepted(MigrationAction.DeclareUser(name, SecretValue.of(hash), algorithm!!, tags))
        }
    },
    write = { action, out ->
        out.putText("name", action.name)
        out.putText("passwordHash", action.passwordHash.expose())
        out.putText("hashingAlgorithm", action.hashingAlgorithm)
        out.putTags(action.tags)
    },
)

internal val setPermissionsCodec = codec<MigrationAction.SetPermissions>(
    name = "setPermissions",
    read = { entry ->
        val user = entry.identifier("user")
        if (user.isNullOrBlank()) refused(PERMISSION_INCOMPLETE)
        else accepted(MigrationAction.SetPermissions(entry.vhost(), user, entry.string("configure") ?: ".*", entry.string("write") ?: ".*", entry.string("read") ?: ".*"))
    },
    write = { action, out ->
        out.putText("user", action.user)
        out.putText("configure", action.configure)
        out.putText("write", action.write)
        out.putText("read", action.read)
    },
)

internal val setTopicPermissionsCodec = codec<MigrationAction.SetTopicPermissions>(
    name = "setTopicPermissions",
    read = { entry ->
        val user = entry.identifier("user")
        val exchange = entry.string("exchange")
        if (user.isNullOrBlank() || exchange.isNullOrBlank()) refused(TOPIC_INCOMPLETE)
        else accepted(MigrationAction.SetTopicPermissions(entry.vhost(), user, exchange, entry.string("write") ?: ".*", entry.string("read") ?: ".*"))
    },
    write = { action, out ->
        out.putText("user", action.user)
        out.putText("exchange", action.exchange)
        out.putText("write", action.write)
        out.putText("read", action.read)
    },
)

private val HASH_VALUE = Regex("""("passwordHash"\s*:\s*)"(?:[^"\\]|\\.)*"""")

/** The raw file text with every `passwordHash` value replaced by `hash hidden` (D4); the file itself and its checksum keep the real value. */
fun redactedRaw(rawJson: String): String = rawJson.replace(HASH_VALUE) { "${it.groupValues[1]}\"hash hidden\"" }
