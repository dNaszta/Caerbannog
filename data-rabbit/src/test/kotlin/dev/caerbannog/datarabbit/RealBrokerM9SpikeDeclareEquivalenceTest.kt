package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.DeclareEquivalenceTable
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * M9-00c spike, part 2 (S-G1 to S-G4): what a real RabbitMQ 4.x answers to a repeated declare, with the request bodies of the REAL executor
 * builders (`MigrationRequests`), and whether a GET returns everything the broker compares. The table is `DeclareEquivalenceTable`
 * (core-testing); every row is sent and compared with the live status. Findings: docs/tasks/M9-00c-spike-results.md.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeDeclareEquivalenceTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9SpikeDeclareEquivalenceTest {

    companion object {
        private val container: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(6)
    private var counter = 0
    private fun unique(base: String) = "m9c-$base-$run-${counter++}"
    private val cleanup = M9c.Cleanup(api)

    @AfterEach fun clean() = cleanup.run()

    private fun text(value: String) = ArgumentValue.Text(value)
    private fun whole(value: Long) = ArgumentValue.Whole(value)

    /** The request the real executor builds for [action], sent as it would be. */
    private fun send(action: MigrationAction): MgmtApi.Reply {
        val write = checkNotNull(MigrationRequests.requestFor(action)) { "no request for $action" }
        check(write.method == "PUT")
        return api.put(write.path, write.body.toString())
    }

    private fun queue(name: String, type: String, args: Map<String, ArgumentValue> = emptyMap(), durable: Boolean = true, autoDelete: Boolean = false) =
        MigrationAction.DeclareQueue(name, type, "/", durable, args, autoDelete)

    private fun exchange(
        name: String, type: String = "direct", args: Map<String, ArgumentValue> = emptyMap(),
        durable: Boolean = true, autoDelete: Boolean = false, internal: Boolean = false,
    ) = MigrationAction.DeclareExchange(name, type, "/", durable, autoDelete, internal, args)

    /** The ids of the table rows this test sent (a row nobody sends must fail the test, see [assertVisitedAll]). */
    private val visited = mutableSetOf<String>()

    private fun assertVisitedAll(prefix: String) {
        val all = DeclareEquivalenceTable.rows.map { it.id }.filter { it.startsWith(prefix) }.toSet()
        assertEquals(all, visited.filter { it.startsWith(prefix) }.toSet(), "every $prefix* row of the table must be sent by the test (missing: ${all - visited}, unknown: ${visited.filter { it.startsWith(prefix) }.toSet() - all})")
    }

    private fun check(id: String, reply: MgmtApi.Reply, problems: MutableList<String>) {
        visited += id
        val row = DeclareEquivalenceTable.row(id)
        val expectedText = row.bodyHas
        val bodyOk = expectedText == null || expectedText in reply.body
        if (reply.status != row.status || !bodyOk) problems += "$id: table says ${row.status} ${row.bodyHas ?: ""}, broker said $reply"
    }

    @Test
    fun `The floating tag 4-management is a 4_x broker`() = M9c.assertFourX(api)

    @Test
    fun `S-G1 a repeated queue declare, per type, answers like the table`() {
        val problems = mutableListOf<String>()
        listOf("quorum", "classic", "stream").forEach { type ->
            val limit = if (type == "stream") "x-max-length-bytes" else "x-max-length"
            val value = if (type == "stream") 1000L else 10L
            val base = mapOf(limit to whole(value))
            val moreKey = if (type == "stream") "x-max-age" to text("7D") else "x-message-ttl" to whole(5000)
            val refusedAtFirst = when (type) { "quorum" -> "x-max-priority" to whole(5); "classic" -> "x-delivery-limit" to whole(5); else -> "x-message-ttl" to whole(5000) }
            fun repeat(id: String, first: Map<String, ArgumentValue> = base, second: Map<String, ArgumentValue>, secondType: String = type,
                       durable: Boolean = true, autoDelete: Boolean = false) {
                val name = cleanup.queue(unique("g1"))
                val created = send(queue(name, type, first))
                assertEquals(201, created.status, "$id: the first declare must create: $created")
                check("queue.$type.$id", send(queue(name, secondType, second, durable, autoDelete)), problems)
            }
            repeat("same", second = base)
            repeat("fewer-arguments", second = emptyMap())
            repeat("more-arguments", second = base + moreKey)
            repeat("other-value", second = mapOf(limit to whole(value + 1)))
            repeat("number-as-text", second = mapOf(limit to text(value.toString())))
            repeat("number-as-decimal", second = mapOf(limit to ArgumentValue.Decimal(value.toDouble())))
            repeat("other-type", second = base, secondType = if (type == "quorum") "classic" else "quorum")
            repeat("durable-false", second = base, durable = false)
            repeat("auto-delete-true", second = base, autoDelete = true)
            repeat("unknown-argument-other-value", first = mapOf("x-foo" to text("a")), second = mapOf("x-foo" to text("b")))
            repeat("unknown-argument-added", first = emptyMap(), second = mapOf("x-foo" to text("a")))
            repeat("unknown-argument-number-as-text", first = mapOf("x-foo" to whole(1)), second = mapOf("x-foo" to text("1")))
            repeat("argument-of-another-type-added", first = emptyMap(), second = mapOf(refusedAtFirst))
            // A declare WITHOUT x-queue-type is not something the real builder sends (it always adds it); hand-written, to complete the table.
            val name = cleanup.queue(unique("g1o"))
            assertEquals(201, send(queue(name, type, base)).status)
            check("queue.$type.type-argument-omitted", api.put("/api/queues/%2F/$name", """{"durable":true,"auto_delete":false,"arguments":{"$limit":$value}}"""), problems)
        }
        assertVisitedAll("queue.")
        assertTrue(problems.isEmpty(), "the repeated-declare table differs from the broker:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `S-G1 the broker compares exactly the arguments the queue type honours, any other argument is ignored`() {
        api.put("/api/exchanges/%2F/${cleanup.exchange("dlx")}", """{"type":"direct","durable":true}""")
        val candidates = listOf(
            "x-message-ttl" to whole(5000), "x-expires" to whole(600000), "x-max-length" to whole(10), "x-max-length-bytes" to whole(1000),
            "x-overflow" to text("reject-publish"), "x-dead-letter-exchange" to text("dlx"), "x-dead-letter-strategy" to text("at-most-once"),
            "x-delivery-limit" to whole(5), "x-queue-leader-locator" to text("balanced"), "x-quorum-initial-group-size" to whole(3),
            "x-initial-cluster-size" to whole(3), "x-single-active-consumer" to ArgumentValue.Flag(true), "x-max-priority" to whole(5),
            "x-queue-mode" to text("lazy"), "x-queue-version" to whole(2), "x-max-age" to text("7D"), "x-stream-max-segment-size-bytes" to whole(500000000),
            "x-stream-filter-size-bytes" to whole(32), "x-consumer-disconnected-timeout" to whole(1000), "x-foo" to text("a"),
        )
        val problems = mutableListOf<String>()
        DeclareEquivalenceTable.queueComparedArguments.forEach { (type, set) ->
            val untried = set - candidates.map { it.first }.toSet() - "x-dead-letter-routing-key"
            assertTrue(untried.isEmpty(), "$type: compared arguments no candidate sends: $untried")
        }
        listOf("quorum", "classic", "stream").forEach { type ->
            val compared = DeclareEquivalenceTable.queueComparedArguments.getValue(type)
            candidates.forEach { (key, value) ->
                val name = cleanup.queue(unique("g1c"))
                assertEquals(201, send(queue(name, type)).status)
                val status = send(queue(name, type, mapOf(key to value))).status
                val expected = if (key in compared) 400 else 204
                if (status != expected) problems += "$type $key added: expected $expected, broker $status"
            }
            // the routing key of a dead letter only exists next to its exchange, so it is tried as a change of value
            if (type != "stream") {
                val name = cleanup.queue(unique("g1k"))
                val args = mapOf("x-dead-letter-exchange" to text("dlx"), "x-dead-letter-routing-key" to text("a"))
                assertEquals(201, send(queue(name, type, args)).status)
                assertEquals(400, send(queue(name, type, args + ("x-dead-letter-routing-key" to text("b")))).status, "$type: x-dead-letter-routing-key is compared")
                assertTrue("x-dead-letter-routing-key" in compared)
            }
        }
        assertTrue(problems.isEmpty(), "the compared-arguments sets differ from the broker:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `S-G2 a repeated exchange declare answers like the table`() {
        val ae = cleanup.exchange(unique("ae"))
        assertEquals(201, send(exchange(ae, "fanout")).status)
        val problems = mutableListOf<String>()
        fun repeat(id: String, first: MigrationAction.DeclareExchange, second: (String) -> MigrationAction.DeclareExchange) {
            val name = cleanup.exchange(unique("g2"))
            assertEquals(201, send(first.copy(name = name)).status, "$id: the first declare must create")
            check("exchange.$id", send(second(name)), problems)
        }
        val withAe = mapOf("alternate-exchange" to text(ae))
        val base = exchange("x", args = withAe)
        repeat("same", base) { base.copy(name = it) }
        repeat("other-type", base) { base.copy(name = it, type = "topic") }
        repeat("durable-false", base) { base.copy(name = it, durable = false) }
        repeat("auto-delete-true", base) { base.copy(name = it, autoDelete = true) }
        repeat("internal-true", base) { base.copy(name = it, internal = true) }
        repeat("alternate-exchange-fewer", base) { base.copy(name = it, arguments = emptyMap()) }
        repeat("alternate-exchange-other-value", base) { base.copy(name = it, arguments = mapOf("alternate-exchange" to text("other"))) }
        repeat("alternate-exchange-as-number", base) { base.copy(name = it, arguments = mapOf("alternate-exchange" to whole(5))) }
        repeat("alternate-exchange-added", exchange("x")) { base.copy(name = it) }
        repeat("unknown-argument-added", base) { base.copy(name = it, arguments = withAe + ("x-foo" to text("b"))) }
        repeat("unknown-argument-number-as-text", exchange("x", args = mapOf("x-foo" to whole(10)))) { exchange(it, args = mapOf("x-foo" to text("10"))) }
        repeat("unknown-argument-number-as-decimal", exchange("x", args = mapOf("x-foo" to whole(10)))) { exchange(it, args = mapOf("x-foo" to ArgumentValue.Decimal(10.0))) }
        assertVisitedAll("exchange.")
        assertTrue(problems.isEmpty(), "the exchange rows differ from the broker:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `S-G2 an ignored argument that a repeated declare carries is not stored, the first arguments stay`() {
        val name = cleanup.exchange(unique("g2s"))
        assertEquals(201, send(exchange(name, args = mapOf("x-foo" to text("first")))).status)
        assertEquals(204, send(exchange(name, args = mapOf("x-foo" to text("second"), "x-bar" to whole(1)))).status)
        assertEquals(mapOf("x-foo" to JsonPrimitive("first")), api.get("/api/exchanges/%2F/$name").obj.args(), "a 204 changes nothing")
        val q = cleanup.queue(unique("g2q"))
        assertEquals(201, send(queue(q, "quorum", mapOf("x-foo" to text("first")))).status)
        assertEquals(204, send(queue(q, "quorum", mapOf("x-foo" to text("second")))).status)
        assertEquals("first", M9c.queueDetail(api, q).args()["x-foo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S-G4 a GET returns every field the broker compares, with the wire type of numbers`() {
        val problems = mutableListOf<String>()
        listOf("quorum", "classic", "stream").forEach { type ->
            val name = cleanup.queue(unique("g4"))
            val limit = if (type == "stream") "x-max-length-bytes" else "x-max-length"
            assertEquals(201, send(queue(name, type, mapOf(limit to whole(10), "x-foo" to ArgumentValue.Decimal(10.0), "x-bar" to text("10")))).status)
            val body = M9c.queueDetail(api, name)
            if (body["durable"]?.jsonPrimitive?.content != "true") problems += "$type: durable missing in the GET"
            if (body["auto_delete"]?.jsonPrimitive?.content != "false") problems += "$type: auto_delete missing in the GET"
            if (body["type"]?.jsonPrimitive?.content != type) problems += "$type: type is ${body["type"]}"
            val args = body.args()
            if (args["x-queue-type"]?.jsonPrimitive?.content != type) problems += "$type: arguments.x-queue-type is ${args["x-queue-type"]}"
            val number = args[limit]!!.jsonPrimitive
            if (number.isString || number.content != "10") problems += "$type: $limit should be the JSON number 10, was $number"
            val decimal = args["x-foo"]!!.jsonPrimitive
            if (decimal.isString || decimal.content != "10.0") problems += "$type: a decimal must read back as 10.0, was $decimal"
            val asText = args["x-bar"]!!.jsonPrimitive
            if (!asText.isString || asText.content != "10") problems += "$type: a text must read back as a JSON string, was $asText"
        }
        val x = cleanup.exchange(unique("g4x"))
        assertEquals(201, send(exchange(x, "topic", mapOf("alternate-exchange" to text("ae")), durable = true, autoDelete = true, internal = true)).status)
        val e = api.get("/api/exchanges/%2F/$x").obj
        assertEquals("topic", e["type"]!!.jsonPrimitive.content)
        assertEquals("true", e["durable"]!!.jsonPrimitive.content)
        assertEquals("true", e["auto_delete"]!!.jsonPrimitive.content)
        assertEquals("true", e["internal"]!!.jsonPrimitive.content)
        assertEquals("ae", e.args()["alternate-exchange"]!!.jsonPrimitive.content)
        assertTrue(problems.isEmpty(), "a GET does not return what the broker compares:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `S-G4 a limit that a policy sets is not in the arguments, so a read of the arguments is what the declare compares`() {
        val name = cleanup.queue(unique("g4p"))
        assertEquals(201, send(queue(name, "quorum", mapOf("x-foo" to text("a")))).status)
        val policy = unique("g4pol")
        assertEquals(201, api.put("/api/policies/%2F/${cleanup.policy(policy)}", """{"pattern":"^$name$","apply-to":"queues","priority":0,"definition":{"max-length":7}}""").status)
        awaitUntil("the policy is applied", 90) { M9c.queueDetail(api, name)["policy"]?.jsonPrimitive?.content == policy }
        val detail = M9c.queueDetail(api, name)
        assertFalse(detail.args().containsKey("x-max-length"), "arguments stay the declared ones: ${detail.args()}")
        assertEquals(204, send(queue(name, "quorum", mapOf("x-foo" to text("a")))).status, "a repeated declare under a policy is equivalent when its own arguments are")
        assertEquals(400, send(queue(name, "quorum", mapOf("x-foo" to text("a"), "x-max-length" to whole(7)))).status, "an argument equal to the policy's value is still an added argument")
    }

    // ---- S-G3: streams ---------------------------------------------------------------------------------------------------

    private fun streamManagerOn(block: (RabbitMqStreamManager) -> Unit) {
        val profile = ConnectionProfile(
            name = "m9c", host = container.host, virtualHost = "/", username = container.adminUsername, tls = false,
            streamPort = container.getMappedPort(5552), managementPort = container.getMappedPort(15672),
        )
        StreamEnvironments(profile, container.adminPassword, BrokerTransport.Plain).use { block(RabbitMqStreamManager(it)) }
    }

    @Test
    fun `S-G3 the stream manager and the queue PUT on names that are taken`() {
        val found = mutableListOf<DeclareEquivalenceTable.StreamAnswer>()
        streamManagerOn { manager ->
            val stream = cleanup.queue(unique("g3s"))
            val created = runBlocking { manager.createStream("/", stream) }
            found += DeclareEquivalenceTable.StreamAnswer("stream.create.new", answerOf(created))
            found += DeclareEquivalenceTable.StreamAnswer("stream.arguments.after-create", "Arguments " + M9c.queueDetail(api, stream).args().toString())
            found += DeclareEquivalenceTable.StreamAnswer("stream.create.again", answerOf(runBlocking { manager.createStream("/", stream) }))
            // M9-14c: the rows with settings, through the REAL manager (the raw route, protocol names): the arguments never change on a repeat.
            val withSettings = cleanup.queue(unique("g3w"))
            val sevenDays = StreamSettings(maxAge = "7D", leaderLocator = "balanced")
            assertEquals(StreamManagementResult.Success, runBlocking { manager.createStream("/", withSettings, sevenDays) })
            val argsAfterCreate = M9c.queueDetail(api, withSettings).args()
            found += DeclareEquivalenceTable.StreamAnswer("stream.arguments.after-create-with-settings", "Arguments " + argsAfterCreate.toString())
            val repeats = listOf(
                "stream.create.again-same" to sevenDays,
                "stream.create.again-subset-locator" to StreamSettings(maxAge = "7D"),
                "stream.create.again-other-retention" to StreamSettings(maxAge = "1D", leaderLocator = "balanced"),
                "stream.create.again-other-locator" to StreamSettings(maxAge = "7D", leaderLocator = "client-local"),
                "stream.create.again-no-settings" to StreamSettings.NONE,
                "stream.create.again-superset" to StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, leaderLocator = "balanced"),
            )
            for ((id, settings) in repeats) {
                found += DeclareEquivalenceTable.StreamAnswer(id, answerOf(runBlocking { manager.createStream("/", withSettings, settings) }))
                assertEquals(argsAfterCreate, M9c.queueDetail(api, withSettings).args(), "$id altered the stream (S-14c-3: a repeat never does)")
            }
            val quorum = cleanup.queue(unique("g3q"))
            assertEquals(201, send(queue(quorum, "quorum")).status)
            found += DeclareEquivalenceTable.StreamAnswer("stream.create.on-quorum-queue", answerOf(runBlocking { manager.createStream("/", quorum) }))
            val classic = cleanup.queue(unique("g3c"))
            assertEquals(201, send(queue(classic, "classic")).status)
            found += DeclareEquivalenceTable.StreamAnswer("stream.create.on-classic-queue", answerOf(runBlocking { manager.createStream("/", classic) }))
            val taken = cleanup.exchange(unique("g3x"))
            assertEquals(201, send(exchange(taken)).status)
            found += DeclareEquivalenceTable.StreamAnswer("stream.create.on-exchange-name", answerOf(runBlocking { manager.createStream("/", taken) }))
            found += DeclareEquivalenceTable.StreamAnswer("queue.put.quorum-on-stream", statusOf(send(queue(stream, "quorum"))))
            found += DeclareEquivalenceTable.StreamAnswer("queue.put.classic-on-stream", statusOf(send(queue(stream, "classic"))))
            found += DeclareEquivalenceTable.StreamAnswer("queue.put.stream-on-stream", statusOf(send(queue(stream, "stream"))))
            found += DeclareEquivalenceTable.StreamAnswer("exchange.put.on-stream-name", statusOf(send(exchange(stream))))
            found += DeclareEquivalenceTable.StreamAnswer("queue.put.stream-on-quorum-queue", statusOf(send(queue(quorum, "stream"))))
        }
        assertEquals(DeclareEquivalenceTable.streamAnswers.map { it.id }.toSet(), found.map { it.id }.toSet(), "every streamAnswers row must be sent by the test")
        val expected = DeclareEquivalenceTable.streamAnswers.associateBy { it.id }
        val problems = found.mapNotNull { f ->
            val e = expected[f.id]
            val result = f.result.substringBefore(' ')
            val detail = f.result.substringAfter(' ', "")
            when {
                e == null -> "${f.id}: no row in the table, broker answered ${f.result}"
                e.result != result -> "${f.id}: table says ${e.result}, broker answered ${f.result}"
                e.detail?.let { it !in detail } == true -> "${f.id}: table expects '${e.detail}' in the answer, broker said: $detail"
                else -> null
            }
        }
        assertTrue(problems.isEmpty(), "the stream answers differ from the table:\n${problems.joinToString("\n")}")
    }

    private fun statusOf(reply: MgmtApi.Reply) = reply.status.toString() + if (reply.ok) "" else " " + reply.reason().take(150)

    private fun answerOf(result: StreamManagementResult): String = when (result) {
        StreamManagementResult.Success -> "Success"
        StreamManagementResult.NotFound -> "NotFound"
        is StreamManagementResult.Refused -> "Refused " + result.reason.take(200)
        is StreamManagementResult.Failed -> "Failed"
    }

    @Test
    fun `The fixtures of S-G4 hold what a GET returns`() {
        val q = kotlinx.serialization.json.Json.parseToJsonElement(M9c.fixture("s-g4-queue-get.json")).jsonObject
        assertEquals("quorum", q["type"]!!.jsonPrimitive.content)
        assertEquals("10.0", q.args()["x-foo"]!!.jsonPrimitive.content, "a decimal reads back as 10.0")
        assertTrue(q.args()["x-bar"]!!.jsonPrimitive.isString, "a text reads back as a string")
        val e = kotlinx.serialization.json.Json.parseToJsonElement(M9c.fixture("s-g4-exchange-get.json")).jsonObject
        assertEquals("true", e["internal"]!!.jsonPrimitive.content)
        assertEquals("ae", e.args()["alternate-exchange"]!!.jsonPrimitive.content)
    }
}
