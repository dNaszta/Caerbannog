package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.Collections

/**
 * M5-08a: an honest in-memory model of the management API that a migration executor talks to (queues, exchanges, bindings, policies, operator
 * policies of 4.x), WITHOUT any HTTP library: the data-rabbit tests and the acceptance glue wrap [handle] in a Ktor mock engine. EVERY status and
 * every body shape comes from [ManagementStatusTable], which `RealBrokerM5MigrationTest` measures on a real 4.x broker; this class only decides
 * WHICH row applies. Other rules, each a measured fact: a name that decodes to `.` or `..` gives 405 and creates nothing; argument values are
 * compared as typed JSON (`5` and `"5"` differ); a PUT of a policy REPLACES an existing one (a test counts [policyWrites] to see an overwrite);
 * the properties key of a binding is its routing key quoted like a form (`~` for the empty one); a binding DELETE resolves its last segment the way
 * the real plugin does (see the comment at the DELETE); a path the migration executor must never send (more segments than the API has for a
 * resource, a path outside the five collections) is NOT answered with a status: [handle] throws, so the test fails loudly.
 * M9-05b: a repeated PUT of a queue or exchange compares `durable`, `auto_delete`, the type (and `internal` for an exchange) and ONLY the arguments
 * of [DeclareEquivalenceTable] (400 for a difference there, 204 and nothing stored for any other argument); `GET` of a queue or exchange named `.` or
 * `..` answers 200 with a JSON list. Honest limits: no statistics, no pagination, no permission model (use [answer] to script a 401 or 403), one node.
 */
class FakeManagementBroker {

    /** One request as it arrived: [path] is the raw encoded path without query. */
    data class Request(val method: String, val path: String, val body: String?) {
        val isWrite: Boolean get() = method != "GET"
        override fun toString(): String = "$method $path${body?.let { " $it" } ?: ""}"
    }

    data class Reply(val status: Int, val body: String = "")

    class ForbiddenPath(message: String) : IllegalStateException(message)

