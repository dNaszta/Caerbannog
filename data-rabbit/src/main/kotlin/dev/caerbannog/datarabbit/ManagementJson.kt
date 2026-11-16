package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.SuperStreamLayout
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.InspectedQueueMessage
import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.VhostRow
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses the management API bodies into core's data classes. A body that is not what the spec records is
 * [CatalogResult.Unexpected], never an exception; unknown keys are ignored. Field names are the ones recorded in
 * `fixtures/m2-02/`; nothing here is guessed.
 */
internal object ManagementJson {

    /** Broker bodies echo user-chosen structure (queue arguments, policy definitions): real data nests below about 20, the reader fails near 10,000. */
    private const val MAX_BODY_DEPTH = 128

    private const val QUEUE_TYPE_ARGUMENT = "x-queue-type"

    // Thrown inside the parsers only, turned into Unexpected at the public boundary.
    private class Malformed : RuntimeException()

    private fun malformed(): Nothing = throw Malformed()

    private inline fun <T> guarded(body: String, parse: (JsonElement) -> CatalogResult<T>): CatalogResult<T> =
        when (val parsed = parseUntrustedJson(body, MAX_BODY_DEPTH)) {
            is UntrustedJson.Parsed -> try {
                parse(parsed.element)
            } catch (e: Malformed) {
                CatalogResult.Unexpected
            } catch (e: SerializationException) {
                CatalogResult.Unexpected
            } catch (e: IllegalArgumentException) {
                CatalogResult.Unexpected
            }
            UntrustedJson.TooDeep, UntrustedJson.NotJson -> CatalogResult.Unexpected
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

    private const val SUPER_STREAM_MARKER = "x-super-stream"
    private const val PARTITION_ORDER = "x-stream-partition-order"

    // A super stream is a `direct` exchange whose marker is the JSON boolean true (spike S-A, S-B: false, "true", fanout are not).
    private fun superStreamNames(exchangesBody: String): CatalogResult<List<String>> = guarded(exchangesBody) { root ->
        val names = root.asArray().map { it.asObject() }.mapNotNull { exchange ->
            exchange.requiredString("name").takeIf { isSuperStream(exchange) }
        }
        CatalogResult.Success(names)
    }

    // The one rule for "this exchange is a super stream", shared by the stream list and the Topology Exchanges tab.
    private fun isSuperStream(exchange: JsonObject): Boolean {
        val marker = (exchange["arguments"] as? JsonObject)?.get(SUPER_STREAM_MARKER) as? JsonPrimitive
        return exchange.string("type") == "direct" && marker != null && !marker.isString && marker.booleanOrNull == true
    }

    /** M2-07: true when some exchange of `GET /api/exchanges/{vhost}` is a super stream, so the bindings call is worth making. */
    fun hasSuperStreamExchange(exchangesBody: String): CatalogResult<Boolean> = when (val names = superStreamNames(exchangesBody)) {
        is CatalogResult.Success -> CatalogResult.Success(names.value.isNotEmpty())
        is CatalogResult.Failure -> names
    }

    private class Binding(val source: String, val destination: String, val routingKey: String, val order: Double?)

    /**
     * M2-07: the super streams of a vhost from `GET /api/exchanges/{vhost}` and `GET /api/bindings/{vhost}`. Partitions are the queue
     * destinations of the bindings of the exchange, numbered ones first by `x-stream-partition-order` (a value that is not a number
     * counts as none), then the rest by routing key and destination; a queue bound twice appears once, at its first place.
     */
    fun parseSuperStreams(vhost: String, exchangesBody: String, bindingsBody: String): CatalogResult<List<SuperStreamLayout>> {
        val names = when (val parsed = superStreamNames(exchangesBody)) {
            is CatalogResult.Success -> parsed.value
            is CatalogResult.Failure -> return parsed
        }
        val bindings = when (val parsed = parseBindings(bindingsBody)) {
            is CatalogResult.Success -> parsed.value
            is CatalogResult.Failure -> return parsed
        }
        val byOrder = compareBy<Binding, Double?>(nullsLast()) { it.order }.thenBy { it.routingKey }.thenBy { it.destination }
        return CatalogResult.Success(
            names.map { name ->
                val partitions = bindings.filter { it.source == name }.sortedWith(byOrder).map { it.destination }.distinct()
                SuperStreamLayout(name, vhost, partitions)
            },
        )
    }

    private fun parseBindings(body: String): CatalogResult<List<Binding>> = guarded(body) { root ->
        val queueBindings = root.asArray().map { it.asObject() }.filter { it.requiredString("destination_type") == "queue" }
        CatalogResult.Success(
            queueBindings.map { binding ->
                val order = ((binding["arguments"] as? JsonObject)?.get(PARTITION_ORDER) as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.doubleOrNull
                Binding(binding.requiredString("source"), binding.requiredString("destination"), binding.string("routing_key").orEmpty(), order)
            },
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

    /** D8: publisher rows of a whole vhost counted per stream (`queue.name`); streams without a row are absent. */
    fun parsePublisherCounts(body: String): CatalogResult<Map<String, Int>> = guarded(body) { root ->
        CatalogResult.Success(
            root.asArray().map { it.asObject()["queue"].asObject().requiredString("name") }.groupingBy { it }.eachCount(),
        )
    }

    /**
     * The broker has no per-stream consumers path, so the rows are kept by the exact name of their queue. A lag that is missing or not a
     * whole number stays `null`; `core` shows such a row as updating.
     */
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
                    offsetLag = row.long("offset_lag"),
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

    // --- M2-08a topology (fields recorded in fixtures/m2-08 and m2-07) ---

    /** The vhost comes from the caller: the list is asked with `columns`, which carries none. Streams are dropped here, counts stay the broker's. */
    fun parseQueuePage(body: String, vhost: String): CatalogResult<QueuePage> = guarded(body) { root ->
        val obj = root.asObject()
        val rows = obj["items"].asArray().map { it.asObject() }.filter { it.string("type") != "stream" }.map { queueRow(it, vhost) }
        CatalogResult.Success(
            QueuePage(
                rows = rows,
                page = obj.requiredInt("page"),
                pageCount = obj.requiredInt("page_count"),
                pageSize = obj.requiredInt("page_size"),
                totalCount = obj.requiredInt("total_count"),
            ),
        )
    }

    /** One queue object (vhost from the body); a stream is no queue here, so [CatalogResult.NotFound]. */
    fun parseQueueDetail(body: String): CatalogResult<QueueRow> = guarded(body) { root ->
        val obj = root.asObject()
        if (obj.string("type") == "stream") return@guarded CatalogResult.NotFound
        CatalogResult.Success(queueRow(obj, obj.requiredString("vhost")))
    }

    fun parseExchanges(body: String): CatalogResult<List<ExchangeRow>> = guarded(body) { root ->
        CatalogResult.Success(
            root.asArray().map { it.asObject() }.map { e ->
                ExchangeRow(
                    name = e.requiredString("name"),
                    vhost = e.requiredString("vhost"),
                    type = e.requiredString("type"),
                    durable = e.boolean("durable"),
                    autoDelete = e.boolean("auto_delete"),
                    internal = e.boolean("internal"),
                    arguments = e.argumentText(),
                    superStream = isSuperStream(e),
                    typedArguments = e.typedArguments(),
                )
            },
        )
    }

    fun parseBindingList(body: String): CatalogResult<List<BindingRow>> = guarded(body) { root ->
        CatalogResult.Success(
            root.asArray().map { it.asObject() }.map { b ->
                BindingRow(
                    source = b.requiredString("source"),
                    vhost = b.requiredString("vhost"),
                    destination = b.requiredString("destination"),
                    destinationType = when (b.requiredString("destination_type")) {
                        "queue" -> BindingDestinationType.QUEUE
                        "exchange" -> BindingDestinationType.EXCHANGE
                        else -> malformed()
                    },
                    routingKey = b.string("routing_key").orEmpty(),
                    arguments = b.argumentText(),
                    typedArguments = b.typedArguments(),
                )
            },
        )
    }

    /**
     * M9-14: one page of the declarations (name, type, flags, typed arguments) of the list asked with the export columns; streams are KEPT. Every field is
     * required and typed: a flag that is missing or not a JSON boolean is [CatalogResult.Unexpected], nothing is guessed. `x-queue-type` is left out of
     * `arguments` (the type says it; measured: the broker adds no other argument).
     */
    fun parseQueueDeclarations(body: String, vhost: String): CatalogResult<QueueDeclarationPage> = guarded(body) { root ->
        val obj = root.asObject()
        val rows = obj["items"].asArray().map { it.asObject() }.map { q ->
            QueueDeclaration(
                name = q.requiredString("name"),
                vhost = vhost,
                type = q.requiredString("type"),
                durable = q.requiredBoolean("durable"),
                autoDelete = q.requiredBoolean("auto_delete"),
                exclusive = q.requiredBoolean("exclusive"),
                arguments = q.typedArguments() - QUEUE_TYPE_ARGUMENT,
            )
        }
        CatalogResult.Success(QueueDeclarationPage(rows, obj.requiredInt("page"), obj.requiredInt("page_count")))
    }

    /** M9-14: the policies of `GET /api/policies/{vhost}` or the operator policies of `GET /api/operator-policies/{vhost}` (same shape); every field is required. */
    fun parsePolicies(body: String): CatalogResult<List<PolicyRow>> = guarded(body) { root ->
        CatalogResult.Success(
            root.asArray().map { it.asObject() }.map { p ->
                PolicyRow(
                    name = p.requiredString("name"),
                    vhost = p.requiredString("vhost"),
                    pattern = p.requiredString("pattern"),
                    applyTo = p.requiredString("apply-to"),
                    priority = p.requiredInt("priority"),
                    definition = (p["definition"].asObject()).mapValues { (_, v) -> ArgumentValueJson.parse(v) ?: malformed() },
                )
            },
        )
    }

    /** Only `name` is read; the rest of each object is the broker's statistics. */
    fun parseVhosts(body: String): CatalogResult<List<VhostRow>> = guarded(body) { root ->
        CatalogResult.Success(root.asArray().map { VhostRow(it.asObject().requiredString("name")) })
    }

    /**
     * M7-08: Parses the array returned by `POST /api/queues/{vhost}/{name}/get` into [InspectedQueueMessage] items.
     * Supports both `"string"` and `"base64"` payload encodings, extracts standard AMQP `properties` and `properties.headers`,
     * and marks every message as `acknowledged = false` (`ack_requeue_true`).
     */
    fun parseInspectedQueueMessages(body: String): CatalogResult<List<InspectedQueueMessage>> = guarded(body) { root ->
        val items = root.asArray().mapIndexed { idx, elem ->
            val obj = elem.asObject()
            val payloadStr = obj.string("payload").orEmpty()
            val encoding = obj.string("payload_encoding") ?: "string"
            val payloadBytesArray = if (encoding.equals("base64", ignoreCase = true)) {
                try {
                    Base64.getDecoder().decode(payloadStr)
                } catch (_: IllegalArgumentException) {
                    payloadStr.encodeToByteArray()
                }
            } else {
                payloadStr.encodeToByteArray()
            }
            val reportedBytes = obj.long("payload_bytes") ?: payloadBytesArray.size.toLong()
            val redelivered = obj.boolean("redelivered")
            val exchange = obj.string("exchange").orEmpty()
            val routingKey = obj.string("routing_key").orEmpty()
            val messageCount = obj.long("message_count")
            val propsObj = obj["properties"] as? JsonObject
            val headersObj = propsObj?.get("headers") as? JsonObject
            val propsMap = buildMap {
                propsObj?.forEach { (k, v) ->
                    if (k != "headers") {
                        val strVal = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                        if (strVal != "null") put(k, strVal)
                    }
                }
            }
            val headersMap = buildMap {
                headersObj?.forEach { (k, v) ->
                    val strVal = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                    if (strVal != "null") put(k, strVal)
                }
            }
            InspectedQueueMessage(
                index = idx + 1,
                payload = payloadBytesArray,
                payloadBytes = reportedBytes,
                payloadEncoding = encoding,
                redelivered = redelivered,
                exchange = exchange,
                routingKey = routingKey,
                messageCount = messageCount,
                properties = propsMap,
                headers = headersMap,
                acknowledged = false,
                ackMode = QueueInspectionConstants.ACK_REQUEUE_TRUE,
            )
        }
        CatalogResult.Success(items)
    }

    private val MQTT_SUBSCRIPTION_REGEX = Regex("""^mqtt-subscription-(.*)qos([0-2])$""")

    /**
     * M6-02: Parses active MQTT connections from `/api/connections` and correlates them with topic
     * subscriptions from `/api/bindings/{vhost}`.
     */
    fun parseMqttClients(
        connectionsBody: String,
        bindingsBody: String? = null,
        vhost: String? = null,
    ): CatalogResult<List<MqttClientRow>> = guarded(connectionsBody) { root ->
        val subscriptionsByClient = mutableMapOf<String, MutableList<MqttSubscription>>()
        if (!bindingsBody.isNullOrBlank()) {
            try {
                // Too deep or not JSON: no subscription data, the connections are still listed.
                val bindingsRoot = (parseUntrustedJson(bindingsBody, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element
                val bindingObjects = (bindingsRoot as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                for (b in bindingObjects) {
                    if (vhost != null && b.string("vhost") != null && b.string("vhost") != vhost) continue
                    val dest = b.string("destination").orEmpty()
                    if (!dest.startsWith("mqtt-subscription-")) continue
                    val match = MQTT_SUBSCRIPTION_REGEX.matchEntire(dest)
                    val (clientId, qos) = if (match != null) {
                        match.groupValues[1] to match.groupValues[2].toInt()
                    } else {
                        dest.removePrefix("mqtt-subscription-") to 0
                    }
                    val routingKey = b.string("routing_key").orEmpty()
                    val topic = routingKey.replace('.', '/').replace('*', '+')
                    subscriptionsByClient.getOrPut(clientId) { mutableListOf() }
                        .add(MqttSubscription(topic = topic, qos = qos))
                }
            } catch (_: Exception) {
                // Ignore bindings parsing failures: subscriptions stay empty
            }
        }

        val connList = root.asArray().mapNotNull { it as? JsonObject }
            .filter { conn ->
                val protocol = conn.string("protocol").orEmpty()
                protocol.startsWith("MQTT", ignoreCase = true) &&
                    (vhost == null || conn.string("vhost") == null || conn.string("vhost") == vhost)
            }

        val seenClientIds = mutableSetOf<String>()
        val activeClients = connList.map { conn ->
            val clientProps = conn["client_properties"] as? JsonObject
            val connectionName = conn.string("name")
            val clientId = clientProps?.string("client_id")
                ?: clientProps?.string("connection_name")
                ?: connectionName.orEmpty()
            seenClientIds += clientId

            val connVhost = conn.string("vhost") ?: "/"
            val user = conn.string("user").orEmpty()
            val peerHost = conn.string("peer_host").orEmpty()
            val peerPort = (conn["peer_port"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            val protocol = conn.string("protocol").orEmpty()
            val connectedAt = (conn["connected_at"] as? JsonPrimitive)?.longOrNull
            val ssl = (conn["ssl"] as? JsonPrimitive)?.booleanOrNull ?: false
            val cleanSession = (clientProps?.get("clean_start") as? JsonPrimitive)?.booleanOrNull
                ?: (clientProps?.get("clean_session") as? JsonPrimitive)?.booleanOrNull

            val subs = subscriptionsByClient[clientId].orEmpty().distinct()

            MqttClientRow(
                clientId = clientId,
                connectionName = connectionName,
                vhost = connVhost,
                username = user,
                peerHost = peerHost,
                peerPort = peerPort,
                protocol = protocol,
                connectedAt = connectedAt,
                ssl = ssl,
                cleanSession = cleanSession,
                subscriptions = subs,
                connected = true,
            )
        }

        val offlineClients = subscriptionsByClient
            .filterKeys { it !in seenClientIds }
            .map { (clientId, subs) ->
                MqttClientRow(
                    clientId = clientId,
                    connectionName = null,
                    vhost = vhost ?: "/",
                    username = "",
                    peerHost = "",
                    peerPort = 0,
                    protocol = "MQTT",
                    connectedAt = null,
                    ssl = false,
                    cleanSession = false,
                    subscriptions = subs.distinct(),
                    connected = false,
                )
            }

        CatalogResult.Success(activeClients + offlineClients)
    }

    // A count the broker has not sent (or sent as text) is null, never 0: the screen shows "n/a".
    private fun queueRow(obj: JsonObject, vhost: String): QueueRow = QueueRow(
        name = obj.requiredString("name"),
        vhost = vhost,
        type = obj.string("type").orEmpty(),
        readyMessages = obj.long("messages_ready"),
        unackedMessages = obj.long("messages_unacknowledged"),
        consumers = obj.long("consumers")?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt(),
        leader = obj.string("leader"),
        members = obj.strings("members"),
        online = obj.strings("online"),
        policy = obj.string("policy"),
        state = obj.string("state"),
    )

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: malformed()

    // M9-14: the arguments with their JSON types; absent is empty, anything but an object, or a value the model refuses, is malformed.
    private fun JsonObject.typedArguments(): Map<String, ArgumentValue> {
        val element = this["arguments"] ?: return emptyMap()
        if (element is JsonNull) return emptyMap()
        return element.asObject().mapValues { (_, v) -> ArgumentValueJson.parse(v) ?: malformed() }
    }

    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    // A string as is, any other value as its JSON text (`true`, `1`, `[1,2]`).
    private fun JsonObject.argumentText(): Map<String, String> =
        (this["arguments"] as? JsonObject)?.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else v.toString() } ?: emptyMap()
}

/** One page of [ManagementJson.parseQueueDeclarations]: [page] and [pageCount] are the broker's, 1-based; an empty vhost has page 1 of 0. */
internal data class QueueDeclarationPage(val rows: List<QueueDeclaration>, val page: Int, val pageCount: Int)
