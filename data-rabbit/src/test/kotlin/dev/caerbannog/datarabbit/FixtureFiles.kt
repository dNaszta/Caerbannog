package dev.caerbannog.datarabbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** M2-03 part 2: reads the recorded broker bodies (`fixtures/m2-02/`, `fixtures/m2-03a/`) from the test classpath. */
internal object FixtureFiles {

    fun text(path: String): String {
        val stream: java.io.InputStream = FixtureFiles::class.java.classLoader.getResourceAsStream("fixtures/$path") ?: error("missing fixture fixtures/$path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** A body fixture of the form `{"status": n, "body": {...}}` (the error recordings): status and body text. */
    fun errorBody(path: String): Pair<Int, String> {
        val envelope: JsonObject = Json.parseToJsonElement(text(path)).jsonObject
        return envelope.getValue("status").jsonPrimitive.int to envelope.getValue("body").toString()
    }
}
