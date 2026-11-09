package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.BindingDestinationType
import io.ktor.client.request.HttpRequestPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertNotNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M5-08a live test (`@broker @m5-08 @m5-08a`, `@gate-M5`): the REAL executor against `rabbitmq:4-management` (Testcontainers, plain HTTP: the
 * profile has TLS off, D9a). Scenarios: "Apply a migration to a real broker" (through the connection's `migrationExecutor` of the real gateway),
 * "Exported topology can be applied to an empty broker", a queue conflict (400, queue unchanged), a policy that is equal (no PUT) and one that is
 * different (no PUT, unchanged), read-back, delete of an absent resource (404 is a no-op), binding delete with an empty and a non-empty routing key
 * (this test MEASURES the properties-key form, brief open point 1), an exchange-to-exchange binding, typed numbers, apply twice, rerun after a
 * failure at action 2, and an action built in code with the name `..` (the broker answers 405 and nothing is created).
 * Every test uses unique names and the class deletes what it created. It never reads a queue's messages: a request recorder fails the test if
 * the app sends anything to a path with `/get`, `/contents` or `/purge`, or anything but GET/PUT/POST/DELETE.
 * NOT RUN at step 2 (test developer): compiled only. Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM5MigrationTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM5MigrationTest {

    companion object {
        private val container: RabbitMQContainer = broker("rabbitmq:4-management")
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private fun unique(base: String) = "m508a-$base-$run"

    /** Every request the app sent in this test, as "METHOD /path". */
    private val sent = CopyOnWriteArrayList<String>()
    private val cleanup = mutableListOf<String>()
    private val toClose = mutableListOf<AutoCloseable>()

    private fun executorFor(c: RabbitMQContainer): TopologyMigrationExecutor {
        val client = TlsSupport.buildHttpClient(BrokerTransport.Plain, 10_000)
        client.requestPipeline.intercept(HttpRequestPipeline.Before) { sent += "${context.method.value} ${context.url.build().encodedPath}" }
        val http = ManagementHttp(client, c.adminUsername, c.adminPassword).also { toClose += it }
        val requests = ManagementRequests("http://${c.host}:${c.getMappedPort(15672)}", BrokerTransport.Plain)
        return ManagementTopologyMigrationExecutor(http, requests, UnavailableStreamManager) { kotlinx.coroutines.delay(it) }
    }

    private val executor: TopologyMigrationExecutor by lazy { executorFor(container) }

    private fun exec(action: MigrationAction): ActionResult = runBlocking { executor.execute(action) }

    private fun fileOf(version: String, vararg actions: MigrationAction): MigrationFile {
        val raw = MigrationParser.exportToJson(version, "m5-08a live", actions.toList())
        return (MigrationParser.parse(raw) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration
    }

    @AfterEach
    fun checkTheRecorderAndCleanUp() {
        try {
            val bad = sent.filter { line ->
                val (method, path) = line.split(" ", limit = 2)
                method !in setOf("GET", "PUT", "POST", "DELETE") || listOf("/get", "/contents", "/purge").any { path.contains(it) }
            }
            assertTrue(bad.isEmpty(), "the app sent a request it must never send: $bad")
        } finally {
            cleanup.asReversed().forEach { runCatching { api.delete(it) } }
            cleanup.clear()
            toClose.forEach { runCatching { it.close() } }
            toClose.clear()
        }
    }

    private fun track(path: String) { cleanup += path }

    // ---- the scenarios of feature 10 -----------------------------------------------------------------------------------

    @Test
    fun `Apply a migration to a real broker, through the executor of the real connection`() {
        val queue = unique("orders"); val exchange = unique("events")
        track("/api/queues/%2F/$queue"); track("/api/exchanges/%2F/$exchange")
        val secrets = object : SecretStore {
            override fun store(profileName: String, password: String) = Unit
            override fun retrieve(profileName: String): String? = container.adminPassword
            override fun delete(profileName: String) = Unit
        }
        val profile = ConnectionProfile(
            name = "m5-08a-live", host = container.host, virtualHost = "/", username = container.adminUsername,
            managementPort = container.getMappedPort(15672), streamPort = 5552, tls = false, topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
        )
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile) } as OpenResult.Opened
        toClose += opened.connection
        val connectionExecutor = assertNotNull(opened.connection.migrationExecutor, "the real held connection must carry the management migration executor")

        val history = InMemoryMigrationHistoryRepository()
        val file = fileOf(
            "0001",
            MigrationAction.DeclareQueue(queue, "quorum"),
            MigrationAction.DeclareExchange(exchange, "topic"),
            MigrationAction.DeclareBinding(exchange, queue, "order.created"),
        )
        val result = runBlocking { MigrationRunner(history, connectionExecutor).applyPending(profile.name, listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/") }
        assertEquals(MigrationApplyResult.Success(listOf("0001")), result)
        assertEquals("quorum", api.get("/api/queues/%2F/$queue").obj["arguments"]!!.jsonObject["x-queue-type"]!!.jsonPrimitive.content)
        val bindings = api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr
        assertEquals(listOf("order.created"), bindings.map { it.jsonObject["routing_key"]!!.jsonPrimitive.content })
        assertEquals(setOf("0001"), runBlocking { history.getAppliedMigrations(profile.name) }.keys)
    }

    @Test
    fun `Exported topology can be applied to an empty broker`() {
        val vhost = unique("gate")
        val queue = unique("orders"); val classic = unique("classic"); val exchange = unique("events"); val target = unique("dlx")
        // broker A is the shared container; broker B is a second, empty one
        api.put("/api/vhosts/$vhost", "{}"); track("/api/vhosts/$vhost")
        api.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""")
        assertTrue(api.put("/api/queues/$vhost/$queue", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").ok)
        assertTrue(api.put("/api/queues/$vhost/$classic", """{"durable":true,"arguments":{"x-queue-type":"classic"}}""").ok)
        assertTrue(api.put("/api/exchanges/$vhost/$exchange", """{"type":"topic","durable":true}""").ok)
        assertTrue(api.put("/api/exchanges/$vhost/$target", """{"type":"fanout","durable":true,"internal":false}""").ok)
        assertTrue(api.post("/api/bindings/$vhost/e/$exchange/q/$queue", """{"routing_key":"order.created"}""").ok)
        assertTrue(api.post("/api/bindings/$vhost/e/$exchange/e/$target", """{"routing_key":"dead"}""").ok)

        val clientA = TlsSupport.buildHttpClient(BrokerTransport.Plain, 10_000)
        val httpA = ManagementHttp(clientA, container.adminUsername, container.adminPassword).also { toClose += it }
        val requestsA = ManagementRequests("http://${container.host}:${container.getMappedPort(15672)}", BrokerTransport.Plain)
        val files = InMemoryMigrationFileRepository()
        val viewModel = MigrationsViewModel(
            fileRepository = files, runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor),
            topologyCatalog = ManagementTopologyCatalog(httpA, requestsA), vhost = vhost,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val exported = runBlocking { viewModel.exportCurrentTopology() }

        broker("rabbitmq:4-management").use { b ->
            b.start()
            val apiB = MgmtApi(b)
            assertTrue(apiB.put("/api/vhosts/$vhost", "{}").ok)
            apiB.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""")
            val executorB = executorFor(b)
            val result = runBlocking { MigrationRunner(InMemoryMigrationHistoryRepository(), executorB).applyPending("b", listOf(exported), true, TopologyControlLevel.FULL_CONTROL, vhost = vhost) }
            assertEquals(MigrationApplyResult.Success(listOf(exported.version)), result)

            fun shape(a: MgmtApi): Set<String> = buildSet {
                a.get("/api/queues/$vhost").arr.forEach { q -> q.jsonObject.let { add("queue ${it["name"]!!.jsonPrimitive.content} ${it["type"]!!.jsonPrimitive.content} ${it["durable"]!!.jsonPrimitive.content}") } }
                a.get("/api/exchanges/$vhost").arr.map { it.jsonObject }.filter { it["name"]!!.jsonPrimitive.content.let { n -> n.isNotEmpty() && !n.startsWith("amq.") } }
                    .forEach { add("exchange ${it["name"]!!.jsonPrimitive.content} ${it["type"]!!.jsonPrimitive.content} ${it["durable"]!!.jsonPrimitive.content}") }
                a.get("/api/bindings/$vhost").arr.map { it.jsonObject }.filter { it["source"]!!.jsonPrimitive.content.isNotEmpty() }
                    .forEach { add("binding ${it["source"]!!.jsonPrimitive.content} ${it["destination_type"]!!.jsonPrimitive.content} ${it["destination"]!!.jsonPrimitive.content} ${it["routing_key"]!!.jsonPrimitive.content}") }
            }
            assertEquals(shape(api), shape(apiB), "B must have the same queues, exchanges and bindings as A")
            assertEquals(6, shape(apiB).size)
        }
    }

    @Test
    fun `a queue that exists with other arguments answers 400, the executor reports a conflict and the queue is unchanged`() {
        val queue = unique("conflict"); track("/api/queues/%2F/$queue")
        assertTrue(api.declareQueue(queue, """{"x-queue-type":"quorum","x-delivery-limit":3}""").ok)
        // the raw broker answer the writer relies on: 400 with PRECONDITION_FAILED
        val raw = api.declareQueue(queue, """{"x-queue-type":"quorum","x-delivery-limit":5}""")
        assertEquals(400, raw.status, raw.toString())
        assertTrue(raw.body.contains("PRECONDITION_FAILED"), raw.body)

        val result = exec(MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))))
        assertTrue((result as ActionResult.Failed).reason.contains("exists with different settings"), result.toString())
        assertFalse(result.reason.contains("PRECONDITION"), "the broker's text must not be copied")
        assertEquals("3", api.get("/api/queues/%2F/$queue").obj["arguments"]!!.jsonObject["x-delivery-limit"]!!.jsonPrimitive.content)
        assertTrue(sent.none { it.startsWith("DELETE") }, sent.toString())
    }

    @Test
    fun `a policy that is equal gets no request that writes, one that differs gets none either and stays as it is`() {
        val policy = unique("limits"); track("/api/policies/%2F/$policy")
        val pattern = "^never-matches-$run$"
        assertTrue(api.put("/api/policies/%2F/$policy", """{"pattern":"$pattern","apply-to":"queues","priority":0,"definition":{"max-length":500}}""").ok)
        sent.clear()
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclarePolicy(policy, pattern, mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues")))
        assertTrue(sent.none { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("DELETE") }, "an equal policy was written: $sent")

        sent.clear()
        val result = exec(MigrationAction.DeclarePolicy(policy, pattern, mapOf("max-length" to ArgumentValue.Whole(1000)), applyTo = "queues"))
        assertTrue((result as ActionResult.Failed).reason.contains("exists with different settings"), result.toString())
        assertTrue(sent.none { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("DELETE") }, "a different policy was overwritten: $sent")
        assertEquals("500", api.get("/api/policies/%2F/$policy").obj["definition"]!!.jsonObject["max-length"]!!.jsonPrimitive.content)
    }

    @Test
    fun `numbers are read back as numbers and a text stays a text, and the declared queue reads back`() {
        val queue = unique("typed"); track("/api/queues/%2F/$queue")
        val result = exec(MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-note" to ArgumentValue.Text("5"))))
        assertEquals(ActionResult.Success, result)
        val arguments = api.get("/api/queues/%2F/$queue").obj["arguments"]!!.jsonObject
        assertFalse(arguments["x-delivery-limit"]!!.jsonPrimitive.isString, "5 came back as a string")
        assertTrue(arguments["x-note"]!!.jsonPrimitive.isString, "\"5\" came back as a number")
        assertTrue(sent.any { it.startsWith("GET /api/queues/%2F/$queue") }, "no read-back GET: $sent")
    }

    @Test
    fun `deleting something that is already gone is a no-op, the broker answers 404`() {
        val queue = unique("absent")
        assertEquals(404, api.delete("/api/queues/%2F/$queue").status, "the premise: the broker answers 404 to a DELETE of an absent queue")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteQueue(queue)))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteExchange(unique("absentx"))))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeletePolicy(unique("absentp"))))
    }

    @Test
    fun `a binding is deleted with an empty and with a non-empty routing key, and the properties key form is measured`() {
        val queue = unique("bq"); val exchange = unique("bx")
        track("/api/queues/%2F/$queue"); track("/api/exchanges/%2F/$exchange")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareQueue(queue, "quorum")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareExchange(exchange, "direct")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareBinding(exchange, queue, "")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareBinding(exchange, queue, "order.created")))
        val keys = api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr.map { it.jsonObject["routing_key"]!!.jsonPrimitive.content to it.jsonObject["properties_key"]!!.jsonPrimitive.content }.toMap()
        println("M5-08a measured properties_key on rabbitmq:4-management: $keys")
        assertEquals("~", keys[""], "properties key of an empty routing key: $keys")
        assertEquals("order.created", keys["order.created"], "properties key of a non-empty routing key: $keys")

        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding(exchange, queue, "order.created")))
        assertEquals(listOf(""), api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr.map { it.jsonObject["routing_key"]!!.jsonPrimitive.content }, "only the named binding must go")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding(exchange, queue)))
        assertTrue(api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr.isEmpty())
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding(exchange, queue)), "a second delete is a no-op")
    }

    @Test
    fun `an exchange-to-exchange binding is applied and read back`() {
        val source = unique("e2e-src"); val destination = unique("e2e-dst")
        track("/api/exchanges/%2F/$source"); track("/api/exchanges/%2F/$destination")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareExchange(source, "topic")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareExchange(destination, "fanout")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareBinding(source, destination, "dead", destinationType = BindingDestinationType.EXCHANGE)))
        val binding = api.get("/api/bindings/%2F/e/$source/e/$destination").arr.single().jsonObject
        assertEquals("exchange", binding["destination_type"]!!.jsonPrimitive.content)
        assertEquals("dead", binding["routing_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applying a migration twice changes nothing the second time and deletes nothing`() {
        val queue = unique("twice"); val exchange = unique("twicex"); val policy = unique("twicep")
        track("/api/queues/%2F/$queue"); track("/api/exchanges/%2F/$exchange"); track("/api/policies/%2F/$policy")
        val actions = listOf(
            MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
            MigrationAction.DeclareExchange(exchange, "topic"),
            MigrationAction.DeclareBinding(exchange, queue, "k"),
            MigrationAction.DeclarePolicy(policy, "^never-matches-$run$", mapOf("max-length" to ArgumentValue.Whole(10)), applyTo = "queues"),
        )
        actions.forEach { assertEquals(ActionResult.Success, exec(it), it.actionName) }
        fun state() = listOf("/api/queues/%2F/$queue", "/api/exchanges/%2F/$exchange", "/api/bindings/%2F/e/$exchange/q/$queue", "/api/policies/%2F/$policy").map { api.get(it).body }
        val before = state()
        sent.clear()
        actions.forEach { assertEquals(ActionResult.Success, exec(it), "second run ${it.actionName}") }
        assertEquals(before, state())
        assertTrue(sent.none { it.startsWith("DELETE") }, sent.toString())
        assertTrue(sent.none { it.startsWith("PUT /api/policies") }, "the equal policy was written again: $sent")
    }

    @Test
    fun `a rerun after a failure at action 2 finishes the migration and records it only then`() {
        val queue = unique("rerun"); val exchange = unique("rerunx"); val policy = unique("rerunp")
        track("/api/queues/%2F/$queue"); track("/api/exchanges/%2F/$exchange"); track("/api/policies/%2F/$policy")
        val history = InMemoryMigrationHistoryRepository()
        val file = fileOf(
            "0007",
            MigrationAction.DeclareQueue(queue, "quorum"),
            MigrationAction.DeclareBinding(exchange, queue, "k"), // the exchange does not exist yet: the broker refuses (404)
            MigrationAction.DeclarePolicy(policy, "^never-matches-$run$", mapOf("max-length" to ArgumentValue.Whole(10)), applyTo = "queues"),
        )
        val runner = MigrationRunner(history, executor)
        val first = runBlocking { runner.applyPending("p", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/") } as MigrationApplyResult.ActionFailed
        assertEquals(2 to 3, first.index to first.total)
        assertTrue(runBlocking { history.getAppliedMigrations("p") }.isEmpty(), "a failed migration must not be recorded")
        assertTrue(api.get("/api/queues/%2F/$queue").ok, "action 1 stays applied")
        assertEquals(404, api.get("/api/policies/%2F/$policy").status, "action 3 never ran")

        assertTrue(api.put("/api/exchanges/%2F/$exchange", """{"type":"direct","durable":true}""").ok) // the world changes: the refusal goes away
        val second = runBlocking { runner.applyPending("p", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/") }
        assertEquals(MigrationApplyResult.Success(listOf("0007")), second)
        assertEquals(setOf("0007"), runBlocking { history.getAppliedMigrations("p") }.keys)
        assertTrue(api.get("/api/policies/%2F/$policy").ok)
    }

    @Test
    fun `an action built in code with the name dot dot answers 405 and creates nothing`() {
        val queuesBefore = api.get("/api/queues/%2F").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val exchangesBefore = api.get("/api/exchanges/%2F").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        for (action in listOf(MigrationAction.DeclareQueue(".."), MigrationAction.DeclareExchange(".."), MigrationAction.DeclareQueue("."), MigrationAction.DeclarePolicy("..", "^x"))) {
            val result = exec(action)
            assertTrue(result is ActionResult.Failed, "${action.actionName} .. gave $result")
        }
        assertEquals(queuesBefore, api.get("/api/queues/%2F").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        assertEquals(exchangesBefore, api.get("/api/exchanges/%2F").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        assertTrue(sent.none { path -> path.split(" ")[1].split('/').any { it == ".." || it == "." } }, "a raw dot segment reached the wire: $sent")
    }
}
