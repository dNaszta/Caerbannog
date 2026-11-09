package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface MigrationValidationResult {
    data class Valid(val migration: MigrationFile) : MigrationValidationResult
    data class Invalid(val reason: String) : MigrationValidationResult
}

object MigrationParser {

    /** A migration file has a known shallow schema (a few levels); far below the reader's failure point near 10,000. */
    private const val MAX_FILE_DEPTH = 64

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
    }

    fun computeChecksum(content: String): String {
        // FNV-1a 64-bit checksum over normalized whitespace/line-endings
        val normalized = content.replace("\r\n", "\n").trim()
        var hash = -3750763034362895579L
        for (char in normalized) {
            hash = hash xor char.code.toLong()
            hash = hash * 1099511628211L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    fun parse(
        rawJson: String,
        existingVersions: Set<String> = emptySet(),
    ): MigrationValidationResult {
        val rootElement: JsonElement = when (val parsed = parseUntrustedJson(rawJson, MAX_FILE_DEPTH, lenientJson)) {
            is UntrustedJson.Parsed -> parsed.element
            UntrustedJson.TooDeep -> return MigrationValidationResult.Invalid("the file is nested too deep")
            UntrustedJson.NotJson -> return MigrationValidationResult.Invalid("the file is not valid JSON")
        }

        val root = rootElement as? JsonObject
            ?: return MigrationValidationResult.Invalid("the file is not valid JSON")

        val versionElement = root["version"]?.jsonPrimitive
        val version = versionElement?.content
        if (version.isNullOrBlank()) {
            return MigrationValidationResult.Invalid("the file has no version")
        }

        if (version in existingVersions) {
            return MigrationValidationResult.Invalid("that version already exists")
        }

        val description = root["description"]?.jsonPrimitive?.content ?: ""
        val upArray = root["up"] as? JsonArray
            ?: return MigrationValidationResult.Invalid("the file has no up actions")

        val actions = mutableListOf<MigrationAction>()
        for (item in upArray) {
            val itemObj = item as? JsonObject
                ?: return MigrationValidationResult.Invalid("which action is not supported")

            val actionName = itemObj["action"]?.jsonPrimitive?.content
            if (actionName.isNullOrBlank()) {
                return MigrationValidationResult.Invalid("which action is not supported")
            }

            val vhost = itemObj["vhost"]?.jsonPrimitive?.content ?: "/"

            when (actionName) {
                "declareQueue" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("queue name is required")
                    val type = itemObj["type"]?.jsonPrimitive?.content ?: "quorum"
                    val durable = itemObj["durable"]?.jsonPrimitive?.booleanOrNull ?: true
                    actions += MigrationAction.DeclareQueue(
                        name = name,
                        type = type,
                        vhost = vhost,
                        durable = durable,
                    )
                }

                "deleteQueue" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("queue name is required")
                    actions += MigrationAction.DeleteQueue(name = name, vhost = vhost)
                }

                "declareExchange" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("exchange name is required")
                    val type = itemObj["type"]?.jsonPrimitive?.content ?: "direct"
                    val durable = itemObj["durable"]?.jsonPrimitive?.booleanOrNull ?: true
                    actions += MigrationAction.DeclareExchange(
                        name = name,
                        type = type,
                        vhost = vhost,
                        durable = durable,
                    )
                }

                "deleteExchange" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("exchange name is required")
                    actions += MigrationAction.DeleteExchange(name = name, vhost = vhost)
                }

                "declareBinding" -> {
                    val exchange = itemObj["exchange"]?.jsonPrimitive?.content
                    val queue = itemObj["queue"]?.jsonPrimitive?.content
                        ?: itemObj["destination"]?.jsonPrimitive?.content
                    if (exchange.isNullOrBlank() || queue.isNullOrBlank()) {
                        return MigrationValidationResult.Invalid("binding requires exchange and queue")
                    }
                    val routingKey = itemObj["routingKey"]?.jsonPrimitive?.content ?: ""
                    actions += MigrationAction.DeclareBinding(
                        exchange = exchange,
                        queue = queue,
                        routingKey = routingKey,
                        vhost = vhost,
                    )
                }

                "deleteBinding" -> {
                    val exchange = itemObj["exchange"]?.jsonPrimitive?.content
                    val queue = itemObj["queue"]?.jsonPrimitive?.content
                        ?: itemObj["destination"]?.jsonPrimitive?.content
                    if (exchange.isNullOrBlank() || queue.isNullOrBlank()) {
                        return MigrationValidationResult.Invalid("binding requires exchange and queue")
                    }
                    val routingKey = itemObj["routingKey"]?.jsonPrimitive?.content ?: ""
                    actions += MigrationAction.DeleteBinding(
                        exchange = exchange,
                        queue = queue,
                        routingKey = routingKey,
                        vhost = vhost,
                    )
                }

                "declareStream" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("stream name is required")
                    actions += MigrationAction.DeclareStream(name = name, vhost = vhost)
                }

                "deleteStream" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("stream name is required")
                    actions += MigrationAction.DeleteStream(name = name, vhost = vhost)
                }

                "declarePolicy" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    val pattern = itemObj["pattern"]?.jsonPrimitive?.content ?: ".*"
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("policy name is required")
                    actions += MigrationAction.DeclarePolicy(name = name, pattern = pattern, vhost = vhost)
                }

                "deletePolicy" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.content
                    if (name.isNullOrBlank()) return MigrationValidationResult.Invalid("policy name is required")
                    actions += MigrationAction.DeletePolicy(name = name, vhost = vhost)
                }

                else -> {
                    return MigrationValidationResult.Invalid("action '$actionName' is not supported; which action is not supported")
                }
            }
        }

        val checksum = computeChecksum(rawJson)
        val migrationFile = MigrationFile(
            version = version,
            description = description,
            up = actions,
            rawJson = rawJson,
            checksum = checksum,
        )

        return MigrationValidationResult.Valid(migrationFile)
    }

    fun exportToJson(
        version: String,
        description: String,
        actions: List<MigrationAction>,
    ): String {
        val root = buildJsonObject {
            put("version", version)
            put("description", description)
            putJsonArray("up") {
                for (action in actions) {
                    add(
                        buildJsonObject {
                            put("action", action.actionName)
                            if (action.vhost != "/") put("vhost", action.vhost)
                            when (action) {
                                is MigrationAction.DeclareQueue -> {
                                    put("name", action.name)
                                    put("type", action.type)
                                    if (!action.durable) put("durable", false)
                                }
                                is MigrationAction.DeleteQueue -> {
                                    put("name", action.name)
                                }
                                is MigrationAction.DeclareExchange -> {
                                    put("name", action.name)
                                    put("type", action.type)
                                    if (!action.durable) put("durable", false)
                                }
                                is MigrationAction.DeleteExchange -> {
                                    put("name", action.name)
                                }
                                is MigrationAction.DeclareBinding -> {
                                    put("exchange", action.exchange)
                                    put("queue", action.queue)
                                    if (action.routingKey.isNotEmpty()) put("routingKey", action.routingKey)
                                }
                                is MigrationAction.DeleteBinding -> {
                                    put("exchange", action.exchange)
                                    put("queue", action.queue)
                                    if (action.routingKey.isNotEmpty()) put("routingKey", action.routingKey)
                                }
                                is MigrationAction.DeclareStream -> {
                                    put("name", action.name)
                                }
                                is MigrationAction.DeleteStream -> {
                                    put("name", action.name)
                                }
                                is MigrationAction.DeclarePolicy -> {
                                    put("name", action.name)
                                    put("pattern", action.pattern)
                                }
                                is MigrationAction.DeletePolicy -> {
                                    put("name", action.name)
                                }
                            }
                        }
                    )
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }
}
