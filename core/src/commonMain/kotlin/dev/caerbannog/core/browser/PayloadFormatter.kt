package dev.caerbannog.core.browser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Kind of presentation view for a message payload.
 */
enum class PayloadViewKind {
    TEXT,
    JSON,
    HEX,
}

/**
 * Outcome of attempting to parse and format a payload as JSON.
 */
sealed interface JsonFormatResult {
    data class Valid(val formattedText: String) : JsonFormatResult
    data class Invalid(val message: String) : JsonFormatResult
}

/**
 * Detailed inspection model for a selected [StreamMessage].
 */
data class PayloadInspection(
    val offset: Long,
    val timestamp: Long,
    val currentKind: PayloadViewKind,
    val recommendedKind: PayloadViewKind,
    val availableKinds: Set<PayloadViewKind>,
    val displayText: String,
    val validationWarning: String? = null,
    val isCapped: Boolean = false,
    val canShowAll: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
    val applicationProperties: Map<String, String> = emptyMap(),
    val exchange: String? = null,
    val routingKey: String? = null,
)

/**
 * Formats stream message payloads into Text, JSON, and Hex views with binary detection,
 * invalid JSON handling, display capping, and copy support.
 */
@OptIn(ExperimentalSerializationApi::class)
class PayloadFormatter(
    val defaultCapLimit: Int = DEFAULT_CAP_LIMIT,
) {
    companion object {
        const val DEFAULT_CAP_LIMIT = 65_536
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun byteToHex(b: Byte): String {
            val i = b.toInt() and 0xFF
            return "" + HEX_CHARS[i ushr 4] + HEX_CHARS[i and 0x0F]
        }

        private fun offsetToHex(offset: Int): String {
            val chars = CharArray(8)
            var v = offset
            for (i in 7 downTo 0) {
                chars[i] = HEX_CHARS[v and 0x0F]
                v = v ushr 4
            }
            return String(chars)
        }
    }

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Determines whether [payload] consists of binary (non-UTF8 or non-whitespace ISO control) data.
     */
    fun isBinary(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val text = try {
            payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            return true
        }
        return text.any { ch -> ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' }
    }

    /**
     * Formats [payload] as UTF-8 text if valid, or null if binary/invalid.
     */
    fun formatText(payload: ByteArray): String? {
        if (isBinary(payload)) return null
        return try {
            payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Attempts to parse [payload] as JSON and returns formatted indented text,
     * or an invalid outcome with an error message.
     */
    fun formatJson(payload: ByteArray): JsonFormatResult {
        val text = try {
            payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            return JsonFormatResult.Invalid("The body is not valid JSON (non-UTF8 characters)")
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return JsonFormatResult.Invalid("The body is not valid JSON")
        }
        return try {
            val element = json.parseToJsonElement(trimmed)
            if (element !is JsonObject && element !is JsonArray) {
                return JsonFormatResult.Invalid("The body is not valid JSON")
            }
            JsonFormatResult.Valid(json.encodeToString(JsonElement.serializer(), element))
        } catch (_: Exception) {
            JsonFormatResult.Invalid("The body is not valid JSON")
        }
    }

    /**
     * Formats [payload] into a hex dump view with offset, hex byte pairs, and ASCII representation.
     */
    fun formatHex(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        return payload.asSequence()
            .chunked(16)
            .mapIndexed { lineIndex, chunk ->
                val hex = chunk.joinToString(" ") { byteToHex(it) }
                val ascii = chunk.map { b ->
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c in ' '..'~') c else '.'
                }.joinToString("")
                val offsetHex = offsetToHex(lineIndex * 16)
                val paddedHex = hex.padEnd(48, ' ')
                "$offsetHex: $paddedHex |$ascii|"
            }
            .joinToString("\n")
    }

    /**
     * Inspects a [StreamMessage] and computes display text, available tabs, warnings, and capping.
     */
    fun inspect(
        message: StreamMessage,
        requestedKind: PayloadViewKind? = null,
        capLimit: Int = defaultCapLimit,
        showAll: Boolean = false,
    ): PayloadInspection {
        val binary = isBinary(message.payload)
        val contentType = (message.properties["content-type"] ?: message.properties["contentType"])?.toString().orEmpty()
        val claimsJson = contentType.contains("json", ignoreCase = true)

        val availableKinds: Set<PayloadViewKind>
        val recommendedKind: PayloadViewKind

        if (binary) {
            availableKinds = setOf(PayloadViewKind.HEX)
            recommendedKind = PayloadViewKind.HEX
        } else {
            val jsonCheck = formatJson(message.payload)
            val isJson = jsonCheck is JsonFormatResult.Valid
            val kinds = mutableSetOf(PayloadViewKind.TEXT, PayloadViewKind.HEX)
            if (claimsJson || isJson) {
                kinds.add(PayloadViewKind.JSON)
            }
            availableKinds = kinds
            recommendedKind = when {
                claimsJson && isJson -> PayloadViewKind.JSON
                claimsJson -> PayloadViewKind.JSON
                isJson -> PayloadViewKind.JSON
                else -> PayloadViewKind.TEXT
            }
        }

        val effectiveKind = when {
            requestedKind != null && requestedKind in availableKinds -> requestedKind
            requestedKind != null && !binary && requestedKind == PayloadViewKind.JSON -> PayloadViewKind.JSON
            else -> recommendedKind
        }

        var validationWarning: String? = null
        val fullContent = when (effectiveKind) {
            PayloadViewKind.TEXT -> formatText(message.payload) ?: formatHex(message.payload)
            PayloadViewKind.JSON -> {
                when (val result = formatJson(message.payload)) {
                    is JsonFormatResult.Valid -> result.formattedText
                    is JsonFormatResult.Invalid -> {
                        validationWarning = result.message
                        formatText(message.payload) ?: result.message
                    }
                }
            }
            PayloadViewKind.HEX -> formatHex(message.payload)
        }

        val canShowAll = fullContent.length > capLimit
        val isCapped = !showAll && canShowAll
        val displayText = if (isCapped) fullContent.take(capLimit) else fullContent

        val standardProps = message.properties.mapValues { it.value?.toString().orEmpty() }
        val appProps = message.applicationProperties.mapValues { it.value?.toString().orEmpty() }

        return PayloadInspection(
            offset = message.offset,
            timestamp = message.timestamp,
            currentKind = effectiveKind,
            recommendedKind = recommendedKind,
            availableKinds = availableKinds,
            displayText = displayText,
            validationWarning = validationWarning,
            isCapped = isCapped,
            canShowAll = canShowAll,
            properties = standardProps,
            applicationProperties = appProps,
            exchange = message.exchange,
            routingKey = message.routingKey,
        )
    }

    /**
     * Extracts exact published message body.
     */
    fun copyBody(message: StreamMessage): String {
        return try {
            message.payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            formatHex(message.payload)
        }
    }

    /**
     * Formats message offset for copying.
     */
    fun copyOffset(message: StreamMessage): String {
        return message.offset.toString()
    }

    /**
     * Formats standard message properties and application properties (headers) for copying.
     */
    fun copyHeadersAndProperties(message: StreamMessage): String = buildString {
        appendLine("Offset: ${message.offset}")
        appendLine("Timestamp: ${message.timestamp}")
        message.exchange?.takeIf { it.isNotEmpty() }?.let { appendLine("Exchange: $it") }
        message.routingKey?.takeIf { it.isNotEmpty() }?.let { appendLine("Routing Key: $it") }
        if (message.properties.isNotEmpty()) {
            appendLine()
            appendLine("Properties:")
            for ((k, v) in message.properties) {
                appendLine("  $k: ${v ?: ""}")
            }
        }
        if (message.applicationProperties.isNotEmpty()) {
            appendLine()
            appendLine("Headers / Application Properties:")
            for ((k, v) in message.applicationProperties) {
                appendLine("  $k: ${v ?: ""}")
            }
        }
    }.trimEnd()
}
