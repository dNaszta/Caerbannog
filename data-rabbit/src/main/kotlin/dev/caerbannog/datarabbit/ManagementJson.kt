package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses the management API bodies into core's data classes. A body that is not what the spec records is
 * [CatalogResult.Unexpected], never an exception; unknown keys are ignored. Field names are the ones recorded in
 * `fixtures/m2-02/`; nothing here is guessed.
 */
internal object ManagementJson {

    // Thrown inside the parsers only, turned into Unexpected at the public boundary.
    private class Malformed : RuntimeException()

    private fun malformed(): Nothing = throw Malformed()

    private inline fun <T> guarded(body: String, parse: (JsonElement) -> CatalogResult<T>): CatalogResult<T> = try {
        parse(Json.parseToJsonElement(body))
    } catch (e: Malformed) {
        CatalogResult.Unexpected
    } catch (e: SerializationException) {
        CatalogResult.Unexpected
    } catch (e: IllegalArgumentException) {
        CatalogResult.Unexpected
    }

    fun parseStreamPage(body: String): CatalogResult<StreamPage> = guarded(body) { root ->
        val obj = root.asObject()
        val items = obj["items"].asArray()
        val rows = items.map { it.asObject() }.filter { it.string("type") == "stream" }.map(::summary)
        CatalogResult.Success(
            StreamPage(
                rows = rows,
                page = obj.requiredInt("page"),
                pageCount = obj.requiredInt("page_count"),
                pageSize = obj.requiredInt("page_size"),
                filteredCount = obj.requiredInt("filtered_count"),
                itemCount = obj.requiredInt("item_count"),
                totalCount = obj.requiredInt("total_count"),
            ),
        )
    }

    fun parseStreamDetail(body: String): CatalogResult<StreamSummary> = guarded(body) { root ->
        val obj = root.asObject()
        // A queue that is not a stream is no stream detail; a missing type is tolerated.
        if (obj["type"] != null && obj.string("type") != "stream") return@guarded CatalogResult.NotFound
        CatalogResult.Success(summary(obj))
    }

    // Measured against RabbitMQ 4 (M2-03 broker run): for a few seconds after a client attaches the row carries
    // `"connection_details":{}` and zero counters, so the connection name is "" until the broker fills it in.
    fun parsePublishers(body: String): CatalogResult<List<PublisherRow>> = guarded(body) { root ->
        CatalogResult.Success(
            root.asArray().map { it.asObject() }.map { row ->
                val connection = row["connection_details"] as? JsonObject
                PublisherRow(
                    connectionName = connection?.string("name").orEmpty(),
                    user = connection?.string("user"),
                    publisherId = row.requiredInt("publisher_id"),
                    reference = row.string("reference"),
                    published = row.long("published") ?: 0,
                    confirmed = row.long("confirmed") ?: 0,
                    errored = row.long("errored") ?: 0,
                )
            },
        )
    }

    /** The broker has no per-stream consumers path, so the rows are kept by the exact name of their queue. */
    fun parseConsumers(body: String, stream: String): CatalogResult<List<ConsumerRow>> = guarded(body) { root ->
        val rows = root.asArray().map { it.asObject() }
            .filter { it["queue"].asObject().requiredString("name") == stream }
            .map { row ->
                val connection = row["connection_details"] as? JsonObject
                ConsumerRow(
                    connectionName = connection?.string("name").orEmpty(),
                    user = connection?.string("user"),
                    subscriptionId = row.requiredInt("subscription_id"),
                    name = (row["properties"] as? JsonObject)?.string("name"),
                    active = (row["active"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    activityStatus = row.string("activity_status"),
                    consumed = row.long("consumed") ?: 0,
                    credits = (row.long("credits") ?: 0).toInt(),
                    offset = row.long("offset") ?: 0,
                    offsetLag = row.long("offset_lag") ?: 0,
                )
            }
        CatalogResult.Success(rows)
    }

    /** `tags` is a list in RabbitMQ 4; older brokers send one comma separated string, which is read too. */
    fun parseWhoamiTags(body: String): CatalogResult<List<String>> = guarded(body) { root ->
        val tags: List<String> = when (val element = root.asObject()["tags"]) {
            is JsonArray -> element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: malformed() }
            is JsonPrimitive -> if (element.isString) element.content.split(",").map { it.trim() }.filter { it.isNotEmpty() } else malformed()
            else -> malformed()
        }
        CatalogResult.Success(tags)
    }

    private fun summary(obj: JsonObject): StreamSummary = StreamSummary(
        name = obj.requiredString("name"),
        vhost = obj.requiredString("vhost"),
        messages = obj.long("messages"),
        consumers = obj.long("consumers")?.toInt() ?: 0,
        segments = obj.long("segments")?.toInt(),
        firstTimestamp = obj.long("first_timestamp"),
        leader = obj.string("leader"),
        node = obj.string("node"),
        members = obj.strings("members"),
        online = obj.strings("online"),
        retention = retention(obj["arguments"] as? JsonObject),
    )

    private fun retention(arguments: JsonObject?): Retention = Retention(
        maxAge = arguments?.string("x-max-age"),
        maxLengthBytes = arguments?.long("x-max-length-bytes"),
        segmentSizeBytes = arguments?.long("x-stream-max-segment-size-bytes"),
    )

    private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: malformed()

    private fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: malformed()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.requiredString(key: String): String = string(key) ?: malformed()

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.requiredInt(key: String): Int = long(key)?.toInt() ?: malformed()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
}
