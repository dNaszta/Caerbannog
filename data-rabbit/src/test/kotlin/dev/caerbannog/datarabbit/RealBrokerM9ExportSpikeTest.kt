package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.URLEncoder
import java.util.UUID

/**
 * M9-14 spike, items E1 to E7 of `docs/tasks/M9-14-export-keeps-arguments-and-policies.md`: what the topology export may rely on, MEASURED on a
 * throwaway `rabbitmq:4-management` (plugins `rabbitmq_stream`, `rabbitmq_stream_management`, `rabbitmq_mqtt`). Results and the exact versions are in
 * `docs/tasks/M9-14-spike-results.md`; raw answers in `fixtures/m9-14/` (recorded when the environment variable `RECORD_M9_14_FIXTURES` is set).
 * The image tag floats: a 4.x that answers differently fails here on purpose.
 *
 * E1 to E6 only read facts (and assert them). E7 is the LIVE GATE of the build: seed broker A with the rich topology, export it with the real catalog and
 * the real [MigrationsViewModel], apply the file to an empty broker B with the real runner and executor, read B back and compare, then export B and
 * compare the two files. E7 starts RED (the exporter drops arguments, policies and streams today) and goes green with the build.
 * Every name is unique per run; each test creates its own virtual host and the class deletes what it created.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9ExportSpikeTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.MethodName::class)
class RealBrokerM9ExportSpikeTest {

    companion object {
        private const val IMAGE = "rabbitmq:4-management"
        private const val QUEUE_COLUMNS = "name,type,durable,auto_delete,exclusive,arguments"

        private val rabbit: RabbitMQContainer =
            brokerWithPlugins(IMAGE, "rabbitmq_stream", "rabbitmq_stream_management", "rabbitmq_mqtt").apply { addExposedPorts(5552, 1883) }
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }

        private val record = System.getenv("RECORD_M9_14_FIXTURES") != null
        private val factsFile = File("build/m9-14-facts.txt")

        /** One measured fact, printed and kept in `build/m9-14-facts.txt` for the results file. */
        fun fact(item: String, text: String) {
            println("M9-14 $item: $text")
            factsFile.parentFile.mkdirs()
            factsFile.appendText("$item: $text\n")
        }

        fun save(name: String, body: String) {
            if (!record) return
            val dir = File("src/test/resources/fixtures/m9-14"); dir.mkdirs()
            File(dir, name).writeText(body.trim() + "\n")
        }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private val vhosts = mutableListOf<String>()
    private val users = mutableListOf<String>()
    private val cleanup = mutableListOf<String>()
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanUp() {
        toClose.asReversed().forEach { runCatching { it.close() } }; toClose.clear()
        cleanup.asReversed().forEach { runCatching { api.delete(it) } }; cleanup.clear()
        vhosts.forEach { runCatching { api.delete("/api/vhosts/${enc(it)}") } }; vhosts.clear()
        users.forEach { runCatching { api.delete("/api/users/$it") } }; users.clear()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun newVhost(label: String): String {
        val vhost = "m914-$label-$run"
        assertTrue(api.put("/api/vhosts/$vhost", "{}").ok, "vhost $vhost")
        assertTrue(api.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""").ok)
        vhosts += vhost
        return vhost
    }

    private fun json(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject

    private fun queueList(vhost: String, columns: String? = QUEUE_COLUMNS): JsonObject {
        val suffix = if (columns == null) "" else "&columns=${enc(columns)}"
        val reply = api.get("/api/queues/${enc(vhost)}?page=1&page_size=500$suffix")
        assertTrue(reply.ok, reply.toString())
        return reply.obj
    }

    private fun queueItems(vhost: String, columns: String? = QUEUE_COLUMNS): Map<String, JsonObject> =
        queueList(vhost, columns)["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }

    private fun prim(o: JsonObject, key: String): JsonPrimitive = o[key]!!.jsonPrimitive

    // ---- E0: which broker -------------------------------------------------------------------------------------------------

    @Test
    fun `E0 the broker is a 4 x and its exact versions are recorded`() {
        val overview = api.get("/api/overview").obj
        val version = prim(overview, "rabbitmq_version").content
        assertTrue(version.startsWith("4."), "this spike is about 4.x, got $version")
        val arch = rabbit.execInContainer("uname", "-m").stdout.trim()
        fact("E0", "rabbitmq_version=$version erlang_version=${prim(overview, "erlang_version").content} management_version=${prim(overview, "management_version").content} arch=$arch image=$IMAGE")
        save("overview-versions.json", buildJsonObject {
            put("rabbitmq_version", version); put("erlang_version", prim(overview, "erlang_version").content); put("arch", arch)
        }.toString())
    }

    // ---- E1: the queue list columns and the JSON type of each value ----------------------------------------------------------

    private fun seedQueuesE1(vhost: String): AutoCloseable {
        val v = enc(vhost)
        assertTrue(api.put("/api/queues/$v/classic-args", """{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":60000,"x-max-length":1000,"x-dead-letter-exchange":"dlx","x-overflow":"reject-publish","x-max-priority":5}}""").ok)
        assertTrue(api.put("/api/queues/$v/quorum-args", """{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum","x-delivery-limit":5,"x-dead-letter-exchange":"dlx","x-max-length":100}}""").ok)
        assertTrue(api.put("/api/queues/$v/stream-args", """{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":1000000000}}""").ok)
        val conn = amqpConnection(rabbit, "m9-14-e1").also { toClose += it }
        // the broker cannot declare an exclusive queue over HTTP: this one is declared over AMQP and lives while the connection is open
        // (the AMQP client connects to "/" by default: the vhost is given in the URI-less factory below)
        return conn
    }

    @Test
    fun `E1 the queue list with columns gives durable, auto_delete, exclusive and typed arguments`() {
        val vhost = newVhost("e1")
        val v = enc(vhost)
        seedQueuesE1(vhost)
        // non-durable auto-delete classic over HTTP: record what a default 4.x says
        val transient = api.put("/api/queues/$v/transient-autodelete", """{"durable":false,"auto_delete":true,"arguments":{"x-queue-type":"classic"}}""")
        fact("E1", "PUT non-durable auto-delete classic queue over HTTP on default 4.x: status=${transient.status} body=${transient.body.take(200)}")
        // exclusive over AMQP in this vhost
        val factory = com.rabbitmq.client.ConnectionFactory().apply {
            host = rabbit.host; port = rabbit.amqpPort; username = "guest"; password = "guest"; virtualHost = vhost; isAutomaticRecoveryEnabled = false
        }
        val amqp = factory.newConnection("m9-14-e1-exclusive").also { toClose += it }
        val channel = amqp.createChannel()
        channel.queueDeclare("exclusive-named", false, true, false, mapOf("x-message-ttl" to 5000))
        val serverNamed = channel.queueDeclare("", false, true, true, null).queue
        fact("E1", "server-named queue: $serverNamed")

        awaitUntil("queues listed") { queueItems(vhost).keys.containsAll(listOf("classic-args", "quorum-args", "stream-args", "exclusive-named", serverNamed)) }
        val listBody = api.get("/api/queues/$v?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}")
        save("e1-queues-columns.json", listBody.body)
        val items = queueItems(vhost)

        // columns honoured: which keys each item has
        val keys = items.mapValues { it.value.keys.sorted() }
        fact("E1", "keys per item with columns=$QUEUE_COLUMNS: ${keys.values.toSet()}")
        for ((name, item) in items) {
            assertTrue(item.keys.containsAll(listOf("name", "type", "durable", "auto_delete", "exclusive", "arguments")), "$name has keys ${item.keys}")
            assertTrue(prim(item, "durable").booleanOrNull != null && prim(item, "auto_delete").booleanOrNull != null && prim(item, "exclusive").booleanOrNull != null, "$name flags are JSON booleans: $item")
        }
        val classic = items["classic-args"]!!; val quorum = items["quorum-args"]!!; val stream = items["stream-args"]!!
        assertEquals("classic", prim(classic, "type").content)
        assertEquals("quorum", prim(quorum, "type").content)
        assertEquals("stream", prim(stream, "type").content)
        val ca = classic["arguments"]!!.jsonObject
        assertFalse(prim(ca, "x-message-ttl").isString, "x-message-ttl must be a number: $ca")
        assertFalse(prim(ca, "x-max-length").isString)
        assertFalse(prim(ca, "x-max-priority").isString)
        assertTrue(prim(ca, "x-dead-letter-exchange").isString)
        assertTrue(prim(ca, "x-overflow").isString)
        val qa = quorum["arguments"]!!.jsonObject
        assertFalse(prim(qa, "x-delivery-limit").isString, "x-delivery-limit must be a number: $qa")
        assertTrue(prim(qa, "x-dead-letter-exchange").isString)
        assertFalse(prim(qa, "x-max-length").isString)
        val sa = stream["arguments"]!!.jsonObject
        assertTrue(prim(sa, "x-max-age").isString, "x-max-age is text (7D): $sa")
        assertFalse(prim(sa, "x-max-length-bytes").isString, "x-max-length-bytes is a number: $sa")
        fact("E1", "arguments classic=$ca quorum=$qa stream=$sa")
        for ((name, item) in items) fact("E1", "flags $name: type=${prim(item, "type").content} durable=${prim(item, "durable").content} auto_delete=${prim(item, "auto_delete").content} exclusive=${prim(item, "exclusive").content}")

        // the exclusive queue
        val exclusive = items["exclusive-named"]!!
        assertTrue(prim(exclusive, "exclusive").booleanOrNull == true, "an exclusive queue says exclusive=true: $exclusive")
        assertTrue(prim(classic, "exclusive").booleanOrNull == false)
        // the stream is IN the list (the app's TopologyCatalog.queues drops it on purpose)
        assertTrue(items.containsKey("stream-args"), "the list holds the stream")
        // page fields
        val list = queueList(vhost)
        fact("E1", "page fields: ${list.keys.sorted()} total_count=${list["total_count"]}")
    }

    // ---- E2: arguments the broker adds by itself ---------------------------------------------------------------------------

    @Test
    fun `E2 which arguments the broker adds by itself`() {
        val vhost = newVhost("e2")
        val v = enc(vhost)
        assertTrue(api.put("/api/queues/$v/plain-classic", """{"durable":true,"arguments":{}}""").ok)
        assertTrue(api.put("/api/queues/$v/typed-classic", """{"durable":true,"arguments":{"x-queue-type":"classic"}}""").ok)
        assertTrue(api.put("/api/queues/$v/plain-quorum", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").ok)
        assertTrue(api.put("/api/queues/$v/plain-stream", """{"durable":true,"arguments":{"x-queue-type":"stream"}}""").ok)
        assertTrue(api.put("/api/queues/$v/with-policy", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").ok)
        assertTrue(api.put("/api/policies/$v/limits", """{"pattern":"^with-policy$","apply-to":"queues","priority":1,"definition":{"max-length":50}}""").ok)
        awaitUntil("queues listed") { queueItems(vhost).size == 5 }
        val items = queueItems(vhost)
        save("e2-queues-no-arguments.json", api.get("/api/queues/$v?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}").body)
        for ((name, item) in items) fact("E2", "$name type=${prim(item, "type").content} arguments=${item["arguments"]}")
        val extras = items.mapValues { (_, item) -> item["arguments"]!!.jsonObject.keys - "x-queue-type" }
        fact("E2", "arguments other than x-queue-type that the broker stores by itself: $extras")
        assertEquals(emptySet<String>(), extras.values.flatten().toSet(), "the broker added an argument besides x-queue-type: $extras")
        // the single-queue object: where storage_version and the effective policy are (not in arguments)
        val full = api.get("/api/queues/$v/with-policy").obj
        fact("E2", "single quorum queue object keys: ${full.keys.sorted()}")
        assertFalse(full["arguments"]!!.jsonObject.containsKey("max-length"), "a policy never shows in arguments")
        save("e2-queue-single-with-policy.json", full.toString())
        // the default queue type of the vhost does not change what a queue with an explicit type says
        fact("E2", "plain-classic (declared with no x-queue-type) arguments=${items["plain-classic"]!!["arguments"]} type=${prim(items["plain-classic"]!!, "type").content}")
    }

    // ---- E3: what an export leaves out --------------------------------------------------------------------------------------

    private class Mqtt(val client: MqttClient)

    private fun mqttClient(clientId: String, clean: Boolean, qos: Int, topic: String): MqttClient {
        val client = MqttClient("tcp://${rabbit.host}:${rabbit.getMappedPort(1883)}", clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply { userName = "guest"; setPassword("guest".toCharArray()); isCleanSession = clean }
        client.connect(options)
        client.subscribe(topic, qos)
        return client
    }

    @Test
    fun `E3 exclusive, server-named and MQTT subscription queues are recognisable from the export columns`() {
        val vhost = "/"
        val clients = listOf(
            mqttClient("m914-q0-$run", clean = true, qos = 0, topic = "m914/$run/q0"),
            mqttClient("m914-q1-$run", clean = true, qos = 1, topic = "m914/$run/q1"),
            mqttClient("m914-p1-$run", clean = false, qos = 1, topic = "m914/$run/p1"),
        )
        toClose += AutoCloseable { clients.forEach { runCatching { it.disconnect(); it.close() } } }
        val amqp = amqpConnection(rabbit, "m9-14-e3").also { toClose += it }
        val channel = amqp.createChannel()
        val exclusiveName = "m914-exclusive-$run"
        channel.queueDeclare(exclusiveName, false, true, false, null)
        val serverNamed = channel.queueDeclare("", false, true, true, null).queue
        cleanup += "/api/queues/%2F/m914-keep-$run"
        assertTrue(api.put("/api/queues/%2F/m914-keep-$run", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").ok)

        awaitUntil("mqtt subscription queues listed") { queueItems(vhost).keys.count { it.startsWith("mqtt-subscription-m914-") && it.contains(run) } >= 3 }
        val items = queueItems(vhost)
        save("e3-queues-with-mqtt-and-exclusive.json", api.get("/api/queues/%2F?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}").body)
        val mqtt = items.filterKeys { it.startsWith("mqtt-subscription-m914-") && it.contains(run) }
        for ((name, item) in mqtt) fact("E3", "MQTT queue $name type=${prim(item, "type").content} durable=${prim(item, "durable").content} auto_delete=${prim(item, "auto_delete").content} exclusive=${prim(item, "exclusive").content} arguments=${item["arguments"]}")
        val ex = items[exclusiveName]!!
        fact("E3", "exclusive queue $exclusiveName type=${prim(ex, "type").content} durable=${prim(ex, "durable").content} auto_delete=${prim(ex, "auto_delete").content} exclusive=${prim(ex, "exclusive").content}")
        val gen = items[serverNamed]!!
        fact("E3", "server-named $serverNamed durable=${prim(gen, "durable").content} auto_delete=${prim(gen, "auto_delete").content} exclusive=${prim(gen, "exclusive").content}")
        assertTrue(serverNamed.startsWith("amq.gen-"), "a server-named queue starts with amq.gen-: $serverNamed")
        assertTrue(prim(ex, "exclusive").booleanOrNull == true)
        assertTrue(prim(gen, "exclusive").booleanOrNull == true)
        assertTrue(mqtt.size >= 3, "MQTT subscription queues for the 3 clients are in the queue list: ${mqtt.keys}")
        assertTrue(mqtt.keys.all { it.startsWith("mqtt-subscription-") })
        val keep = items["m914-keep-$run"]!!
        assertTrue(prim(keep, "durable").booleanOrNull == true && prim(keep, "exclusive").booleanOrNull == false)
        // the owner of an MQTT queue is not a column the export can use; is anything else different between an MQTT queue and a user queue?
        val detail = api.get("/api/queues/%2F/${enc(mqtt.keys.first())}")
        save("e3-mqtt-queue-single.json", detail.body)
        fact("E3", "MQTT queue single object keys: ${detail.obj.keys.sorted()} owner_pid_details=${detail.obj["owner_pid_details"]}")
        // a persistent MQTT session queue stays after the client leaves
        clients[2].disconnect()
        val afterLeave = queueItems(vhost)
        val persistent = afterLeave.keys.filter { it.contains("m914-p1-$run") }
        fact("E3", "persistent session queue after the client disconnected: $persistent")
        val gone = afterLeave.keys.filter { it.contains("m914-q1-$run") || it.contains("m914-q0-$run") }
        fact("E3", "clean-session queues still listed while their clients stay connected: $gone")
        persistent.forEach { cleanup += "/api/queues/%2F/${enc(it)}" }
    }

    @Test
    fun `E3 a declareQueue of a transient or auto-delete queue on a default 4 x`() {
        val vhost = newVhost("e3b")
        val executor = openHeld(vhost).migrationExecutor!!
        val cases = listOf(
            "non-durable" to MigrationAction.DeclareQueue("e3-nondurable", "classic", vhost, durable = false),
            "non-durable-autodelete" to MigrationAction.DeclareQueue("e3-nd-autodelete", "classic", vhost, durable = false, autoDelete = true),
            "durable-autodelete-classic" to MigrationAction.DeclareQueue("e3-d-autodelete", "classic", vhost, durable = true, autoDelete = true),
            "durable-autodelete-quorum" to MigrationAction.DeclareQueue("e3-q-autodelete", "quorum", vhost, durable = true, autoDelete = true),
        )
        for ((label, action) in cases) {
            val result = runBlocking { executor.execute(action) }
            val exists = api.get("/api/queues/${enc(vhost)}/${action.name}")
            fact("E3", "executor DeclareQueue $label -> ${if (result is ActionResult.Failed) "Failed(${result.reason})" else result}; GET after: ${exists.status}")
            save("e3-declare-$label.json", """{"result":"$result","getStatus":${exists.status}}""")
        }
        // raw broker answers, no executor
        val raw = api.put("/api/queues/${enc(vhost)}/e3-raw-nondurable", """{"durable":false,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}""")
        fact("E3", "raw PUT non-durable classic: ${raw.status} ${raw.body.take(220)}")
    }

    // ---- E1b: the non-durable auto-delete classic queue, on a broker that still permits the deprecated feature ---------------

    @Test
    fun `E1b a non-durable auto-delete queue on a broker that permits transient queues`() {
        val permissive = brokerWithPlugins(IMAGE).withCopyToContainer(
            Transferable.of("deprecated_features.permit.transient_nonexcl_queues = true\n"), "/etc/rabbitmq/conf.d/20-m9-14-permit.conf",
        )
        permissive.start()
        try {
            val a = MgmtApi(permissive)
            val reply = a.put("/api/queues/%2F/e1b-transient", """{"durable":false,"auto_delete":true,"arguments":{"x-queue-type":"classic"}}""")
            fact("E1b", "PUT non-durable auto-delete classic on a broker with deprecated_features.permit.transient_nonexcl_queues=true: ${reply.status} ${reply.body.take(200)}")
            if (reply.ok) {
                val item = a.get("/api/queues/%2F?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}").obj["items"]!!.jsonArray.map { it.jsonObject }.single { prim(it, "name").content == "e1b-transient" }
                save("e1b-transient-queue-permitted.json", item.toString())
                fact("E1b", "row: type=${prim(item, "type").content} durable=${prim(item, "durable").content} auto_delete=${prim(item, "auto_delete").content} exclusive=${prim(item, "exclusive").content} arguments=${item["arguments"]}")
                assertEquals(false, prim(item, "durable").booleanOrNull)
                assertEquals(true, prim(item, "auto_delete").booleanOrNull)
                // does the executor apply it to a second such broker, and is a non-durable queue in the export list worth keeping?
            }
        } finally {
            permissive.stop()
        }
    }

    // ---- E4: exchanges and bindings --------------------------------------------------------------------------------------------

    @Test
    fun `E4 exchange and binding arguments keep their JSON types and the built-in exchanges are visible`() {
        val vhost = newVhost("e4")
        val v = enc(vhost)
        assertTrue(api.put("/api/exchanges/$v/dlx", """{"type":"fanout","durable":true}""").ok)
        assertTrue(api.put("/api/exchanges/$v/with-ae", """{"type":"topic","durable":true,"arguments":{"alternate-exchange":"dlx"}}""").ok)
        assertTrue(api.put("/api/exchanges/$v/marker", """{"type":"direct","durable":true,"arguments":{"x-super-stream":true}}""").ok)
        assertTrue(api.put("/api/exchanges/$v/internal-x", """{"type":"fanout","durable":true,"internal":true}""").ok)
        assertTrue(api.put("/api/exchanges/$v/auto-x", """{"type":"fanout","durable":false,"auto_delete":true}""").ok)
        assertTrue(api.put("/api/exchanges/$v/hdr", """{"type":"headers","durable":true}""").ok)
        assertTrue(api.put("/api/queues/$v/q1", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").ok)
        assertTrue(api.post("/api/bindings/$v/e/hdr/q/q1", """{"routing_key":"","arguments":{"x-match":"all","region":"eu","level":3,"urgent":true,"ratio":1.5}}""").ok)
        assertTrue(api.post("/api/bindings/$v/e/amq.topic/q/q1", """{"routing_key":"sensors.#"}""").ok)
        assertTrue(api.post("/api/bindings/$v/e/with-ae/e/dlx", """{"routing_key":"dead","arguments":{"note":"text"}}""").ok)
        assertTrue(api.post("/api/bindings/$v/e/marker/q/q1", """{"routing_key":"p0","arguments":{"x-stream-partition-order":0}}""").ok)
        awaitUntil("queue listed") { queueItems(vhost).containsKey("q1") }

        val exchangesBody = api.get("/api/exchanges/$v")
        val bindingsBody = api.get("/api/bindings/$v")
        save("e4-exchanges.json", exchangesBody.body); save("e4-bindings.json", bindingsBody.body)
        val exchanges = exchangesBody.arr.map { it.jsonObject }.associateBy { prim(it, "name").content }
        val bindings = bindingsBody.arr.map { it.jsonObject }

        val ae = exchanges["with-ae"]!!["arguments"]!!.jsonObject
        assertTrue(prim(ae, "alternate-exchange").isString, "alternate-exchange is text: $ae")
        val marker = exchanges["marker"]!!["arguments"]!!.jsonObject
        assertEquals(true, prim(marker, "x-super-stream").booleanOrNull, "x-super-stream is a JSON boolean: $marker")
        assertEquals(true, prim(exchanges["internal-x"]!!, "internal").booleanOrNull)
        assertEquals(false, prim(exchanges["dlx"]!!, "internal").booleanOrNull)
        assertEquals(false, prim(exchanges["auto-x"]!!, "durable").booleanOrNull)
        assertEquals(true, prim(exchanges["auto-x"]!!, "auto_delete").booleanOrNull)
        fact("E4", "exchange keys: ${exchanges["with-ae"]!!.keys.sorted()}; the built-in exchanges listed: ${exchanges.keys.filter { it.startsWith("amq.") || it.isEmpty() }.sorted()}")

        val header = bindings.single { prim(it, "source").content == "hdr" }["arguments"]!!.jsonObject
        assertEquals("all", prim(header, "x-match").content)
        assertTrue(prim(header, "region").isString)
        assertFalse(prim(header, "level").isString, "a whole number stays a number: $header")
        assertEquals("3", prim(header, "level").content)
        assertEquals(true, prim(header, "urgent").booleanOrNull)
        assertFalse(prim(header, "ratio").isString)
        fact("E4", "headers binding arguments: $header (level=${prim(header, "level").content}, ratio=${prim(header, "ratio").content})")
        fact("E4", "binding keys: ${bindings.first { prim(it, "source").content == "hdr" }.keys.sorted()}")

        val topic = bindings.single { prim(it, "source").content == "amq.topic" }
        fact("E4", "a user binding on amq.topic is listed: source=${prim(topic, "source").content} destination=${prim(topic, "destination").content} routing_key=${prim(topic, "routing_key").content} arguments=${topic["arguments"]}")
        val e2e = bindings.single { prim(it, "source").content == "with-ae" }
        assertEquals("exchange", prim(e2e, "destination_type").content)
        val partition = bindings.single { prim(it, "source").content == "marker" }["arguments"]!!.jsonObject
        assertFalse(prim(partition, "x-stream-partition-order").isString, "x-stream-partition-order is a number: $partition")
        // implicit bindings of the default exchange
        val implicit = bindings.filter { prim(it, "source").content.isEmpty() }
        fact("E4", "implicit default-exchange bindings listed: ${implicit.map { prim(it, "destination").content }}")
        assertTrue(implicit.any { prim(it, "destination").content == "q1" })
        // an empty routing key and the property key
        fact("E4", "binding with empty routing key: routing_key='${prim(bindings.first { prim(it, "source").content == "hdr" }, "routing_key").content}' properties_key=${prim(bindings.first { prim(it, "source").content == "hdr" }, "properties_key").content}")
    }

    // ---- E5: a super stream -----------------------------------------------------------------------------------------------------

    private fun ctl(vararg args: String): String {
        val r = rabbit.execInContainer(*args)
        check(r.exitCode == 0) { "${args.toList()} failed: ${r.stderr} ${r.stdout}" }
        return r.stdout
    }

    @Test
    fun `E5 a super stream, its exchange, partitions and bindings, and a replay of the reference export on an empty vhost`() {
        val source = newVhost("e5a"); val target = newVhost("e5b")
        ctl("rabbitmq-streams", "add_super_stream", "orders", "--vhost", source, "--partitions", "3")
        awaitUntil("partition streams listed") { queueItems(source).size == 3 }
        val exchanges = api.get("/api/exchanges/${enc(source)}").arr.map { it.jsonObject }.filter { prim(it, "name").content == "orders" }
        val marker = exchanges.single()
        save("e5-super-stream-exchange.json", marker.toString())
        fact("E5", "super stream exchange: type=${prim(marker, "type").content} durable=${prim(marker, "durable").content} internal=${prim(marker, "internal").content} arguments=${marker["arguments"]}")
        val queues = queueItems(source)
        save("e5-super-stream-partitions.json", api.get("/api/queues/${enc(source)}?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}").body)
        fact("E5", "partition streams: ${queues.mapValues { prim(it.value, "type").content to it.value["arguments"] }}")
        val bindings = api.get("/api/bindings/${enc(source)}").arr.map { it.jsonObject }.filter { prim(it, "source").content == "orders" }
        save("e5-super-stream-bindings.json", JsonArray(bindings).toString())
        fact("E5", "bindings: ${bindings.map { Triple(prim(it, "destination").content, prim(it, "routing_key").content, it["arguments"]) }}")
        assertEquals(true, prim(marker["arguments"]!!.jsonObject, "x-super-stream").booleanOrNull)
        assertEquals(3, bindings.size)
        assertTrue(bindings.all { !prim(it["arguments"]!!.jsonObject, "x-stream-partition-order").isString })

        // replay with the reference conversion (what the production exporter must be able to write) on an empty vhost
        val actions = referenceActions(source, target)
        fact("E5", "reference export of the super stream: ${actions.map { it.actionName + ":" + it.description }}")
        val executor = openHeld(target).migrationExecutor!!
        for (action in actions) {
            val result = runBlocking { executor.execute(action) }
            assertEquals(ActionResult.Success, result, "replaying ${action.description}")
        }
        val replayedExchange = api.get("/api/exchanges/${enc(target)}/orders").obj
        assertEquals(true, prim(replayedExchange["arguments"]!!.jsonObject, "x-super-stream").booleanOrNull)
        val replayedBindings = api.get("/api/bindings/${enc(target)}").arr.map { it.jsonObject }.filter { prim(it, "source").content == "orders" }
        assertEquals(snapshotBindings(bindings), snapshotBindings(replayedBindings), "the replayed super stream has the same bindings")
        assertEquals(queues.keys, queueItems(target).keys)
        fact("E5", "replay on an empty vhost gave the same exchange, partition streams and bindings (with x-stream-partition-order)")
    }

    // ---- E6: policies ------------------------------------------------------------------------------------------------------------

    @Test
    fun `E6 policies, operator policies and who may read them`() {
        val vhost = newVhost("e6"); val v = enc(vhost)
        val body = """{"pattern":"^e6-","apply-to":"queues","priority":7,"definition":{"max-length":5000,"message-ttl":86400000,"dead-letter-exchange":"dlx","overflow":"reject-publish","delivery-limit":10}}"""
        assertTrue(api.put("/api/policies/$v/e6-limits", body).ok)
        assertTrue(api.put("/api/policies/$v/e6-streams", """{"pattern":"^e6-s","apply-to":"streams","priority":0,"definition":{"max-age":"1D","stream-max-segment-size-bytes":50000000}}""").ok)
        assertTrue(api.put("/api/policies/$v/e6-all", """{"pattern":"^e6-x","apply-to":"all","priority":-1,"definition":{"queue-leader-locator":"balanced"}}""").ok)
        val operator = api.put("/api/operator-policies/$v/e6-op", """{"pattern":"^e6-","apply-to":"queues","priority":3,"definition":{"max-length":100000}}""")
        fact("E6", "PUT operator policy: ${operator.status} ${operator.body.take(150)}")

        val list = api.get("/api/policies/$v")
        save("e6-policies.json", list.body)
        val policies = list.arr.map { it.jsonObject }.associateBy { prim(it, "name").content }
        fact("E6", "policies listed by GET /api/policies/{vhost}: ${policies.keys}; keys of one: ${policies["e6-limits"]!!.keys.sorted()}")
        assertEquals(setOf("e6-limits", "e6-streams", "e6-all"), policies.keys, "an operator policy is NOT in /api/policies")
        val limits = policies["e6-limits"]!!
        assertEquals("^e6-", prim(limits, "pattern").content)
        assertEquals("queues", prim(limits, "apply-to").content)
        assertEquals("7", prim(limits, "priority").content)
        assertFalse(prim(limits, "priority").isString)
        val def = limits["definition"]!!.jsonObject
        assertFalse(prim(def, "max-length").isString); assertFalse(prim(def, "message-ttl").isString); assertFalse(prim(def, "delivery-limit").isString)
        assertTrue(prim(def, "dead-letter-exchange").isString); assertTrue(prim(def, "overflow").isString)
        assertEquals("-1", prim(policies["e6-all"]!!, "priority").content)
        assertTrue(prim(policies["e6-streams"]!!["definition"]!!.jsonObject, "max-age").isString)
        fact("E6", "definition types: max-length/message-ttl/delivery-limit numbers, dead-letter-exchange/overflow/max-age text; negative priority -1 kept; stream-max-segment-size-bytes number")

        if (operator.ok) {
            val ops = api.get("/api/operator-policies/$v")
            save("e6-operator-policies.json", ops.body)
            fact("E6", "GET /api/operator-policies/{vhost}: ${ops.status}; ${ops.arr.map { prim(it.jsonObject, "name").content to it.jsonObject.keys.sorted() }}")
            val parameters = api.get("/api/parameters/operator_policy/$v")
            fact("E6", "GET /api/parameters/operator_policy/{vhost}: ${parameters.status} ${parameters.body.take(200)}")
        }

        // who may read them
        val perms = """{"configure":".*","write":".*","read":".*"}"""
        for ((user, tags) in listOf("m914-mgmt-$run" to "management", "m914-mon-$run" to "monitoring", "m914-pol-$run" to "management,policymaker", "m914-pol-only-$run" to "policymaker")) {
            assertTrue(api.put("/api/users/$user", """{"password":"pw-$user","tags":"$tags"}""").ok); users += user
            assertTrue(api.put("/api/permissions/$v/$user", perms).ok)
            val p = api.get("/api/policies/$v", user, "pw-$user")
            val o = api.get("/api/operator-policies/$v", user, "pw-$user")
            fact("E6", "user tags=[$tags]: GET /api/policies/{vhost} -> ${p.status}${if (p.ok) " (${p.arr.size} policies)" else " " + p.reason()}; GET /api/operator-policies/{vhost} -> ${o.status}${if (o.ok) " (${o.arr.size})" else " " + o.reason()}")
            save("e6-user-${tags.replace(',', '+')}.json", """{"policies":${p.status},"operatorPolicies":${o.status}}""")
        }
        // a user with the management tag and NO permission on the vhost
        val outsider = "m914-out-$run"
        assertTrue(api.put("/api/users/$outsider", """{"password":"pw-out","tags":"management"}""").ok); users += outsider
        val outsiderReply = api.get("/api/policies/$v", outsider, "pw-out")
        fact("E6", "management user with no permission on the vhost: GET /api/policies/{vhost} -> ${outsiderReply.status} ${outsiderReply.reason()}")
    }

    // ---- E7: the round trip (the live gate) --------------------------------------------------------------------------------------

    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "guest"
        override fun delete(profileName: String) = Unit
    }

    private fun openHeld(vhost: String, broker: RabbitMQContainer = rabbit): HeldConnection {
        val profile = ConnectionProfile(
            name = "m9-14", host = broker.host, virtualHost = vhost, username = "guest",
            streamPort = broker.getMappedPort(5552), managementPort = broker.getMappedPort(15672), tls = false,
            topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
        )
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile) }
        check(opened is OpenResult.Opened) { "could not open the held connection: $opened" }
        toClose += opened.connection
        return opened.connection
    }

    /** Seeds the rich topology of the brief into [vhost] of [broker] and returns what to close (the exclusive queue lives with its AMQP connection). */
    private fun seedRich(broker: RabbitMQContainer, brokerApi: MgmtApi, vhost: String): AutoCloseable {
        val v = enc(vhost)
        fun put(path: String, body: String) = assertTrue(brokerApi.put(path, body).ok, "$path")
        put("/api/exchanges/$v/dlx", """{"type":"fanout","durable":true}""")
        put("/api/exchanges/$v/events", """{"type":"topic","durable":true,"arguments":{"alternate-exchange":"dlx"}}""")
        put("/api/exchanges/$v/hdr", """{"type":"headers","durable":true}""")
        put("/api/exchanges/$v/internal-x", """{"type":"fanout","durable":true,"internal":true}""")
        put("/api/exchanges/$v/auto-x", """{"type":"fanout","durable":true,"auto_delete":true}""")
        put("/api/queues/$v/q-classic", """{"durable":true,"arguments":{"x-queue-type":"classic","x-message-ttl":60000,"x-max-length":1000,"x-dead-letter-exchange":"dlx","x-overflow":"reject-publish"}}""")
        put("/api/queues/$v/q-quorum", """{"durable":true,"arguments":{"x-queue-type":"quorum","x-delivery-limit":5,"x-dead-letter-exchange":"dlx","x-max-length":100}}""")
        put("/api/queues/$v/q-plain", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""")
        // M9-14c (brief table, E7): a stream with retention AND a non-default leader locator, so "B equals A" includes a stream's arguments.
        put("/api/queues/$v/q-stream", """{"durable":true,"arguments":{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":1000000000,"x-queue-leader-locator":"client-local"}}""")
        ctlOn(broker, "rabbitmq-streams", "add_super_stream", "orders-ss", "--vhost", vhost, "--partitions", "3")
        assertTrue(brokerApi.post("/api/bindings/$v/e/events/q/q-quorum", """{"routing_key":"order.#"}""").ok)
        assertTrue(brokerApi.post("/api/bindings/$v/e/hdr/q/q-classic", """{"routing_key":"","arguments":{"x-match":"all","region":"eu","level":3,"urgent":true}}""").ok)
        assertTrue(brokerApi.post("/api/bindings/$v/e/events/e/dlx", """{"routing_key":"dead"}""").ok)
        assertTrue(brokerApi.post("/api/bindings/$v/e/amq.topic/q/q-plain", """{"routing_key":"sensors.#"}""").ok)
        assertTrue(brokerApi.post("/api/bindings/$v/e/internal-x/q/q-plain", """{"routing_key":""}""").ok)
        put("/api/policies/$v/pol-limits", """{"pattern":"^q-","apply-to":"queues","priority":7,"definition":{"max-length":5000,"message-ttl":86400000,"overflow":"reject-publish"}}""")
        put("/api/policies/$v/pol-stream", """{"pattern":"^q-stream$","apply-to":"streams","priority":0,"definition":{"max-age":"1D"}}""")
        put("/api/operator-policies/$v/op-limits", """{"pattern":"^q-","apply-to":"queues","priority":3,"definition":{"max-length":100000}}""")
        // the things an export must leave out: an exclusive queue and a server-named one (an open AMQP connection), in this vhost
        val factory = com.rabbitmq.client.ConnectionFactory().apply {
            host = broker.host; port = broker.amqpPort; username = "guest"; password = "guest"; virtualHost = vhost; isAutomaticRecoveryEnabled = false
        }
        val connection = factory.newConnection("m9-14-seed")
        val channel = connection.createChannel()
        channel.queueDeclare("q-exclusive", false, true, false, null)
        channel.queueDeclare("", false, true, true, null)
        awaitUntil("seeded queues listed") { queuesOf(brokerApi, vhost).size >= 9 }
        return AutoCloseable { runCatching { connection.close() } }
    }

    private fun ctlOn(broker: RabbitMQContainer, vararg args: String) {
        val r = broker.execInContainer(*args)
        check(r.exitCode == 0) { "${args.toList()} failed: ${r.stderr} ${r.stdout}" }
    }

    private fun queuesOf(a: MgmtApi, vhost: String): List<JsonObject> =
        a.get("/api/queues/${enc(vhost)}?page=1&page_size=500&columns=${enc(QUEUE_COLUMNS)}").obj["items"]!!.jsonArray.map { it.jsonObject }

    // What the export must NOT carry over; the rule is measured in E3 and fixed in the brief.
    private fun leftOut(name: String, item: JsonObject) =
        prim(item, "exclusive").booleanOrNull == true || name.startsWith("amq.gen-") || name.startsWith("mqtt-subscription-")

    private fun arguments(o: JsonObject): JsonObject = (o["arguments"] as? JsonObject) ?: JsonObject(emptyMap())

    /** The broker's own view of a vhost, reduced to what the export promises to keep. M9-14c: a stream's arguments (retention, leader locator) are part of "the same". */
    private data class Snapshot(
        val queues: Map<String, JsonObject>, val exchanges: Map<String, JsonObject>, val bindings: Set<JsonObject>,
        val policies: Map<String, JsonObject>, val operatorPolicies: Map<String, JsonObject>,
    )

    private fun snapshot(a: MgmtApi, vhost: String): Snapshot {
        val v = enc(vhost)
        val queues = queuesOf(a, vhost).filterNot { leftOut(prim(it, "name").content, it) }.associate { q ->
            val type = prim(q, "type").content
            prim(q, "name").content to buildJsonObject {
                put("type", type); put("durable", prim(q, "durable").booleanOrNull!!); put("auto_delete", prim(q, "auto_delete").booleanOrNull!!)
                put("arguments", JsonObject(arguments(q) - "x-queue-type"))
            }
        }
        val exchanges = a.get("/api/exchanges/$v").arr.map { it.jsonObject }.filter { prim(it, "name").content.let { n -> n.isNotEmpty() && !n.startsWith("amq.") } }.associate { e ->
            prim(e, "name").content to buildJsonObject {
                put("type", prim(e, "type").content); put("durable", prim(e, "durable").booleanOrNull!!); put("auto_delete", prim(e, "auto_delete").booleanOrNull!!)
                put("internal", prim(e, "internal").booleanOrNull!!); put("arguments", arguments(e))
            }
        }
        val excluded = queuesOf(a, vhost).map { prim(it, "name").content }.filter { n -> queuesOf(a, vhost).any { prim(it, "name").content == n && leftOut(n, it) } }.toSet()
        val bindings = a.get("/api/bindings/$v").arr.map { it.jsonObject }
            .filter { prim(it, "source").content.isNotEmpty() && prim(it, "destination").content !in excluded }.let { snapshotBindings(it) }
        fun policies(path: String): Map<String, JsonObject> = a.get(path).let { r ->
            if (!r.ok) emptyMap() else r.arr.map { it.jsonObject }.associate { p ->
                prim(p, "name").content to buildJsonObject {
                    put("pattern", prim(p, "pattern").content); put("apply-to", prim(p, "apply-to").content)
                    put("priority", p["priority"]!!); put("definition", p["definition"]!!)
                }
            }
        }
        return Snapshot(queues, exchanges, bindings, policies("/api/policies/$v"), policies("/api/operator-policies/$v"))
    }

    private fun snapshotBindings(items: List<JsonObject>): Set<JsonObject> = items.map { b ->
        buildJsonObject {
            put("source", prim(b, "source").content); put("destination", prim(b, "destination").content)
            put("destination_type", prim(b, "destination_type").content); put("routing_key", prim(b, "routing_key").content)
            put("arguments", arguments(b))
        }
    }.toSet()

    private fun assertSameSnapshot(expected: Snapshot, actual: Snapshot, what: String) {
        assertEquals(expected.queues, actual.queues, "$what: queues (type, durable, arguments)")
        assertEquals(expected.exchanges, actual.exchanges, "$what: exchanges (flags, arguments)")
        assertEquals(expected.bindings, actual.bindings, "$what: bindings (arguments)")
        assertEquals(expected.policies, actual.policies, "$what: policies (name, pattern, apply-to, definition, priority)")
        assertEquals(expected.operatorPolicies, actual.operatorPolicies, "$what: operator policies")
    }

    /** The reference exporter of this spike: reads raw management answers and writes the actions the production export must be able to write. */
    private fun typed(o: JsonObject): Map<String, ArgumentValue> = o.mapValues { ArgumentValueJson.parse(it.value)!! }

    private fun referenceActions(from: String, to: String, order: List<String> = listOf("exchanges", "queues", "bindings", "policies")): List<MigrationAction> {
        val v = enc(from)
        val queueRows = queuesOf(api, from)
        val excluded = queueRows.filter { leftOut(prim(it, "name").content, it) }.map { prim(it, "name").content }.toSet()
        val queues = queueRows.filterNot { prim(it, "name").content in excluded }.map { q ->
            val name = prim(q, "name").content; val type = prim(q, "type").content
            if (type == "stream") MigrationAction.DeclareStream(name, to, typed(JsonObject(arguments(q) - "x-queue-type")))
            else MigrationAction.DeclareQueue(name, type, to, prim(q, "durable").booleanOrNull!!, typed(JsonObject(arguments(q) - "x-queue-type")), prim(q, "auto_delete").booleanOrNull!!)
        }
        val exchanges = api.get("/api/exchanges/$v").arr.map { it.jsonObject }.filter { prim(it, "name").content.let { n -> n.isNotEmpty() && !n.startsWith("amq.") } }.map { e ->
            MigrationAction.DeclareExchange(prim(e, "name").content, prim(e, "type").content, to, prim(e, "durable").booleanOrNull!!, prim(e, "auto_delete").booleanOrNull!!, prim(e, "internal").booleanOrNull!!, typed(arguments(e)))
        }
        val bindings = api.get("/api/bindings/$v").arr.map { it.jsonObject }
            .filter { prim(it, "source").content.isNotEmpty() && prim(it, "destination").content !in excluded }.map { b ->
                MigrationAction.DeclareBinding(
                    prim(b, "source").content, prim(b, "destination").content, prim(b, "routing_key").content, to,
                    if (prim(b, "destination_type").content == "exchange") BindingDestinationType.EXCHANGE else BindingDestinationType.QUEUE, typed(arguments(b)),
                )
            }
        val policies = api.get("/api/policies/$v").arr.map { it.jsonObject }.map { p ->
            MigrationAction.DeclarePolicy(prim(p, "name").content, prim(p, "pattern").content, typed(p["definition"]!!.jsonObject), to, prim(p, "apply-to").content, prim(p, "priority").content.toInt())
        }
        val operatorPolicies = api.get("/api/operator-policies/$v").let { r ->
            if (!r.ok) emptyList() else r.arr.map { it.jsonObject }.map { p ->
                MigrationAction.DeclareOperatorPolicy(prim(p, "name").content, prim(p, "pattern").content, typed(p["definition"]!!.jsonObject), to, prim(p, "apply-to").content, prim(p, "priority").content.toInt())
            }
        }
        val groups = mapOf("exchanges" to exchanges, "queues" to queues, "bindings" to bindings, "policies" to policies + operatorPolicies)
        return order.flatMap { groups.getValue(it) }
    }

    private fun applyTo(held: HeldConnection, vhost: String, actions: List<MigrationAction>): List<ActionResult> =
        actions.map { runBlocking { held.migrationExecutor!!.execute(it) } }

    @Test
    fun `E7a the order of the file does not decide the result, only a binding needs its two ends first`() {
        val source = newVhost("e7a-src")
        toClose += seedRich(rabbit, api, source)
        val expected = snapshot(api, source)
        fact("E7a", "source vhost holds ${expected.queues.size} exported queues and streams, ${expected.exchanges.size} exchanges, ${expected.bindings.size} bindings, ${expected.policies.size} policies, ${expected.operatorPolicies.size} operator policies")
        val orders = mapOf(
            "exchanges, queues, bindings, policies" to listOf("exchanges", "queues", "bindings", "policies"),
            "queues, exchanges, bindings, policies (today's order, policies appended)" to listOf("queues", "exchanges", "bindings", "policies"),
            "policies first, then queues, exchanges, bindings" to listOf("policies", "queues", "exchanges", "bindings"),
        )
        for ((label, order) in orders) {
            val target = newVhost("e7a-" + orders.keys.indexOf(label))
            val held = openHeld(target)
            val results = applyTo(held, target, referenceActions(source, target, order))
            val failures = results.filterIsInstance<ActionResult.Failed>()
            fact("E7a", "order [$label]: ${results.size} actions, ${failures.size} failed ${failures.map { it.reason }}")
            assertTrue(failures.isEmpty(), "order [$label] failed: $failures")
            awaitUntil("target queues listed") { queuesOf(api, target).size == expected.queues.size }
            val actual = snapshot(api, target)
            assertSameSnapshot(expected, actual, "order [$label]")
        }
        // a binding before its exchange is refused: the one ordering rule
        val target = newVhost("e7a-bad")
        val held = openHeld(target)
        val results = applyTo(held, target, referenceActions(source, target, listOf("bindings", "exchanges", "queues", "policies")))
        fact("E7a", "bindings first: ${results.count { it is ActionResult.Failed }} of ${results.size} actions failed")
        assertTrue(results.any { it is ActionResult.Failed }, "bindings before their ends must fail")
    }

    @Test
    fun `E7 an exported topology applied to an empty broker gives the same topology, and exporting it again gives the same file`() {
        val vhost = newVhost("e7")
        toClose += seedRich(rabbit, api, vhost)
        val expected = snapshot(api, vhost)

        // A: the real catalog and the real view model
        val heldA = openHeld(vhost)
        val exported = export(heldA, vhost)
        fact("E7", "the export of A has ${exported.up.size} actions: ${exported.up.groupingBy { it.actionName }.eachCount()}")

        // the file must keep its promised shape: bindings after their ends, policies last
        val names = exported.up.map { it.actionName }
        val lastBindingEnd = names.indexOfLast { it in setOf("declareQueue", "declareExchange", "declareStream") }
        val firstBinding = names.indexOfFirst { it == "declareBinding" }
        val firstPolicy = names.indexOfFirst { it == "declarePolicy" || it == "declareOperatorPolicy" }
        assertTrue(firstBinding > lastBindingEnd, "every binding comes after every queue, exchange and stream: $names")
        assertTrue(firstPolicy > names.indexOfLast { it == "declareBinding" } && firstPolicy >= 0, "policies come last: $names")
        // what must never be in it
        assertTrue(exported.up.none { it is MigrationAction.DeclareQueue && leftOutName(it.name) }, "an exclusive, server-named or MQTT queue was exported: ${exported.up}")
        assertTrue(exported.up.none { it is MigrationAction.DeclareBinding && (it.queue == "q-exclusive" || it.queue.startsWith("amq.gen-")) })

        // B: a second, empty broker
        brokerWithPlugins(IMAGE, "rabbitmq_stream", "rabbitmq_stream_management").apply { addExposedPorts(5552) }.use { b ->
            b.start()
            val apiB = MgmtApi(b)
            assertTrue(apiB.put("/api/vhosts/${enc(vhost)}", "{}").ok)
            assertTrue(apiB.put("/api/permissions/${enc(vhost)}/guest", """{"configure":".*","write":".*","read":".*"}""").ok)
            val heldB = openHeld(vhost, b)
            val result = runBlocking {
                MigrationRunner(InMemoryMigrationHistoryRepository(), heldB.migrationExecutor!!)
                    .applyPending("b", listOf(exported), true, TopologyControlLevel.FULL_CONTROL, vhost = vhost)
            }
            assertEquals(MigrationApplyResult.Success(listOf(exported.version)), result, "applying the exported file to the empty broker B")
            awaitUntil("B queues listed") { queuesOf(apiB, vhost).size == expected.queues.size }
            assertSameSnapshot(expected, snapshot(apiB, vhost), "B after applying the export of A")

            // the second export: the same actions as the first
            val again = export(heldB, vhost)
            assertEquals(exported.up.map { it.toString() }.sorted(), again.up.map { it.toString() }.sorted(), "the export of B holds the same actions as the export of A")
            assertEquals(exported.up.map { it.actionName }, again.up.map { it.actionName }, "and in the same order of kinds")
        }
    }

    private fun leftOutName(name: String) = name == "q-exclusive" || name.startsWith("amq.gen-") || name.startsWith("mqtt-subscription-")

    private fun export(held: HeldConnection, vhost: String): MigrationFile {
        val vm = MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), held.migrationExecutor!!),
            topologyCatalog = held.topology, vhost = vhost,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val file = runBlocking { vm.exportCurrentTopology() }
        return file ?: error("the export returned no file: ${vm.state.value.errorMessage}")
    }

    @Suppress("unused")
    private fun parsed(raw: String): MigrationFile = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration

    @Suppress("unused")
    private val unusedJson: JsonElement = JsonNull
}
