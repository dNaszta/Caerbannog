package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** settings.json is a flat object; the app's own small files stay far below the reader's failure point near 10,000. */
private const val MAX_FILE_DEPTH = 32

/**
 * [AppSettings] kept in `settings.json` (`{"streamListRefreshSeconds": 5}`). The value is read once, when the object is made,
 * and held in memory afterwards, so a failing disk never changes what the running app sees.
 *
 * Reading never throws and never writes: a missing, empty, corrupt or unrecognised value reads as [DEFAULT_REFRESH]; `0` is Off.
 * Writing keeps every other key of the file (settings of later versions survive), goes through a temp file in the same folder
 * and a move, so a reader never sees half a file, and a failure is passed to [log] and not thrown (settings are never worth a crash).
 * The file holds no secret.
 */
class JsonFileAppSettings(
    private val file: File,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AppSettings {
    private val json = Json { prettyPrint = true }

    @Volatile
    private var current: RefreshInterval = readInterval()

    override var streamListRefresh: RefreshInterval
        get() = current
        set(value) {
            current = value
            write(value)
        }

    private fun readInterval(): RefreshInterval {
        val seconds = (readObject()?.get(KEY) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return seconds?.let { RefreshInterval.fromSeconds(it) } ?: DEFAULT_REFRESH
    }

    // Null for a missing, empty or unreadable file or one that is not a JSON object.
    private fun readObject(): JsonObject? = try {
        if (file.isFile) readTree(file.readText()) else null
    } catch (e: Exception) {
        // Corrupt JSON (SerializationException) or an unreadable file: fall back, the next write replaces it.
        log("settings.json could not be read, using defaults: ${e.javaClass.simpleName}")
        null
    }

    private fun readTree(text: String): JsonObject? = when (val parsed = parseUntrustedJson(text, MAX_FILE_DEPTH, json)) {
        is UntrustedJson.Parsed -> parsed.element.asObjectOrNull()
        UntrustedJson.TooDeep -> {
            log("settings.json is nested too deep, using defaults")
            null
        }
        UntrustedJson.NotJson -> {
            log("settings.json is not valid JSON, using defaults")
            null
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun write(value: RefreshInterval) {
        var temp: File? = null
        try {
            val content = JsonObject((readObject().orEmpty()) + (KEY to JsonPrimitive(value.seconds)))
            val folder = file.absoluteFile.parentFile
            folder.mkdirs()
            val tmp = File.createTempFile("settings", ".tmp", folder).also { temp = it }
            tmp.writeText(json.encodeToString(JsonObject.serializer(), content))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log("settings.json could not be written, the setting is kept for this run only: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            temp?.delete()
        }
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    companion object {
        private const val KEY = "streamListRefreshSeconds"

        /** `~/.caerbannog/settings.json`, next to `connections.json`. */
        fun defaultFile(): File = File(System.getProperty("user.home"), ".caerbannog/settings.json")
    }
}