    private data class Key(val vhost: String, val name: String)
    private class Queue(val durable: Boolean, val autoDelete: Boolean, val arguments: JsonObject)
    private class Exchange(val type: String, val durable: Boolean, val autoDelete: Boolean, val internal: Boolean, val arguments: JsonObject)
    private data class Binding(val vhost: String, val source: String, val toExchange: Boolean, val destination: String, val routingKey: String, val arguments: JsonObject) {
        /** MEASURED: "~" for an empty key, otherwise the key quoted like a form (space is "+", "~" is %7E, "+" is %2B, "/" is %2F, "%" is %25); with arguments a hash follows. */
        val propertiesKey: String get() = (if (routingKey.isEmpty()) "~" else java.net.URLEncoder.encode(routingKey, Charsets.UTF_8)) + (if (arguments.isEmpty()) "" else "_" + arguments.hashCode())
    }
    private class Policy(val pattern: String, val applyTo: String, val priority: Int, val definition: JsonObject)

    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())
    private val vhosts = linkedSetOf("/")
    private val queues = linkedMapOf<Key, Queue>()
    private val exchanges = linkedMapOf<Key, Exchange>()
    private val bindings = mutableListOf<Binding>()
    private val policies = linkedMapOf<Key, Policy>()
    private val operatorPolicies = linkedMapOf<Key, Policy>()
    private val silent = mutableSetOf<Key>()
    private val rules = mutableListOf<Pair<(Request) -> Boolean, Reply?>>()

    /** Every PUT of a policy or operator policy that reached the broker. */
    var policyWrites: Int = 0
        private set

    /** Fault switch: a binding DELETE also removes another binding between the same source and destination (a broker bug the read-back must notice). */
    @Volatile var alsoRemovesSibling: Boolean = false

    /** When true every call fails like a refused connection (the engine throws an IOException). */
    @Volatile var unreachable: Boolean = false

    // ---- scripting ------------------------------------------------------------------------------------------

    /** Every later request with [method] and a path that equals [path] (raw, as sent) is answered with [status] and [body]; until [clearRules]. First rule wins. */
    fun answer(method: String, path: String, status: Int, body: String = ""): FakeManagementBroker = apply {
        rules += ({ r: Request -> r.method == method && r.path == path }) to Reply(status, body)
    }

    /** Like [answer] for every path that contains [part] (for example the encoded queue name). */
    fun answerContaining(method: String, part: String, status: Int, body: String = ""): FakeManagementBroker = apply {
        rules += ({ r: Request -> r.method == method && r.path.contains(part) }) to Reply(status, body)
    }

    fun clearRules() = rules.clear()

    /** The broker says 201 to the PUT of this queue and then does not have it (read-back must notice). */
    fun silentlyDoNotCreateQueue(vhost: String, name: String) { silent += Key(vhost, name) }

    // ---- seeding and inspection -----------------------------------------------------------------------------

    fun addVhost(name: String) { vhosts += name }

    fun addQueue(vhost: String, name: String, type: String = "quorum", durable: Boolean = true, autoDelete: Boolean = false, arguments: Map<String, JsonElement> = emptyMap()) {
        vhosts += vhost
        queues[Key(vhost, name)] = Queue(durable, autoDelete, JsonObject(mapOf("x-queue-type" to JsonPrimitive(type)) + arguments))
    }

    fun addExchange(vhost: String, name: String, type: String = "direct", durable: Boolean = true, autoDelete: Boolean = false, internal: Boolean = false, arguments: Map<String, JsonElement> = emptyMap()) {
        vhosts += vhost
        exchanges[Key(vhost, name)] = Exchange(type, durable, autoDelete, internal, JsonObject(arguments))
    }

    fun addBinding(vhost: String, source: String, destination: String, toExchange: Boolean = false, routingKey: String = "", arguments: Map<String, JsonElement> = emptyMap()) {
        bindings += Binding(vhost, source, toExchange, destination, routingKey, JsonObject(arguments))
    }

    fun addPolicy(vhost: String, name: String, pattern: String, definition: Map<String, JsonElement>, applyTo: String = "all", priority: Int = 0, operator: Boolean = false) {
        vhosts += vhost
        (if (operator) operatorPolicies else policies)[Key(vhost, name)] = Policy(pattern, applyTo, priority, JsonObject(definition))
    }

    fun hasQueue(vhost: String, name: String): Boolean = Key(vhost, name) in queues
    fun hasExchange(vhost: String, name: String): Boolean = Key(vhost, name) in exchanges
    fun hasPolicy(vhost: String, name: String, operator: Boolean = false): Boolean = Key(vhost, name) in (if (operator) operatorPolicies else policies)
    fun hasBinding(vhost: String, source: String, destination: String, routingKey: String = "", toExchange: Boolean = false): Boolean =
        bindings.any { it.vhost == vhost && it.source == source && it.destination == destination && it.routingKey == routingKey && it.toExchange == toExchange }

    /** The `arguments` of a queue as the broker holds them (including `x-queue-type`), null when there is no such queue. */
    fun queueArguments(vhost: String, name: String): JsonObject? = queues[Key(vhost, name)]?.arguments

    /** The definition of a policy as the broker holds it, null when absent. */
    fun policyDefinition(vhost: String, name: String, operator: Boolean = false): JsonObject? = (if (operator) operatorPolicies else policies)[Key(vhost, name)]?.definition

    /** Everything the broker holds, one line per resource, sorted: two equal snapshots mean an unchanged topology. */
    fun snapshot(): String = buildList {
        queues.forEach { (k, q) -> add("queue ${k.vhost} ${k.name} durable=${q.durable} autoDelete=${q.autoDelete} ${q.arguments}") }
        exchanges.forEach { (k, e) -> add("exchange ${k.vhost} ${k.name} ${e.type} durable=${e.durable} autoDelete=${e.autoDelete} internal=${e.internal} ${e.arguments}") }
        bindings.forEach { add("binding ${it.vhost} ${it.source} -> ${if (it.toExchange) "e" else "q"}:${it.destination} key=${it.routingKey} ${it.arguments}") }
        policies.forEach { (k, p) -> add("policy ${k.vhost} ${k.name} ${p.pattern} ${p.applyTo} ${p.priority} ${p.definition}") }
        operatorPolicies.forEach { (k, p) -> add("operator-policy ${k.vhost} ${k.name} ${p.pattern} ${p.applyTo} ${p.priority} ${p.definition}") }
    }.sorted().joinToString("\n")

    val writes: List<Request> get() = synchronized(requests) { requests.filter { it.isWrite } }
    val deletes: List<Request> get() = synchronized(requests) { requests.filter { it.method == "DELETE" } }

    /** M9-14c: every create and delete the stream manager of this broker was asked for (the stream protocol is not an HTTP request, so [requests] does not show them). */
    val streamCreateCalls: MutableList<Triple<String, String, StreamSettings>> = Collections.synchronizedList(ArrayList())
    val streamDeleteCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())

    /**
     * A stream manager that works on the same queues: a stream is a queue of type `stream`, as on the real broker. M9-14c, pinned to the measured rows
     * of `DeclareEquivalenceTable.streamAnswers` (RabbitMQ 4.4.0): the declared settings are stored as the stream's `arguments` under their `x-` names
     * (`x-max-age` text as given, the two byte keys numbers), `x-queue-leader-locator: balanced` is added when no locator is declared (S-14c-5) and
     * `x-queue-type: stream` always. A repeat create is `Success` and changes nothing when every declared setting equals the held one (a declared
     * subset counts for the locator only: the held `balanced` is the default; a held retention left out is a difference); any other repeat, and a name that is not a stream, is `Refused` with `PRECONDITION_FAILED`
     * and changes nothing (S-14c-3). The fake never models route T text equality (the product never uses it).
     */
    fun streamManager(): StreamManager = object : StreamManager {
        override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult {
            streamCreateCalls += Triple(vhost, name, settings)
            val declared = declaredArguments(settings)
            val held = queues[Key(vhost, name)]
            if (held == null) {
                addQueue(vhost, name, type = "stream", arguments = declared + ("x-queue-leader-locator" to (declared["x-queue-leader-locator"] ?: JsonPrimitive("balanced"))))
                return StreamManagementResult.Success
            }
            // The real stream client treats STREAM_ALREADY_EXISTS (code 5) as success only for an equal stream; a different one is code 17.
            val omitted = held.arguments.keys - declared.keys - setOf("x-queue-type", "x-queue-leader-locator") // a held setting the repeat leaves out (S-14c-3 (c))
            val sameStream = held.arguments["x-queue-type"] == JsonPrimitive("stream") && omitted.isEmpty() &&
                declared.all { (key, value) -> held.arguments[key] == value }
            return if (sameStream) StreamManagementResult.Success
            else StreamManagementResult.Refused("Broker refused to create stream '$name': PRECONDITION_FAILED")
        }

        private fun declaredArguments(settings: StreamSettings): Map<String, JsonElement> = buildMap {
            settings.maxAge?.let { put("x-max-age", JsonPrimitive(it)) }
            settings.maxLengthBytes?.let { put("x-max-length-bytes", JsonPrimitive(it)) }
            settings.segmentSizeBytes?.let { put("x-stream-max-segment-size-bytes", JsonPrimitive(it)) }
            settings.leaderLocator?.let { put("x-queue-leader-locator", JsonPrimitive(it)) }
        }

        override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult {
            streamDeleteCalls += vhost to name
            return if (queues.remove(Key(vhost, name)) != null) StreamManagementResult.Success else StreamManagementResult.NotFound
        }
    }

    // ---- the API ---------------------------------------------------------------------------------------------

    fun handle(method: String, path: String, body: String?): Reply {
        val request = Request(method, path, body)
        requests += request
        if (unreachable) throw java.io.IOException("connection refused")
        rules.firstOrNull { it.first(request) }?.let { return it.second!! }
        val raw = path.trim('/').split('/')
        val segments = raw.map { decode(it) }
        if (segments.firstOrNull() != "api" || segments.size < 3) forbid(request)
        return when (segments[1]) {
            "queues" -> if (segments.size == 4) queue(method, segments[2], segments[3], body) else forbid(request)
            "exchanges" -> if (segments.size == 4) exchange(method, segments[2], segments[3], body) else forbid(request)
            "policies" -> if (segments.size == 4) policy(method, segments[2], segments[3], body, operator = false) else forbid(request)
            "operator-policies" -> if (segments.size == 4) policy(method, segments[2], segments[3], body, operator = true) else forbid(request)
            "bindings" -> binding(method, segments, body, request)
            else -> forbid(request)
        }
    }

    private fun unquote(text: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length + 0 && i + 2 <= text.length - 1 + 0) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                c == '+' -> { out.write(' '.code); i++ }
                hex != null -> { out.write(hex); i += 3 }
                else -> { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun forbid(request: Request): Nothing = throw ForbiddenPath("the executor sent a request the management allow-list does not permit: $request")

    private fun decode(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)

        private val dotName = Reply(405, """{"error":"method not allowed"}""")

    private fun parse(body: String?): JsonObject = if (body.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject

    private fun precondition(what: String, name: String, vhost: String, arg: String, received: String, current: String) =
        ManagementStatusTable.reply("$what.put.other-settings", "'$arg' for $what '$name' in vhost '$vhost': received '$received' but current is '$current'")

    /** MEASURED (M9-00c S-H): the broker drops the dot segments, so `GET .../.` and `.../..` answer 200 with the vhost LISTING, never 404. */
    private fun vhostListing(vhost: String, items: List<JsonObject>): Reply =
        if (vhost !in vhosts) t("vhost.get.queue.missing-vhost") else Reply(200, JsonArray(items).toString())

    private fun queueType(arguments: JsonObject): String = (arguments["x-queue-type"] as? JsonPrimitive)?.contentOrNull ?: "classic"

    private fun t(id: String) = ManagementStatusTable.reply(id)

    private fun queue(method: String, vhost: String, name: String, body: String?): Reply {
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (name == "." || name == "..") return dotName
                if (vhost !in vhosts) return t("vhost.put.queue.missing-vhost")
                val json = parse(body)
                val wanted = Queue(json["durable"]?.jsonPrimitive?.boolean ?: true, json["auto_delete"]?.jsonPrimitive?.boolean ?: false, json["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
                val existing = queues[key]
                when {
                    existing == null -> { if (key !in silent) queues[key] = wanted; t("queue.put.created") }
                    existing.durable != wanted.durable -> precondition("queue", name, vhost, "durable", wanted.durable.toString(), existing.durable.toString())
                    existing.autoDelete != wanted.autoDelete -> precondition("queue", name, vhost, "auto_delete", wanted.autoDelete.toString(), existing.autoDelete.toString())
                    queueType(existing.arguments) != queueType(wanted.arguments) ->
                        precondition("queue", name, vhost, "x-queue-type", queueType(wanted.arguments), queueType(existing.arguments))
                    else -> {
                        // M9-05b: MEASURED (DeclareEquivalenceTable): only the arguments the queue type honours are compared; any other argument is ignored (204)
                        val honoured = DeclareEquivalenceTable.queueComparedArguments[queueType(existing.arguments)].orEmpty()
                        val differing = honoured.sorted().firstOrNull { existing.arguments[it] != wanted.arguments[it] }
                        if (differing != null) precondition("queue", name, vhost, differing, wanted.arguments[differing].toString(), existing.arguments[differing].toString())
                        else t("queue.put.repeated")
                    }
                }
            }
            "GET" -> if (name == "." || name == "..") vhostListing(vhost, queues.filterKeys { it.vhost == vhost }.map { (k, q) -> buildJsonObject { put("name", k.name); put("vhost", vhost); put("durable", q.durable); put("auto_delete", q.autoDelete); put("arguments", q.arguments) } })
            else queues[key]?.let {
                Reply(200, buildJsonObject {
                    put("name", name); put("vhost", vhost); put("type", (it.arguments["x-queue-type"] as? JsonPrimitive)?.contentOrNull ?: "classic")
                    put("durable", it.durable); put("auto_delete", it.autoDelete); put("arguments", it.arguments)
                }.toString())
            } ?: t("queue.get.absent")
            "DELETE" -> if (queues.remove(key) != null) { bindings.removeAll { it.vhost == vhost && !it.toExchange && it.destination == name }; t("queue.delete.present") } else t("queue.delete.absent")
            else -> Reply(405)
        }
    }

    private fun exchange(method: String, vhost: String, name: String, body: String?): Reply {
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (name == "." || name == "..") return dotName
                if (vhost !in vhosts) return t("vhost.put.exchange.missing-vhost")
                val json = parse(body)
                val wantedType = json["type"]?.jsonPrimitive?.content ?: "direct"
                // honest limit: the plugin exchange types are not modelled; anything but the four built-in types is "unknown" like on a broker without the plugin
                if (wantedType !in setOf("direct", "fanout", "topic", "headers")) return ManagementStatusTable.reply("exchange.put.unknown-type", "'$wantedType'")
                val wanted = Exchange(
                    json["type"]?.jsonPrimitive?.content ?: "direct", json["durable"]?.jsonPrimitive?.boolean ?: true,
                    json["auto_delete"]?.jsonPrimitive?.boolean ?: false, json["internal"]?.jsonPrimitive?.boolean ?: false,
                    json["arguments"]?.jsonObject ?: JsonObject(emptyMap()),
                )
                val existing = exchanges[key]
                when {
                    existing == null -> { exchanges[key] = wanted; t("exchange.put.created") }
                    existing.type != wanted.type -> precondition("exchange", name, vhost, "type", wanted.type, existing.type)
                    existing.durable != wanted.durable -> precondition("exchange", name, vhost, "durable", wanted.durable.toString(), existing.durable.toString())
                    existing.autoDelete != wanted.autoDelete -> precondition("exchange", name, vhost, "auto_delete", wanted.autoDelete.toString(), existing.autoDelete.toString())
                    existing.internal != wanted.internal -> precondition("exchange", name, vhost, "internal", wanted.internal.toString(), existing.internal.toString())
                    else -> {
                        // M9-05b: MEASURED: only `alternate-exchange` is compared, any other argument is ignored (204)
                        val differing = DeclareEquivalenceTable.exchangeComparedArguments.sorted().firstOrNull { existing.arguments[it] != wanted.arguments[it] }
                        if (differing != null) precondition("exchange", name, vhost, differing, wanted.arguments[differing].toString(), existing.arguments[differing].toString())
                        else t("exchange.put.repeated")
                    }
                }
            }
            "GET" -> if (name == "." || name == "..") vhostListing(vhost, exchanges.filterKeys { it.vhost == vhost }.map { (k, e) -> buildJsonObject { put("name", k.name); put("vhost", vhost); put("type", e.type); put("durable", e.durable); put("auto_delete", e.autoDelete); put("internal", e.internal); put("arguments", e.arguments) } })
            else exchanges[key]?.let {
                Reply(200, buildJsonObject {
                    put("name", name); put("vhost", vhost); put("type", it.type); put("durable", it.durable)
                    put("auto_delete", it.autoDelete); put("internal", it.internal); put("arguments", it.arguments)
                }.toString())
            } ?: t("exchange.get.absent")
            "DELETE" -> if (exchanges.remove(key) != null) { bindings.removeAll { it.vhost == vhost && it.source == name }; t("exchange.delete.present") } else t("exchange.delete.absent")
            else -> Reply(405)
        }
    }

    private fun policy(method: String, vhost: String, name: String, body: String?, operator: Boolean): Reply {
        val map = if (operator) operatorPolicies else policies
        val kind = if (operator) "operator-policy" else "policy"
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (vhost !in vhosts) return t("vhost.put.queue.missing-vhost")
                policyWrites++
                val json = parse(body)
                val created = key !in map
                map[key] = Policy(
                    json["pattern"]?.jsonPrimitive?.content ?: "", json["apply-to"]?.jsonPrimitive?.content ?: "all",
                    json["priority"]?.jsonPrimitive?.int ?: 0, json["definition"]?.jsonObject ?: JsonObject(emptyMap()),
                )
                t("$kind.put." + if (created) "created" else "replaced")
            }
            "GET" -> map[key]?.let {
                Reply(200, buildJsonObject {
                    put("vhost", vhost); put("name", name); put("pattern", it.pattern); put("apply-to", it.applyTo)
                    put("definition", it.definition); put("priority", it.priority)
                }.toString())
            } ?: t("$kind.get.absent")
            "DELETE" -> if (map.remove(key) != null) t("$kind.delete.present") else t("$kind.delete.absent")
            else -> Reply(405)
        }
    }

    // /api/bindings/{v}/e/{src}/{q|e}/{dst}[/{props}]
    private fun binding(method: String, s: List<String>, body: String?, request: Request): Reply {
        if (s.size !in 7..8 || s[3] != "e" || (s[5] != "q" && s[5] != "e")) forbid(request)
        val vhost = s[2]; val source = s[4]; val toExchange = s[5] == "e"; val destination = s[6]
        if (vhost !in vhosts) return t("vhost.get.queue.missing-vhost")
        val between = bindings.filter { it.vhost == vhost && it.source == source && it.toExchange == toExchange && it.destination == destination }
        return when {
            s.size == 7 && method == "GET" -> Reply(ManagementStatusTable.status(if (between.isEmpty()) "binding.get.absent-items" else "binding.get.list"), JsonArray(between.map {
                buildJsonObject {
                    put("source", it.source); put("vhost", vhost); put("destination", it.destination)
                    put("destination_type", if (it.toExchange) "exchange" else "queue"); put("routing_key", it.routingKey)
                    put("arguments", it.arguments); put("properties_key", it.propertiesKey)
                }
            }).toString())
            s.size == 7 && method == "POST" -> {
                val sourceExists = source.startsWith("amq.") || Key(vhost, source) in exchanges
                val destinationExists = if (toExchange) Key(vhost, destination) in exchanges else Key(vhost, destination) in queues
                if (!sourceExists) return ManagementStatusTable.reply("binding.post.missing-source", "'$source' in vhost '$vhost'")
                if (!destinationExists) return ManagementStatusTable.reply("binding.post.missing-destination", "'$destination' in vhost '$vhost'")
                val json = parse(body)
                val wanted = Binding(vhost, source, toExchange, destination, json["routing_key"]?.jsonPrimitive?.content ?: "", json["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
                if (bindings.any { it == wanted }) t("binding.post.repeated") else { bindings += wanted; t("binding.post.created") }
            }
            s.size == 8 && method == "DELETE" -> {
                // MEASURED on 4.x (round 1 probe): the path segment is percent-decoded once by the HTTP layer (s[7]); the management plugin then drops
                // every "~" (so "~" alone is the EMPTY routing key and "x~y" is "xy") and unquotes the rest ("+" is a space, %XX a byte), and
                // deletes the binding without arguments that has that routing key. So the segment that names key K is quote(K) with each "%" written
                // as "%25" (the properties_key as the broker lists it, escaped once more), never the properties_key verbatim.
                val key = unquote(s[7].replace("~", ""))
                val found = between.firstOrNull { it.routingKey == key && it.arguments.isEmpty() }
                if (found != null) { bindings.remove(found); if (alsoRemovesSibling) between.firstOrNull { it != found }?.let { bindings.remove(it) }; t("binding.delete.present") } else t("binding.delete.absent")
            }
            else -> Reply(405)
        }
    }
}
