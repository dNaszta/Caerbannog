package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.StreamManagementResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-00c spike, part 3 (S-H): names. A queue, an exchange, a stream and a policy named `amq.test`, with 255 and 256 bytes (ASCII and multi-byte),
 * with a space or a slash, and the names `.` and `..` (the real GET answer the check meets, and the declare). Requests are those of the real
 * executor builders; streams are created by the stream manager. Findings: docs/tasks/M9-00c-spike-results.md.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeNamesTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9SpikeNamesTest {

    companion object {
        private val container: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val cleanup = M9c.Cleanup(api)

    @AfterEach fun clean() = cleanup.run()

    private val names: Map<String, String> = linkedMapOf(
        "amq.test" to "amq.test",
        "amq-prefix-only" to "amq.",
        "amq-gen" to "amq.gen-x",
        "amq-without-dot" to "amq",
        "255-bytes" to "a".repeat(255),
        "256-bytes" to "a".repeat(256),
        "255-bytes-multibyte" to "é".repeat(127) + "a",
        "256-bytes-multibyte" to "é".repeat(128),
        "space" to "a b",
        "slash" to "a/b",
        "dot" to ".",
        "dotdot" to "..",
    )

    private fun reply(r: MgmtApi.Reply) = "${r.status}" + if (r.ok) "" else " " + r.reason().take(90).trim('"', ' ')

    private fun send(action: MigrationAction): MgmtApi.Reply {
        val write = checkNotNull(MigrationRequests.requestFor(action))
        return api.put(write.path, write.body.toString())
    }

    private fun enc(name: String) = MigrationRequests.encode(name)

    @Test
    fun `The 4_x image is a 4_x broker`() = M9c.assertFourX(api)

    @Test
    fun `S-H what the broker answers to a declare of each name`() {
        val found = linkedMapOf<String, String>()
        val profile = ConnectionProfile(
            name = "m9c", host = container.host, virtualHost = "/", username = container.adminUsername, tls = false,
            streamPort = container.getMappedPort(5552), managementPort = container.getMappedPort(15672),
        )
        StreamEnvironments(profile, container.adminPassword, BrokerTransport.Plain).use { envs ->
            val manager = RabbitMqStreamManager(envs)
            names.forEach { (label, name) ->
                // The stream first and deleted again at once (a stream and a queue share one namespace), through the stream protocol: the
                // management API cannot address a stream named `.` or `..`.
                found["$label.stream"] = when (val r = runBlocking { manager.createStream("/", name) }) {
                    StreamManagementResult.Success -> {
                        // what the check's read sees of the stream just created: its type (an object), or for . and .. the list
                        val read = api.get("/api/queues/%2F/${enc(name)}")
                        found["$label.stream-get"] = "${read.status} " + (if (read.json is JsonArray) "list" else read.obj["type"].toString().trim('"'))
                        val gone = runBlocking { manager.deleteStream("/", name) }
                        assertEquals(StreamManagementResult.Success, gone, "the stream $label must be deletable through the stream protocol")
                        "Success"
                    }
                    is StreamManagementResult.Refused -> "Refused " + r.reason.replace(name, "<name>").take(160)
                    is StreamManagementResult.Failed -> "Failed"
                    StreamManagementResult.NotFound -> "NotFound"
                }
                found["$label.queue"] = reply(send(MigrationAction.DeclareQueue(name, "quorum")).also { if (it.ok) cleanup.queue(enc(name)) })
                found["$label.exchange"] = reply(send(MigrationAction.DeclareExchange(name)).also { if (it.ok) cleanup.exchange(enc(name)) })
                found["$label.policy"] = reply(
                    send(MigrationAction.DeclarePolicy(name, "^m9c-never$", mapOf("max-length" to ArgumentValue.Whole(5)))).also { if (it.ok) cleanup.policy(enc(name)) },
                )
            }
        }
        val problems = found.mapNotNull { (id, answer) ->
            val expected = expectedDeclare[id]
            if (expected == null || !answer.startsWith(expected)) "$id: expected '${expected}', broker said '$answer'" else null
        }
        assertTrue(problems.isEmpty(), "the names table differs from the broker:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `S-H the GET the check uses for a name answers 404 for an absent name, and 200 with a LIST for the names dot and dot-dot`() {
        val problems = mutableListOf<String>()
        // A positive control first: a real queue and exchange are an object with their own name.
        val q = "m9c-names-control"
        assertEquals(201, send(MigrationAction.DeclareQueue(q, "quorum")).status); cleanup.queue(q)
        assertEquals(q, api.get("/api/queues/%2F/$q").obj["name"].toString().trim('"'))
        names.filterKeys { it !in setOf("dot", "dotdot") }.forEach { (label, name) ->
            val p = enc(name)
            val queue = api.get("/api/queues/%2F/$p").status
            val exchange = api.get("/api/exchanges/%2F/$p").status
            if (queue != 404 || exchange != 404) problems += "$label: absent name answered queue $queue, exchange $exchange (expected 404)"
        }
        // The names . and .. : the request is built the way the check builds it (ManagementRequests.queueDetail / exchange path, %2E per dot).
        listOf("dot" to ".", "dotdot" to "..").forEach { (label, name) ->
            val p = enc(name)
            val queue = api.get("/api/queues/%2F/$p")
            val exchange = api.get("/api/exchanges/%2F/$p")
            if (queue.status != 200 || queue.json !is JsonArray) problems += "$label queue: expected 200 with a JSON list, got ${queue.status} ${queue.body.take(60)}"
            if (exchange.status != 200 || exchange.json !is JsonArray) problems += "$label exchange: expected 200 with a JSON list, got ${exchange.status} ${exchange.body.take(60)}"
        }
        // The raw segments and the empty name answer the same list (the path is normalised before the broker routes it).
        listOf("." to ".", ".." to "..", "" to "(empty)").forEach { (segment, label) ->
            val queue = api.get("/api/queues/%2F/$segment")
            val exchange = api.get("/api/exchanges/%2F/$segment")
            if (queue.status != 200 || queue.json !is JsonArray) problems += "raw $label queue: ${queue.status} ${queue.body.take(60)}"
            if (exchange.status != 200 || exchange.json !is JsonArray) problems += "raw $label exchange: ${exchange.status} ${exchange.body.take(60)}"
        }
        assertTrue(problems.isEmpty(), "GET answers differ from the measured ones:\n${problems.joinToString("\n")}")
    }

    /** What each declare answers (the beginning of the answer), MEASURED on 4.4.0. */
    private val expectedDeclare: Map<String, String> = buildMap {
        val reserved = "401 Access refused"
        for (label in listOf("amq.test", "amq-prefix-only", "amq-gen")) {
            put("$label.queue", reserved); put("$label.exchange", reserved); put("$label.policy", "201")
            put("$label.stream", "Refused Broker refused to create stream '<name>': Error while creating stream '<name>' (17 (PRECONDITION_FAILED))")
        }
        for (label in listOf("amq-without-dot", "255-bytes", "255-bytes-multibyte", "space", "slash")) {
            put("$label.queue", "201"); put("$label.exchange", "201"); put("$label.policy", "201"); put("$label.stream", "Success"); put("$label.stream-get", "200 stream")
        }
        // a 256-byte name: the queue is refused (the broker says "less than 255" but accepts 255), exchange, policy and stream are not
        for (label in listOf("256-bytes", "256-bytes-multibyte")) {
            put("$label.queue", "400 queue should be less than 255 bytes, actually was 256")
            put("$label.exchange", "201"); put("$label.policy", "201"); put("$label.stream", "Success"); put("$label.stream-get", "200 stream")
        }
        // . and ..: nothing can be declared through the management API (405), but the stream protocol creates the stream and the management
        // GET of it answers a LIST (the path is normalised away)
        for (label in listOf("dot", "dotdot")) {
            put("$label.queue", "405"); put("$label.exchange", "405"); put("$label.policy", "405"); put("$label.stream", "Success"); put("$label.stream-get", "200 list")
        }
    }

    @Test
    fun `The fixture of S-H holds the real answer of the GET for the name dot-dot`() {
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(M9c.fixture("s-h-get-dotdot-answers-a-list.json")) as kotlinx.serialization.json.JsonObject
        assertEquals("200", fixture["status"].toString())
        assertEquals("true", fixture["bodyIsAList"].toString())
    }
}
