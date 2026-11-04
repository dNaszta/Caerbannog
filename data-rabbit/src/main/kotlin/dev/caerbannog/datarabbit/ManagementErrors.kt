package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Maps a management API error status to a typed failure. The status alone decides; a body is never looked at or copied. */
internal object ManagementErrors {

    /** [on404] says what a 404 means for this call (the plugin probe: PluginMissing; a single stream: NotFound). */
    fun map(status: Int, on404: CatalogResult.Failure): CatalogResult.Failure = when (status) {
        400 -> CatalogResult.BadPaging
        401, 403 -> CatalogResult.NotAuthorised
        404 -> on404
        else -> CatalogResult.Unexpected
    }

    /** True only for the 401 body whose `reason` is exactly `Not management user` (M2-03a): that is not a failed Connect. */
    fun isNotManagementUser(body: String): Boolean {
        val reason = try {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("reason") as? JsonPrimitive
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        return reason != null && reason.isString && reason.content == "Not management user"
    }
}
