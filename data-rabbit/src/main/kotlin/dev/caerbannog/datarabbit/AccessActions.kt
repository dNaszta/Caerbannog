package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The four access actions on the held 4.x connection (M8-12c): read first, create only what is missing, never overwrite, never delete. An existing
 * item that equals what the action carries is a no-op (no write); a difference is a Conflict with a fixed text that never shows a hash or a value.
 * The default virtual host `/` is exempt: no request at all. The user's hash is compared inside [RedactedDefinitions] and goes nowhere else.
 * Writes go through [MigrationWriter] (the one PUT sender), reads through [ResourceReader]; every request passes [ExecutorPathAllowList].
 */
internal class AccessActions(private val reader: ResourceReader, private val writer: MigrationWriter) {

    suspend fun execute(action: MigrationAction): ActionResult = when (action) {
        is MigrationAction.DeclareVhost -> declareVhost(action)
        is MigrationAction.DeclareUser -> declareUser(action)
        is MigrationAction.SetPermissions -> setPermissions(action)
        is MigrationAction.SetTopicPermissions -> setTopicPermissions(action)
        else -> ExecutorReasons.failed(ExecutorReasons.NOT_A_REQUEST)
    }

    private suspend fun write(action: MigrationAction): ActionResult = when (val written = writer.write(action)) {
        WriteOutcome.Success -> ActionResult.Success
        WriteOutcome.BadRequest -> ExecutorReasons.failed(ExecutorReasons.REFUSED)
        is WriteOutcome.Failed -> ExecutorReasons.failed(written.reason)
    }

    /** Reads the item of [action]; null with the failure when the answer is neither 200 nor 404. */
    private suspend fun readItem(action: MigrationAction): Pair<ManagementReply.Response?, ActionResult?> {
        val reply = reader.read(action)
        if (reply !is ManagementReply.Response) return null to ExecutorReasons.forReply(reply)
        return when (reply.status) {
            OK, NOT_FOUND -> reply to null
            else -> null to ExecutorReasons.forReply(reply)
        }
    }

    // ---- virtual hosts (D5 = B) ----

    private suspend fun declareVhost(action: MigrationAction.DeclareVhost): ActionResult {
        if (action.name == "/") return ActionResult.Success
        val (reply, failure) = readItem(action)
        if (reply == null) return failure!!
        if (reply.status == NOT_FOUND) return write(action)
        val json = parse(reply.body) as? JsonObject
        val meta = json?.get("metadata") as? JsonObject
        // The management answer carries the settings in `metadata` (4.x) and, on 3.13, also at the top level.
        fun setting(key: String): JsonElement? = meta?.get(key)?.takeUnless { it is JsonNull } ?: json?.get(key)?.takeUnless { it is JsonNull }
        val existingDescription = (setting("description") as? JsonPrimitive)?.content.orEmpty()
        val existingTags = tagSet(setting("tags"))
        val existingType = (setting("default_queue_type") as? JsonPrimitive)?.content
        val name = ExecutorReasons.accessName(action.name)?.let { "The virtual host $it" } ?: "The virtual host"
        val what = when {
            action.vhostDescription.isNotEmpty() && action.vhostDescription != existingDescription -> "a different description"
            action.tags.isNotEmpty() && action.tags.toSet() != existingTags -> "different tags"
            action.defaultQueueType != null && action.defaultQueueType != existingType -> "a different default queue type"
            else -> return ActionResult.Success
        }
        return ExecutorReasons.accessConflict("$name exists with $what")
    }

    // ---- users ----

    private suspend fun declareUser(action: MigrationAction.DeclareUser): ActionResult {
        val (reply, failure) = readItem(action)
        if (reply == null) return failure!!
        if (reply.status == NOT_FOUND) return write(action)
        // 4.4 hides the hash in GET /api/users/{name}: the target's definitions are read through the redacting reader, which keeps users only.
        val definitions = reader.definitions()
        if (definitions !is ManagementReply.Response) return ExecutorReasons.forReply(definitions)
        if (definitions.status != OK) return ExecutorReasons.forReply(definitions)
        val existing = RedactedDefinitions.parse(definitions.body)?.user(action.name) ?: return ExecutorReasons.failed(ExecutorReasons.USER_NOT_COMPARABLE)
        val name = ExecutorReasons.accessName(action.name)?.let { "The user $it" } ?: "The user"
        return when {
            existing.hash?.matches(action.passwordHash) != true -> ExecutorReasons.accessConflict("$name exists with a different password hash")
            existing.tags.toSet() != action.tags.toSet() -> ExecutorReasons.accessConflict("$name exists with different tags")
            else -> ActionResult.Success
        }
    }

    // ---- permissions ----

    private suspend fun setPermissions(action: MigrationAction.SetPermissions): ActionResult {
        val (reply, failure) = readItem(action)
        if (reply == null) return failure!!
        if (reply.status == NOT_FOUND) return write(action)
        val json = parse(reply.body) as? JsonObject
        val same = patterns(json, "configure") == action.configure && patterns(json, "write") == action.write && patterns(json, "read") == action.read
        return if (same) ActionResult.Success else ExecutorReasons.accessConflict("${permissionsOf(action.user)} exist with other patterns")
    }

    private suspend fun setTopicPermissions(action: MigrationAction.SetTopicPermissions): ActionResult {
        val (reply, failure) = readItem(action)
        if (reply == null) return failure!!
        if (reply.status == NOT_FOUND) return write(action)
        val entry = (parse(reply.body) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["exchange"] as? JsonPrimitive)?.content == action.exchange } ?: return write(action)
        val same = patterns(entry, "write") == action.write && patterns(entry, "read") == action.read
        return if (same) ActionResult.Success else ExecutorReasons.accessConflict("${permissionsOf(action.user)} exist with other patterns")
    }

    private fun permissionsOf(user: String) = ExecutorReasons.accessName(user)?.let { "The permissions of $it" } ?: "The permissions"

    // ---- reading answers ----

    private fun parse(body: String): JsonElement? = (parseUntrustedJson(body, MAX_DEPTH) as? UntrustedJson.Parsed)?.element

    private fun patterns(json: JsonObject?, key: String): String? = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun tagSet(element: JsonElement?): Set<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
        is JsonPrimitive -> if (element.isString) element.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
        else -> emptySet()
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val MAX_DEPTH = 64
    }
}
