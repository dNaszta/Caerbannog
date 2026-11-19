package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.topology.QueueRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.util.UUID

/**
 * M9-08 (slice 1 and slice 2), the four claims the brief (docs/tasks/M9-08-policy-step.md, "Live test") could not take from M9-00c or M9-14c:
 *  1. a stream whose retention comes from a policy: does a row of `GET /api/queues/{vhost}` carry `effective_policy_definition` (with the column
 *     list the app sends today, and with the field added to it), and does the detail GET? Captures the fixture
 *     `fixtures/m9-00/s-f1d-stream-detail-with-policy.json` when the environment variable `M9_08_CAPTURE_DIR` names a directory (the bytes then come
 *     from the broker, not from a hand);
 *  2. does `alternate-exchange` set by a POLICY act on an exchange? Read from the answer of the management publish (`routed`); NO queue message is read;
 *  3. the `max-age` units `M`, `h`, `m` (status of the policy PUT);
 *  4. the `policy` and `type` fields of a queue row of the list the planner reads (they feed `CatalogKnownTopologyReader`), and the item GET of a policy.
 * A policy is applied by the broker about 6 s after the PUT: every wait is a bounded poll, never a fixed sleep.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9PolicyStepTest*'`. Answers are written to docs/tasks/M9-08b-stream-retention-from-policy.md.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9PolicyStepTest {

    companion object {
        private val container: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(6)
    private fun unique(base: String) = "m9p-$base-$run"
    private val cleanup = M9c.Cleanup(api)

    @AfterEach fun clean() = cleanup.run()

    private fun putPolicy(name: String, pattern: String, definitionJson: String, applyTo: String = "all", priority: Int = 0): MgmtApi.Reply {
        val reply = api.put("/api/policies/%2F/$name", """{"pattern":"$pattern","apply-to":"$applyTo","priority":$priority,"definition":$definitionJson}""")
        if (reply.ok) cleanup.policy(name)
        return reply
    }

    private fun policyOf(path: String): String? = (api.get(path).obj["policy"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun streamProfile() = ConnectionProfile(
        name = "m9p", host = container.host, virtualHost = "/", username = container.adminUsername, tls = false,
        streamPort = container.getMappedPort(5552), managementPort = container.getMappedPort(15672),
    )

    private fun capture(name: String, body: String) {
        val dir = System.getenv("M9_08_CAPTURE_DIR")?.takeIf { it.isNotBlank() } ?: return
        File(dir, name).writeText(body)
    }

    @Test
    fun `The floating tag 4-management is a 4_x broker`() = M9c.assertFourX(api)

    /** A stream created without retention, then a policy `{max-age: 7D, max-length-bytes: 1000000}` that has taken effect on it. */
    private fun streamWithPolicy(): String {
        val stream = unique("ret")
        StreamEnvironments(streamProfile(), container.adminPassword, BrokerTransport.Plain).use { envs ->
            assertEquals(StreamManagementResult.Success, runBlocking { RabbitMqStreamManager(envs).createStream("/", stream) })
        }
        cleanup.queue(stream)
        assertEquals(201, putPolicy("p-$stream", "^$stream$", """{"max-age":"7D","max-length-bytes":1000000}""").status)
        awaitUntil("the policy is applied to the stream", 90) { policyOf("/api/queues/%2F/$stream") != null }
        return stream
    }

    private val policyDefinition = Json.parseToJsonElement("""{"max-age":"7D","max-length-bytes":1000000}""")

    private fun appListPath(stream: String): String {
        val requests = ManagementRequests("http://${container.host}:${container.getMappedPort(15672)}", BrokerTransport.Plain)
        val url = requests.listStreams(dev.caerbannog.core.streams.StreamListRequest(vhost = "/", page = 1, pageSize = 100, nameFilter = stream, useRegex = false))
        return url.substringAfter(container.getMappedPort(15672).toString())
    }

    @Test
    fun `Claim 1 the broker leaves effective_policy_definition out of a list row unless the column is asked for, the detail GET always has it`() {
        val stream = streamWithPolicy()
        val detailBody = api.get("/api/queues/%2F/$stream").body
        capture("s-f1d-stream-detail-with-policy.json", detailBody)
        assertEquals(policyDefinition, Json.parseToJsonElement(detailBody).jsonObject["effective_policy_definition"], "the detail GET carries the effective policy definition")
        // The control: the column list WITHOUT the field (what the app sent before M9-08). Then the same list with the field.
        val without = appListPath(stream).replace("%2Ceffective_policy_definition", "")
        val withField = without.replace("%2Carguments", "%2Carguments%2Ceffective_policy_definition")
        assertTrue("effective_policy_definition" !in without && "effective_policy_definition" in withField, "without: $without, with: $withField")
        val controlRow = rowOf(api.get(without).body, stream)
        assertFalse(controlRow.containsKey("effective_policy_definition"), "the broker honours the column list exactly: the row has no effective_policy_definition: $controlRow")
        val row = rowOf(api.get(withField).body, stream)
        capture("s-f1d-stream-list-row-with-policy.json", row.toString())
        assertEquals(policyDefinition, row["effective_policy_definition"], "with the column added the list row carries it: $row")
    }

    @Test
    fun `Claim 1 the app's own list request and detail request give the retention of the policy (scenario Retention set by a policy on a real stream is shown)`() {
        val stream = streamWithPolicy()
        val page = (ManagementJson.parseStreamPage(api.get(appListPath(stream)).body) as CatalogResult.Success).value
        assertEquals(dev.caerbannog.core.streams.Retention(maxAge = "7D", maxLengthBytes = 1_000_000), page.rows.single { it.name == stream }.retention, "the list row")
        val detail = (ManagementJson.parseStreamDetail(api.get("/api/queues/%2F/$stream").body) as CatalogResult.Success).value
        assertEquals(dev.caerbannog.core.streams.Retention(maxAge = "7D", maxLengthBytes = 1_000_000), detail.retention, "the detail")
    }

    private fun rowOf(listBody: String, name: String): JsonObject =
        Json.parseToJsonElement(listBody).jsonObject["items"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `M9-08-alternate-exchange-on-exchange claim 2, alternate-exchange set by a policy acts on an exchange, read from the publish answer`() {
        val main = cleanup.exchange(unique("main")); val spare = cleanup.exchange(unique("spare")); val queue = cleanup.queue(unique("spare-q"))
        assertEquals(201, api.put("/api/exchanges/%2F/$spare", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.put("/api/exchanges/%2F/$main", """{"type":"direct","durable":true}""").status)
        assertEquals(201, api.put("/api/queues/%2F/$queue", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$spare/q/$queue", """{"routing_key":""}""").status)
        fun routed(exchange: String): Boolean =
            api.post("/api/exchanges/%2F/$exchange/publish", """{"properties":{},"routing_key":"nobody","payload":"x","payload_encoding":"string"}""")
                .obj["routed"]!!.jsonPrimitive.booleanOrNull!!
        assertFalse(routed(main), "control before the policy: nothing is bound for the key, the message is not routed")
        assertEquals(201, putPolicy(unique("ae"), "^$main$", """{"alternate-exchange":"$spare"}""", applyTo = "exchanges").status)
        awaitUntil("a message with no matching binding is routed through the alternate exchange", 90) { routed(main) }
        assertFalse(routed(cleanup.exchange(unique("plain")).also { assertEquals(201, api.put("/api/exchanges/%2F/$it", """{"type":"direct","durable":true}""").status) }), "control: an exchange with no policy still does not route it")
    }

    @Test
    fun `M9-08-max-age-units claim 3, the units M, h and m of max-age, status of the policy PUT`() {
        val answers = listOf("1M", "1h", "1m").associateWith { value ->
            putPolicy(unique("age-${value}"), "^$run-nomatch$", """{"max-age":"$value"}""", applyTo = "streams").status
        }
        println("M9-08 MEASURED max-age units: $answers")
        assertEquals(mapOf("1M" to 201, "1h" to 201, "1m" to 201), answers, "RECORD: the units answered 201 are the ones MaxAgeUnits.measured may hold")
    }

    @Test
    fun `Claim 4 the list row of a queue carries type and policy, and the policy item GET answers one policy`() {
        val queue = unique("lst")
        assertEquals(201, api.put("/api/queues/%2F/${cleanup.queue(queue)}", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").status)
        val policy = unique("lst-pol")
        assertEquals(201, putPolicy(policy, "^$queue$", """{"max-length":10}""", applyTo = "quorum_queues", priority = 3).status)
        awaitUntil("the policy is applied to the queue", 90) { policyOf("/api/queues/%2F/$queue") != null }
        val requests = ManagementRequests("http://${container.host}:${container.getMappedPort(15672)}", BrokerTransport.Plain)
        val url = requests.queues("/", 1).substringAfter(container.getMappedPort(15672).toString())
        val page = (ManagementJson.parseQueuePage(api.get(url).body, "/") as CatalogResult.Success).value
        val row: QueueRow = page.rows.single { it.name == queue }
        assertEquals("quorum", row.type)
        assertEquals(policy, row.policy, "the list row names the policy that applies: $row")
        // The item GET the check note uses.
        val item = api.get("/api/policies/%2F/$policy")
        assertEquals(200, item.status)
        capture("m9-08-policy-item.json", item.body)
        val parsed = (ManagementJson.parsePolicies("[${item.body}]") as CatalogResult.Success).value.single()
        assertEquals(3, parsed.priority)
        assertEquals("quorum_queues", parsed.applyTo)
        assertNotNull(parsed.definition["max-length"])
    }
}
