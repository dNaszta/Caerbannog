package dev.caerbannog.datarabbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.testcontainers.containers.RabbitMQContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * M8-00 spike helper: plain management API calls against a Testcontainers broker (3.13 or 4.x), with any user.
 * Spike tests are living proof of the findings written in docs/tasks/M8-00-spike-results.md; they use floating image tags
 * (`rabbitmq:3.13-management`, `rabbitmq:4-management`), so a finding that a newer 4.x changes fails here on purpose.
 */
internal class MgmtApi(private val container: RabbitMQContainer) {

    data class Reply(val status: Int, val body: String) {
        val json: JsonElement? get() = if (body.isBlank()) null else runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val obj: JsonObject get() = json as JsonObject
        val arr: JsonArray get() = json as JsonArray
        val ok: Boolean get() = status in 200..299
        /** The error text of a refused call, without line breaks, for stable assertions. */
        fun reason(): String = (json as? JsonObject)?.get("reason")?.toString().orEmpty().replace("\\n", " ")
        override fun toString() = "$status $body"
    }

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun call(method: String, path: String, body: String? = null, user: String = "guest", password: String = "guest"): Reply {
        val auth = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI("http://${container.host}:${container.getMappedPort(15672)}$path"))
            .header("Authorization", "Basic $auth").header("Content-Type", "application/json")
            .method(method, publisher).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.body())
    }

    fun get(path: String, user: String = "guest", password: String = "guest") = call("GET", path, null, user, password)
    fun put(path: String, body: String, user: String = "guest", password: String = "guest") = call("PUT", path, body, user, password)
    fun post(path: String, body: String, user: String = "guest", password: String = "guest") = call("POST", path, body, user, password)
    fun delete(path: String) = call("DELETE", path)

    fun declareQueue(name: String, arguments: String = "{}", durable: Boolean = true, autoDelete: Boolean = false, vhost: String = "%2F") =
        put("/api/queues/$vhost/$name", """{"durable":$durable,"auto_delete":$autoDelete,"arguments":$arguments}""")

    fun publish(queue: String, payload: String, properties: String = """{"delivery_mode":2}""", vhost: String = "%2F") =
        post("/api/exchanges/$vhost/amq.default/publish", """{"properties":$properties,"routing_key":"$queue","payload":"$payload","payload_encoding":"string"}""")

    /** Peeks at the queue through the management API and returns the payloads (requeues, never acks). */
    fun peek(queue: String, count: Int = 500, vhost: String = "%2F"): List<String> =
        post("/api/queues/$vhost/$queue/get", """{"count":$count,"ackmode":"ack_requeue_true","encoding":"auto"}""").arr
            .map { (it as JsonObject)["payload"].toString().trim('"') }

    /** Takes the messages off the queue (acks them); this spike's own cleanup and counting, not product behaviour. */
    fun drain(queue: String, count: Int = 500, vhost: String = "%2F"): List<String> =
        post("/api/queues/$vhost/$queue/get", """{"count":$count,"ackmode":"ack_requeue_false","encoding":"auto"}""").arr
            .map { (it as JsonObject)["payload"].toString().trim('"') }
}

internal fun awaitUntil(what: String, timeoutSeconds: Long = 60, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
        TimeUnit.MILLISECONDS.sleep(300)
    }
}

internal fun broker(image: String): RabbitMQContainer = RabbitMQContainer(image)
