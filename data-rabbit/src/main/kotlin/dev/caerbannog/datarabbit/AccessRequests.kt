package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The PUT of each access action (M8-12c): `PUT /api/vhosts/{name}`, `/api/users/{name}` (password_hash, hashing_algorithm, tags), `/api/permissions/{v}/{u}`,
 * `/api/topic-permissions/{v}/{u}`. The ONE place of data-rabbit that calls `SecretValue.expose()` (the user request body). Only the settings an action
 * carries are sent for a virtual host. Nothing here is ever a DELETE.
 */
internal object AccessRequests {
    fun requestFor(action: MigrationAction): ManagementWrite? = when (action) {
        is MigrationAction.DeclareVhost -> ManagementWrite("PUT", vhostPath(action.name), buildJsonObject {
            if (action.vhostDescription.isNotEmpty()) put("description", action.vhostDescription)
            if (action.tags.isNotEmpty()) put("tags", JsonArray(action.tags.map { JsonPrimitive(it) }))
            action.defaultQueueType?.let { put("default_queue_type", it) }
        })
        is MigrationAction.DeclareUser -> ManagementWrite("PUT", userPath(action.name), buildJsonObject {
            put("password_hash", action.passwordHash.expose())
            // Without the algorithm the login fails (spec); tags are a comma separated string.
            put("hashing_algorithm", action.hashingAlgorithm)
            put("tags", action.tags.joinToString(","))
        })
        is MigrationAction.SetPermissions -> ManagementWrite("PUT", pairPath("permissions", action.vhost, action.user), buildJsonObject {
            put("configure", action.configure); put("write", action.write); put("read", action.read)
        })
        is MigrationAction.SetTopicPermissions -> ManagementWrite("PUT", pairPath("topic-permissions", action.vhost, action.user), buildJsonObject {
            put("exchange", action.exchange); put("write", action.write); put("read", action.read)
        })
        else -> null
    }

    fun vhostPath(name: String) = "/api/vhosts/${MigrationRequests.encode(name)}"
    fun userPath(name: String) = "/api/users/${MigrationRequests.encode(name)}"
    fun pairPath(collection: String, vhost: String, user: String) = MigrationRequests.collectionPath(collection, vhost, user)
}
