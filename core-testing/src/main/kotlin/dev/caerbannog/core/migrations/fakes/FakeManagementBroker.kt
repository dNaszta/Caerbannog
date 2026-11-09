package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
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
 * M5-08a: an honest in-memory model of the management API that a migration executor talks to (queues, exchanges, bindings,
 * policies, operator policies of 4.x), WITHOUT any HTTP library: the data-rabbit tests and the acceptance glue wrap [handle] in a
 * Ktor mock engine. Rules, each one a fact of the real broker (the live test `RealBrokerM5MigrationTest` measures them again):
 *  - PUT of a queue or exchange: new gives 201; the same settings give 204; other durable, auto_delete, type or arguments give 400 with a
 *    `PRECONDITION_FAILED - inequivalent arg ...` reason that names the resource and the values (a test uses it as a sentinel: the app must
 *    never copy it); a name that decodes to `.` or `..` gives 405 and creates nothing (measured on 4.x, M8-04); an unknown vhost gives 404.
 *  - argument values are compared as typed JSON: `5` and `"5"` differ, `5` and `5.0` differ (the broker compares typed AMQP values).
 *  - PUT of a policy REPLACES an existing one (201 new, 204 replaced): so a test that counts [policyWrites] sees an overwrite the app should not make.
 *  - DELETE of something absent gives 404; GET of something absent gives 404 `{"error":"Object Not Found","reason":"Not Found"}`.
 *  - POST of a binding gives 201 new and 204 for the same source, destination, routing key and arguments; the source must exist (the `amq.`
 *    exchanges always do) and so must the destination, else 404. The properties key of a binding without arguments is its routing key, `~`
 *    for the empty one (RabbitMQ documentation; the live test measures it).
 *  - a path the migration executor must never send (`/get`, `/contents`, `/purge`, more segments than the API has for a resource, a path
 *    outside the five collections) is NOT answered with a status: [handle] throws, so the test fails loudly and the request is in [requests].
 * Honest limits: no statistics, no pagination, no permission model (use [answer] to script a 401 or 403), one node.
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
        val propertiesKey: String get() = if (arguments.isEmpty()) routingKey.ifEmpty { "~" } else routingKey.ifEmpty { "~" } + "_" + arguments.hashCode()
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

    /** A stream manager that works on the same queues: a stream is a queue of type `stream`, as on the real broker. */
    fun streamManager(): StreamManager = object : StreamManager {
        override suspend fun createStream(vhost: String, name: String): StreamManagementResult {
            // The real stream client treats STREAM_ALREADY_EXISTS (code 5) as success: creating again is Success.
            if (Key(vhost, name) !in queues) addQueue(vhost, name, type = "stream")
            return StreamManagementResult.Success
        }

        override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult =
            if (queues.remove(Key(vhost, name)) != null) StreamManagementResult.Success
            else StreamManagementResult.Refused("Broker refused to delete stream '$name': Error while deleting stream $name (STREAM_DOES_NOT_EXIST)")
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

    private fun forbid(request: Request): Nothing = throw ForbiddenPath("the executor sent a request the management allow-list does not permit: $request")

    private fun decode(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)

    private fun notFound() = Reply(404, """{"error":"Object Not Found","reason":"Not Found"}""")
    private val dotName = Reply(405, """{"error":"method not allowed"}""")

    private fun parse(body: String?): JsonObject = if (body.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject

    private fun precondition(what: String, name: String, vhost: String, arg: String, received: String, current: String) =
        Reply(400, """{"error":"bad_request","reason":"PRECONDITION_FAILED - inequivalent arg '$arg' for $what '$name' in vhost '$vhost': received '$received' but current is '$current'"}""")

    private fun queue(method: String, vhost: String, name: String, body: String?): Reply {
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (name == "." || name == "..") return dotName
                if (vhost !in vhosts) return notFound()
                val json = parse(body)
                val wanted = Queue(json["durable"]?.jsonPrimitive?.boolean ?: true, json["auto_delete"]?.jsonPrimitive?.boolean ?: false, json["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
                val existing = queues[key]
                when {
                    existing == null -> { if (key !in silent) queues[key] = wanted; Reply(201) }
                    existing.durable != wanted.durable -> precondition("queue", name, vhost, "durable", wanted.durable.toString(), existing.durable.toString())
                    existing.autoDelete != wanted.autoDelete -> precondition("queue", name, vhost, "auto_delete", wanted.autoDelete.toString(), existing.autoDelete.toString())
                    existing.arguments != wanted.arguments -> {
                        val differing = (existing.arguments.keys + wanted.arguments.keys).first { existing.arguments[it] != wanted.arguments[it] }
                        precondition("queue", name, vhost, differing, wanted.arguments[differing].toString(), existing.arguments[differing].toString())
                    }
                    else -> Reply(204)
                }
            }
            "GET" -> queues[key]?.let {
                Reply(200, buildJsonObject {
                    put("name", name); put("vhost", vhost); put("type", (it.arguments["x-queue-type"] as? JsonPrimitive)?.contentOrNull ?: "classic")
                    put("durable", it.durable); put("auto_delete", it.autoDelete); put("arguments", it.arguments)
                }.toString())
            } ?: notFound()
            "DELETE" -> if (queues.remove(key) != null) { bindings.removeAll { it.vhost == vhost && !it.toExchange && it.destination == name }; Reply(204) } else notFound()
            else -> Reply(405)
        }
    }

    private fun exchange(method: String, vhost: String, name: String, body: String?): Reply {
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (name == "." || name == "..") return dotName
                if (vhost !in vhosts) return notFound()
                val json = parse(body)
                val wanted = Exchange(
                    json["type"]?.jsonPrimitive?.content ?: "direct", json["durable"]?.jsonPrimitive?.boolean ?: true,
                    json["auto_delete"]?.jsonPrimitive?.boolean ?: false, json["internal"]?.jsonPrimitive?.boolean ?: false,
                    json["arguments"]?.jsonObject ?: JsonObject(emptyMap()),
                )
                val existing = exchanges[key]
                when {
                    existing == null -> { exchanges[key] = wanted; Reply(201) }
                    existing.type != wanted.type -> precondition("exchange", name, vhost, "type", wanted.type, existing.type)
                    existing.durable != wanted.durable -> precondition("exchange", name, vhost, "durable", wanted.durable.toString(), existing.durable.toString())
                    existing.autoDelete != wanted.autoDelete -> precondition("exchange", name, vhost, "auto_delete", wanted.autoDelete.toString(), existing.autoDelete.toString())
                    existing.internal != wanted.internal -> precondition("exchange", name, vhost, "internal", wanted.internal.toString(), existing.internal.toString())
                    existing.arguments != wanted.arguments -> precondition("exchange", name, vhost, "arguments", wanted.arguments.toString(), existing.arguments.toString())
                    else -> Reply(204)
                }
            }
            "GET" -> exchanges[key]?.let {
                Reply(200, buildJsonObject {
                    put("name", name); put("vhost", vhost); put("type", it.type); put("durable", it.durable)
                    put("auto_delete", it.autoDelete); put("internal", it.internal); put("arguments", it.arguments)
                }.toString())
            } ?: notFound()
            "DELETE" -> if (exchanges.remove(key) != null) { bindings.removeAll { it.vhost == vhost && it.source == name }; Reply(204) } else notFound()
            else -> Reply(405)
        }
    }

    private fun policy(method: String, vhost: String, name: String, body: String?, operator: Boolean): Reply {
        val map = if (operator) operatorPolicies else policies
        val key = Key(vhost, name)
        return when (method) {
            "PUT" -> {
                if (vhost !in vhosts) return notFound()
                policyWrites++
                val json = parse(body)
                val created = key !in map
                map[key] = Policy(
                    json["pattern"]?.jsonPrimitive?.content ?: "", json["apply-to"]?.jsonPrimitive?.content ?: "all",
                    json["priority"]?.jsonPrimitive?.int ?: 0, json["definition"]?.jsonObject ?: JsonObject(emptyMap()),
                )
                Reply(if (created) 201 else 204)
            }
            "GET" -> map[key]?.let {
                Reply(200, buildJsonObject {
                    put("vhost", vhost); put("name", name); put("pattern", it.pattern); put("apply-to", it.applyTo)
                    put("definition", it.definition); put("priority", it.priority)
                }.toString())
            } ?: notFound()
            "DELETE" -> if (map.remove(key) != null) Reply(204) else notFound()
            else -> Reply(405)
        }
    }

    // /api/bindings/{v}/e/{src}/{q|e}/{dst}[/{props}]
    private fun binding(method: String, s: List<String>, body: String?, request: Request): Reply {
        if (s.size !in 7..8 || s[3] != "e" || (s[5] != "q" && s[5] != "e")) forbid(request)
        val vhost = s[2]; val source = s[4]; val toExchange = s[5] == "e"; val destination = s[6]
        if (vhost !in vhosts) return notFound()
        val between = bindings.filter { it.vhost == vhost && it.source == source && it.toExchange == toExchange && it.destination == destination }
        return when {
            s.size == 7 && method == "GET" -> Reply(200, JsonArray(between.map {
                buildJsonObject {
                    put("source", it.source); put("vhost", vhost); put("destination", it.destination)
                    put("destination_type", if (it.toExchange) "exchange" else "queue"); put("routing_key", it.routingKey)
                    put("arguments", it.arguments); put("properties_key", it.propertiesKey)
                }
            }).toString())
            s.size == 7 && method == "POST" -> {
                val sourceExists = source.startsWith("amq.") || Key(vhost, source) in exchanges
                val destinationExists = if (toExchange) Key(vhost, destination) in exchanges else Key(vhost, destination) in queues
                if (!sourceExists || !destinationExists) return notFound()
                val json = parse(body)
                val wanted = Binding(vhost, source, toExchange, destination, json["routing_key"]?.jsonPrimitive?.content ?: "", json["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
                if (bindings.any { it == wanted }) Reply(204) else { bindings += wanted; Reply(201) }
            }
            s.size == 8 && method == "DELETE" -> {
                val found = between.firstOrNull { it.propertiesKey == s[7] }
                if (found != null) { bindings.remove(found); Reply(204) } else notFound()
            }
            else -> Reply(405)
        }
    }
}
