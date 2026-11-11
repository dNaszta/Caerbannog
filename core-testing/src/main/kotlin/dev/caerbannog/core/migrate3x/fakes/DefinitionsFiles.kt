package dev.caerbannog.core.migrate3x.fakes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M8-06a: definitions export TEXT built from small pieces, for tests that go through the file loader and parser (the screen reads text, not
 * documents). Same shape as the 3.13 export of `fixtures/m8-01`. A queue gets "x-queue-type" only when [type] is given, so a 3.x classic queue
 * has none, like a real 3.13 export. Password hashes in the files of tests are SENTINEL values only.
 */
object DefinitionsFiles {
    fun queue(
        name: String,
        type: String? = null,
        durable: Boolean = true,
        vhost: String? = "/",
        arguments: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("name", name)
        vhost?.let { put("vhost", it) }
        put("durable", durable)
        put("auto_delete", false)
        put("arguments", JsonObject((type?.let { mapOf("x-queue-type" to JsonPrimitive(it)) } ?: emptyMap()) + arguments))
    }

    fun exchange(name: String, type: String = "direct", vhost: String? = "/"): JsonObject = buildJsonObject {
        put("name", name)
        vhost?.let { put("vhost", it) }
        put("type", type)
        put("durable", true)
        put("auto_delete", false)
        put("internal", false)
        put("arguments", JsonObject(emptyMap()))
    }

    fun binding(source: String, destination: String, routingKey: String = "", vhost: String? = "/"): JsonObject = buildJsonObject {
        put("source", source)
        vhost?.let { put("vhost", it) }
        put("destination", destination)
        put("destination_type", "queue")
        put("routing_key", routingKey)
        put("arguments", JsonObject(emptyMap()))
    }

    fun policy(name: String, pattern: String, definition: Map<String, JsonElement>, vhost: String? = "/"): JsonObject = buildJsonObject {
        put("name", name)
        vhost?.let { put("vhost", it) }
        put("pattern", pattern)
        put("apply-to", "all")
        put("priority", 0)
        put("definition", JsonObject(definition))
    }

    fun file(
        version: String? = "3.13.7",
        queues: List<JsonObject> = emptyList(),
        exchanges: List<JsonObject> = emptyList(),
        bindings: List<JsonObject> = emptyList(),
        policies: List<JsonObject> = emptyList(),
        /** Null leaves the "vhosts" section out, like a per-virtual-host export. */
        vhosts: List<String>? = listOf("/"),
    ): String {
        val root = buildJsonObject {
            put("rabbit_version", version?.let(::JsonPrimitive) ?: JsonNull)
            put("rabbitmq_version", version?.let(::JsonPrimitive) ?: JsonNull)
            vhosts?.let { list -> put("vhosts", JsonArray(list.map { buildJsonObject { put("name", it) } })) }
            put("queues", JsonArray(queues))
            put("exchanges", JsonArray(exchanges))
            put("bindings", JsonArray(bindings))
            put("policies", JsonArray(policies))
            put("parameters", JsonArray(emptyList()))
        }
        return Json.encodeToString(JsonElement.serializer(), root)
    }

    // ---- M8-12b: access data of a full 3.x export (virtual hosts with settings, users with a hash, permissions) ----

    fun vhost(name: String, description: String? = null, tags: List<String> = emptyList(), defaultQueueType: String? = null): JsonObject = buildJsonObject {
        put("name", name)
        description?.let { put("description", it) }
        if (tags.isNotEmpty()) put("tags", JsonArray(tags.map(::JsonPrimitive)))
        defaultQueueType?.let { put("default_queue_type", it) }
    }

    /** [hash] null leaves `password_hash` out (external authentication); [algorithm] null leaves `hashing_algorithm` out. */
    fun user(name: String, hash: String? = "SENTINEL-HASH", algorithm: String? = "rabbit_password_hashing_sha256", tags: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("name", name)
        hash?.let { put("password_hash", it) }
        algorithm?.let { put("hashing_algorithm", it) }
        put("tags", JsonArray(tags.map(::JsonPrimitive)))
        put("limits", JsonObject(emptyMap()))
    }

    fun permission(vhost: String, user: String, configure: String = ".*", write: String = ".*", read: String = ".*"): JsonObject = buildJsonObject {
        put("user", user); put("vhost", vhost); put("configure", configure); put("write", write); put("read", read)
    }

    fun topicPermission(vhost: String, user: String, exchange: String = "amq.topic", write: String = ".*", read: String = ".*"): JsonObject = buildJsonObject {
        put("user", user); put("vhost", vhost); put("exchange", exchange); put("write", write); put("read", read)
    }

    /** A full export with access data and, optionally, queues. The version is 3.13.7. */
    fun accessFile(
        vhosts: List<JsonObject>,
        users: List<JsonObject> = emptyList(),
        permissions: List<JsonObject> = emptyList(),
        topicPermissions: List<JsonObject> = emptyList(),
        queues: List<JsonObject> = emptyList(),
    ): String {
        val root = buildJsonObject {
            put("rabbit_version", "3.13.7"); put("rabbitmq_version", "3.13.7")
            put("users", JsonArray(users)); put("vhosts", JsonArray(vhosts)); put("permissions", JsonArray(permissions)); put("topic_permissions", JsonArray(topicPermissions))
            put("queues", JsonArray(queues)); put("exchanges", JsonArray(emptyList())); put("bindings", JsonArray(emptyList())); put("policies", JsonArray(emptyList()))
            put("parameters", JsonArray(emptyList()))
        }
        return Json.encodeToString(JsonElement.serializer(), root)
    }

    /** [count] classic queues named `q-00001`, ... in the virtual host "/". */
    fun classicQueues(count: Int): String = file(queues = (1..count).map { queue("q-" + it.toString().padStart(5, '0')) })
}
