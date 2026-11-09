package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.MigrationFolderStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** settings.json is a flat object; the app's own small files stay far below the reader's failure point near 10,000. */
private const val MAX_FILE_DEPTH = 32

/**
 * [AppSettings] and [MigrationFolderStore] kept in `settings.json` (`{"streamListRefreshSeconds": 5, "migrationsFolder": "/path"}`). The value is read once, when the object is made,
 * and held in memory afterwards, so a failing disk never changes what the running app sees.
 *
 * Reading never throws and never writes: a missing, empty, corrupt or unrecognised value reads as [DEFAULT_REFRESH] (and as no migrations folder); `0` is Off.
 * Writing keeps every other key of the file (settings of later versions survive), goes through a temp file in the same folder
 * and a move, so a reader never sees half a file, and a failure is passed to [log] and not thrown (settings are never worth a crash).
 * The file holds no secret.
 */
class JsonFileAppSettings(
    private val file: File,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AppSettings, MigrationFolderStore {
    private val json = Json { prettyPrint = true }

    private val initial: JsonObject? = readObject()

    @Volatile
    private var current: RefreshInterval = readInterval(initial)

    @Volatile
    private var currentFolder: String? = readFolder(initial)

    override var streamListRefresh: RefreshInterval
        get() = current
        set(value) {
            current = value
            write(KEY to JsonPrimitive(value.seconds))
        }

    /** The folder of the migration files; null (ask the user) when never set, set to null, or when the file holds no usable text there. */
    override var folder: String?
        get() = currentFolder
        set(value) {
            currentFolder = value
            write(FOLDER_KEY to (value?.let { JsonPrimitive(it) } ?: JsonNull))
        }

    private fun readInterval(root: JsonObject?): RefreshInterval {
        val seconds = (root?.get(KEY) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return seconds?.let { RefreshInterval.fromSeconds(it) } ?: DEFAULT_REFRESH
    }

    private fun readFolder(root: JsonObject?): String? =
        (root?.get(FOLDER_KEY) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

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

    // A null value removes the key. The other keys are kept: the file is read again so a setting of a later version survives.
    private fun write(change: Pair<String, JsonElement>) {
        try {
            val (key, value) = change
            val others = readObject().orEmpty() - key
            val content = JsonObject(if (value is JsonNull) others else others + change)
            file.absoluteFile.parentFile.mkdirs()
            AtomicFileWriter.write(file.toPath(), json.encodeToString(JsonObject.serializer(), content).toByteArray(Charsets.UTF_8), NioFileMover)
        } catch (e: Exception) {
            log("settings.json could not be written, the setting is kept for this run only: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    companion object {
        private const val KEY = "streamListRefreshSeconds"
        private const val FOLDER_KEY = "migrationsFolder"

        /** `~/.caerbannog/settings.json`, next to `connections.json`. */
        fun defaultFile(): File = File(System.getProperty("user.home"), ".caerbannog/settings.json")
    }
}
