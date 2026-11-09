package dev.caerbannog.core.migrate3x

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Why a text is not accepted as a definitions export. [message] is fixed text and never contains any part of the input. */
sealed interface DefinitionsRejection {
    val message: String

    data object NotJson : DefinitionsRejection {
        override val message: String = "The file is not valid JSON"
    }

    data object NoDefinitions : DefinitionsRejection {
        override val message: String = "The file has no definitions"
    }

    data object NotDefinitionsFile : DefinitionsRejection {
        override val message: String = "The file is not a definitions file"
    }

    /** A section has the wrong shape, lacks a required key or nests values too deep; only the [section] name is reported. */
    data class Malformed(val section: String) : DefinitionsRejection {
        override val message: String get() = "The file is not a definitions file: the section \"$section\" is malformed"
    }
}

sealed interface DefinitionsParseResult {
    data class Parsed(val document: DefinitionsDocument) : DefinitionsParseResult

    /** A per-virtual-host export: the user must name the virtual host before it can be used. */
    data class NeedsVirtualHost(val draft: UnassignedDefinitions) : DefinitionsParseResult
    data class Rejected(val reason: DefinitionsRejection) : DefinitionsParseResult
}

/** Reads the text of a RabbitMQ 3.x or 4.x definitions export into a [DefinitionsDocument]. Pure; never throws for bad input. */
object DefinitionsParser {

    private val KNOWN_SECTIONS = setOf(
        "queues", "exchanges", "bindings", "policies", "parameters", "users", "vhosts",
        "permissions", "topic_permissions", "rabbit_version", "rabbitmq_version",
    )

    fun parse(text: String): DefinitionsParseResult {
        JsonNestingScan.findTooDeep(text)?.let { return rejectTooDeep(it) }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return reject(DefinitionsRejection.NotJson)
        } catch (_: IllegalArgumentException) {
            return reject(DefinitionsRejection.NotJson)
        }
        return when {
            root !is JsonObject -> reject(DefinitionsRejection.NotDefinitionsFile)
            root.isEmpty() -> reject(DefinitionsRejection.NoDefinitions)
            root.keys.none { it in KNOWN_SECTIONS } -> reject(DefinitionsRejection.NotDefinitionsFile)
            else -> read(root)
        }
    }

    private fun read(root: JsonObject): DefinitionsParseResult = try {
        DefinitionsReader(root).read()
    } catch (e: MalformedSectionException) {
        reject(DefinitionsRejection.Malformed(e.section))
    }

    /** Too deep to read safely: reported like a too-deep argument when it sits in a known section, else as not JSON. */
    private fun rejectTooDeep(tooDeep: JsonNestingScan.TooDeep): DefinitionsParseResult.Rejected {
        val section = tooDeep.section?.takeIf { it in KNOWN_SECTIONS }
        return reject(if (section == null) DefinitionsRejection.NotJson else DefinitionsRejection.Malformed(section))
    }

    private fun reject(reason: DefinitionsRejection) = DefinitionsParseResult.Rejected(reason)
}
