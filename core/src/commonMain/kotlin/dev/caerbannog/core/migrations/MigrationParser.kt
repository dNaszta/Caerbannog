package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    private const val MAX_ECHOED_NAME_LENGTH = 64

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
    ): MigrationValidationResult = try {
        parseFields(rawJson, existingVersions)
    } catch (refusal: FieldRefusal) {
        // The one place that turns a wrong-typed or refused field into a result; the reason never holds input.
        MigrationValidationResult.Invalid(refusal.reason)
    }

    private fun parseFields(rawJson: String, existingVersions: Set<String>): MigrationValidationResult {
        val rootElement: JsonElement = when (val parsed = parseUntrustedJson(rawJson, MAX_FILE_DEPTH, lenientJson)) {
            is UntrustedJson.Parsed -> parsed.element
            UntrustedJson.TooDeep -> return MigrationValidationResult.Invalid("the file is nested too deep")
            UntrustedJson.NotJson -> return MigrationValidationResult.Invalid("the file is not valid JSON")
        }

        val root = rootElement as? JsonObject
            ?: return MigrationValidationResult.Invalid("the file is not valid JSON")

        val version = root.versionText()
        if (version.isNullOrBlank()) {
            return MigrationValidationResult.Invalid("the file has no version")
        }

        if (version in existingVersions) {
            return MigrationValidationResult.Invalid("that version already exists")
        }

        val description = root.string("description") ?: ""
        val upArray = root["up"] as? JsonArray
            ?: return MigrationValidationResult.Invalid("the file has no up actions")

        val actions = mutableListOf<MigrationAction>()
        for (item in upArray) {
            val itemObj = item as? JsonObject
                ?: return MigrationValidationResult.Invalid("which action is not supported")

            val actionName = itemObj.string("action")
            if (actionName.isNullOrBlank()) {
                return MigrationValidationResult.Invalid("which action is not supported")
            }

            val codec = ActionCodecs.find(actionName)
                ?: return MigrationValidationResult.Invalid(unsupportedActionMessage(actionName))
            when (val result = codec.read(itemObj)) {
                is ActionReadResult.Read -> actions += result.action
                is ActionReadResult.Refused -> return MigrationValidationResult.Invalid(result.reason)
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

    /** An untrusted action name is named only when it is short and plain; the phrase stays the same either way. */
    private fun unsupportedActionMessage(actionName: String): String {
        val plain = actionName.length <= MAX_ECHOED_NAME_LENGTH && actionName.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        return if (plain) "action '$actionName' is not supported; which action is not supported" else "which action is not supported"
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
                            ActionCodecs.forAction(action.actionName).write(action, this)
                        }
                    )
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }
}
