package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.ByteCapacity
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * M2-02 spike (docs/tasks/M2-02-management-api-spike.md): what do the RabbitMQ 4 management API (HTTPS 15671) and
 * the stream protocol (TLS 5551) really return for streams, publishers, consumers, offsets, super streams, and
 * what do non-admin users see? Every finding is asserted (status, field name, JSON type, value) so it cannot drift.
 * The recorded answers live in the brief's Status; real bodies are saved under
 * src/test/resources/fixtures/m2-02/ (set env M2_FIXTURES_WRITE=1 to rewrite them) for M2-03 fakes and parsers.
 *
 * Test-only code: the trust-all SSLContext and the throwaway users never leave this class.
 * Determinism: unique names (UUID), everything created is removed in @AfterEach / @AfterAll, no fixed sleeps
 * (bounded [awaitUntil] polling on the value, failing with the last body).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class StreamManagementApiSpikeTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val ALLOWED = "spike-allowed-"
        private const val PRIVATE = "spike-private-"
        private const val OTHER_VHOST = "spike-other"
        private val pw = mapOf("spike-mon" to "pw-mon", "spike-mgmt" to "pw-mgmt", "spike-plain" to "pw-plain")

        private fun newContainer(vararg plugins: String): RabbitMQContainer =
            brokerWithPlugins("rabbitmq:4-management", *plugins)
                .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
                .apply { addExposedPorts(STREAM_TLS_PORT) }

        private val rabbit: RabbitMQContainer = newContainer("rabbitmq_stream", "rabbitmq_stream_management")

        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        private val http: HttpClient by lazy {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            HttpClient.newBuilder().sslContext(ctx)
                .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" }).build()
        }

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
            ctl("add_vhost", OTHER_VHOST)
            ctl("set_permissions", "-p", OTHER_VHOST, rabbit.adminUsername, ".*", ".*", ".*")
            for ((u, p) in pw) {
                ctl("add_user", u, p)
                ctl("set_permissions", "-p", "/", u, "^$ALLOWED.*", "^$ALLOWED.*", "^$ALLOWED.*")
            }
            ctl("set_user_tags", "spike-mon", "monitoring")
            ctl("set_user_tags", "spike-mgmt", "management")
            ctl("set_user_tags", "spike-plain")
        }

        @JvmStatic @AfterAll fun stop() {
            runCatching { for (u in pw.keys) ctl("delete_user", u) }
            runCatching { ctl("delete_vhost", OTHER_VHOST) }
            rabbit.stop()
        }

        private fun ctl(vararg args: String) {
            val r = rabbit.execInContainer("rabbitmqctl", *args)
            check(r.exitCode == 0) { "rabbitmqctl ${args.toList()} failed: ${r.stderr}" }
        }
    }

    // ---------- plumbing ----------

    private class Resp(val status: Int, val body: String) {
        val json: JsonElement by lazy { Json.parseToJsonElement(body) }
        val obj: JsonObject get() = json.jsonObject
        val arr: JsonArray get() = json.jsonArray
        override fun toString() = "HTTP $status $body"
    }

    private inner class Mgmt(private val base: String, private val user: String, private val pass: String) {
        fun send(method: String, path: String, body: String? = null): Resp {
            val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            val b = HttpRequest.newBuilder(URI("$base$path")).header("Authorization", "Basic $auth")
            if (body != null) b.header("Content-Type", "application/json")
            val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
            val r = http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
            return Resp(r.statusCode(), r.body())
        }
        fun get(path: String) = send("GET", path)
    }

    private fun mgmt(user: String = rabbit.adminUsername, pass: String = rabbit.adminPassword) =
        Mgmt("https://${rabbit.host}:${rabbit.httpsPort}", user, pass)

    private val admin by lazy { mgmt() }

    private val queuesToDelete = mutableListOf<Pair<String, String>>() // vhost, name
    private val exchangesToDelete = mutableListOf<String>()
    private val superStreamsToDelete = mutableListOf<String>()
    private val environments = mutableListOf<Environment>()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach fun cleanUp() {
        closeables.reversed().forEach { runCatching { it.close() } }
        environments.forEach { runCatching { it.close() } }
        for (s in superStreamsToDelete) runCatching { rabbit.execInContainer("rabbitmq-streams", "delete_super_stream", s) }
        for (e in exchangesToDelete) runCatching { admin.send("DELETE", "/api/exchanges/%2F/$e") }
        for ((v, q) in queuesToDelete) runCatching { admin.send("DELETE", "/api/queues/$v/$q") }
        closeables.clear(); environments.clear(); superStreamsToDelete.clear(); exchangesToDelete.clear(); queuesToDelete.clear()
    }

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun declareStream(name: String, vhost: String = "%2F", args: String = ""): Resp {
        val r = admin.send("PUT", "/api/queues/$vhost/$name",
            """{"durable":true,"arguments":{"x-queue-type":"stream"$args}}""")
        assertEquals(201, r.status, "declare stream $name: $r")
        queuesToDelete += vhost to name
        return r
    }

    private fun declareQueue(name: String, type: String) {
        val r = admin.send("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"$type"}}""")
        assertEquals(201, r.status, "declare $type $name: $r")
        queuesToDelete += "%2F" to name
    }

    /** Management publish to the default exchange, routing key = queue name; one message per call = one chunk. */
    private fun publishViaManagement(stream: String, count: Int) {
        for (i in 1..count) {
            val r = admin.send("POST", "/api/exchanges/%2F/amq.default/publish",
                """{"properties":{},"routing_key":"$stream","payload":"m$i","payload_encoding":"string"}""")
            assertEquals(200, r.status, "publish $i: $r")
            assertEquals("true", r.obj["routed"]!!.jsonPrimitive.content, "publish $i routed: $r")
        }
    }

    private fun env(user: String = rabbit.adminUsername, pass: String = rabbit.adminPassword): Environment {
        val e = Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(STREAM_TLS_PORT))
            .username(user).password(pass)
            .addressResolver { Address(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) }
            .tls().sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
            .environmentBuilder().build()
        environments += e
        return e
    }

    /** Publishes [count] messages of [size] bytes and waits for every broker confirm. Returns the producer (kept open). */
    private fun publishViaClient(env: Environment, stream: String, count: Int, ref: String? = null, size: Int = 10) {
        val latch = CountDownLatch(count)
        val b = env.producerBuilder().stream(stream)
        if (ref != null) b.name(ref)
        val p = b.build()
        closeables += p
        val payload = ByteArray(size) { 'x'.code.toByte() }
        repeat(count) { p.send(p.messageBuilder().addData(payload).build()) { latch.countDown() } }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "confirms for $count messages on $stream not received in time")
    }

    /** Opens a consumer from the first offset; returns once [expected] messages were delivered. */
    private fun consumeViaClient(env: Environment, stream: String, expected: Int, ref: String, storeAt: Long? = null) {
        val got = CountDownLatch(expected)
        val c = env.consumerBuilder().stream(stream).name(ref).offset(OffsetSpecification.first())
            .manualTrackingStrategy().builder()
            .messageHandler { ctx, _ -> if (storeAt != null && ctx.offset() == storeAt) ctx.storeOffset(); got.countDown() }
            .build()
        closeables += c
        assertTrue(got.await(30, TimeUnit.SECONDS), "consumer $ref did not receive $expected messages on $stream")
    }

    /** Bounded poll: repeats [fetch] until [done], at most [timeoutMs]; fails with the last value. No fixed sleep. */
    private fun <T> awaitUntil(what: String, timeoutMs: Long = 40_000, intervalMs: Long = 250, fetch: () -> T, done: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last = fetch()
        while (!done(last)) {
            check(System.nanoTime() < deadline) { "timed out after ${timeoutMs}ms waiting for: $what; last value: $last" }
            TimeUnit.MILLISECONDS.sleep(intervalMs) // poll interval inside the bounded helper only
            last = fetch()
        }
        return last
    }

    private fun queue(name: String, vhost: String = "%2F", who: Mgmt = admin) = who.get("/api/queues/$vhost/$name")

    private fun awaitMessages(name: String, expected: Long): Resp =
        awaitUntil("messages == $expected on $name", fetch = { queue(name) }, done = { it.status == 200 && it.obj["messages"]?.jsonPrimitive?.long == expected })

    // JSON type assertions -------------------------------------------------

    private enum class T { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, NULL }

    private fun JsonElement.type(): T = when (this) {
        is JsonNull -> T.NULL
        is JsonObject -> T.OBJECT
        is JsonArray -> T.ARRAY
        is JsonPrimitive -> if (isString) T.STRING else if (booleanOrNull != null) T.BOOLEAN else T.NUMBER
    }

    private fun assertShape(o: JsonObject, expected: Map<String, T>, ctx: String) {
        for ((k, t) in expected) {
            val v = o[k]
            assertNotNull(v, "$ctx: field '$k' missing; keys=${o.keys.sorted()}")
            assertEquals(t, v!!.type(), "$ctx: field '$k' type (value $v)")
        }
    }

    private fun assertAbsent(o: JsonObject, keys: List<String>, ctx: String) {
        for (k in keys) assertFalse(o.containsKey(k), "$ctx: field '$k' unexpectedly present: ${o[k]}")
    }

    // fixtures --------------------------------------------------------------

    private fun fixture(name: String, r: Resp, scrub: List<String> = emptyList()) {
        if (System.getenv("M2_FIXTURES_WRITE") != "1") return
        var text = r.body
        for (s in scrub) text = text.replace(s, "<name>")
        text = text.replace(Regex("rabbit@[0-9a-f]+"), "rabbit@node1")
        val pretty = Json { prettyPrint = true }
        val content = if (r.status in 200..299) {
            pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
        } else {
            val bodyEl: JsonElement = runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonPrimitive(text))
            pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf("status" to JsonPrimitive(r.status), "body" to bodyEl)))
        }
        val dir = Path.of("src/test/resources/fixtures/m2-02")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), content + "\n")
    }

    // ================= Question 1: stream list =================

    @Test
    fun `Q1 stream queue object - field names and JSON types for a stream with 1000 messages`() {
        val name = unique("spike-q1-")
        declareStream(name, args = ""","x-max-age":"7D","x-max-length-bytes":1000000000,"x-stream-max-segment-size-bytes":50000000""")
        publishViaManagement(name, 1000)
        val t0 = System.nanoTime()
        // M2-14b: messages and message_stats.publish are sampled separately and do not update together; wait for both.
        val r = awaitUntil(
            "messages == 1000 and message_stats.publish == 1000 on $name",
            fetch = { queue(name) },
            done = {
                it.status == 200 && it.obj["messages"]?.jsonPrimitive?.long == 1000L &&
                    it.obj["message_stats"]?.jsonObject?.get("publish")?.jsonPrimitive?.long == 1000L
            },
        )
        println("M2-02 stats lag after the last publish until messages==1000 and publish==1000: ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)} ms")
        fixture("queue-stream-with-data.json", r, listOf(name))

        val o = r.obj
        assertShape(o, mapOf(
            "name" to T.STRING, "vhost" to T.STRING, "type" to T.STRING, "state" to T.STRING,
            "messages" to T.NUMBER, "messages_ready" to T.NUMBER, "messages_unacknowledged" to T.NUMBER,
            "consumers" to T.NUMBER, "memory" to T.NUMBER, "segments" to T.NUMBER, "first_timestamp" to T.NUMBER,
            "durable" to T.BOOLEAN, "auto_delete" to T.BOOLEAN, "exclusive" to T.BOOLEAN,
            "arguments" to T.OBJECT, "effective_policy_definition" to T.OBJECT,
            "policy" to T.NULL, "operator_policy" to T.NULL,
            "node" to T.STRING, "leader" to T.STRING, "members" to T.ARRAY, "online" to T.ARRAY, "readers" to T.OBJECT,
            "consumer_details" to T.ARRAY, "message_stats" to T.OBJECT,
        ), "stream queue object")
        assertEquals(name, o["name"]!!.jsonPrimitive.content)
        assertEquals("/", o["vhost"]!!.jsonPrimitive.content)
        assertEquals("stream", o["type"]!!.jsonPrimitive.content)
        assertEquals("running", o["state"]!!.jsonPrimitive.content)
        assertEquals(1000, o["messages"]!!.jsonPrimitive.long)
        assertEquals(1000, o["messages_ready"]!!.jsonPrimitive.long, "for a stream messages_ready equals messages")
        assertEquals(0, o["consumers"]!!.jsonPrimitive.long)
        assertEquals(1000, o["message_stats"]!!.jsonObject["publish"]!!.jsonPrimitive.long)
        val args = o["arguments"]!!.jsonObject
        assertEquals("7D", args["x-max-age"]!!.jsonPrimitive.content, "x-max-age is a STRING")
        assertEquals(T.NUMBER, args["x-max-length-bytes"]!!.type())
        assertEquals(1_000_000_000L, args["x-max-length-bytes"]!!.jsonPrimitive.long)
        assertEquals(50_000_000L, args["x-stream-max-segment-size-bytes"]!!.jsonPrimitive.long)
        assertEquals("stream", args["x-queue-type"]!!.jsonPrimitive.content)
        assertEquals(1, o["members"]!!.jsonArray.size)
        assertEquals(o["leader"], o["node"], "single node: leader == node")
        // Absent for streams: no size in bytes, no publisher count, no offsets, no last timestamp.
        assertAbsent(o, listOf("message_bytes", "message_bytes_ready", "message_bytes_persistent", "publishers",
            "first_offset", "last_offset", "committed_offset", "committed_chunk_id", "last_timestamp", "consumer_utilisation"), "stream queue object")
    }

    @Test
    fun `Q1 list endpoints carry the same stream fields - plain array and paginated object`() {
        val name = unique("spike-q1l-")
        declareStream(name)
        declareQueue(unique("spike-q1l-quorum-"), "quorum")
        publishViaManagement(name, 3)
        awaitMessages(name, 3)

        val all = admin.get("/api/queues")
        assertEquals(200, all.status)
        assertEquals(T.ARRAY, all.json.type(), "without page parameters: a bare JSON array")
        val inAll = all.arr.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }
        assertEquals("stream", inAll["type"]!!.jsonPrimitive.content)
        assertEquals(3, inAll["messages"]!!.jsonPrimitive.long)

        val perVhost = admin.get("/api/queues/%2F")
        assertEquals(200, perVhost.status)
        assertTrue(perVhost.arr.any { it.jsonObject["name"]!!.jsonPrimitive.content == name }, "/api/queues/{vhost} lists it")

        val page = admin.get("/api/queues?page=1&page_size=100&name=${name}")
        assertEquals(200, page.status)
        assertEquals(T.OBJECT, page.json.type(), "with page: an object {items, page, ...}")
        fixture("queues-page-one-stream.json", page, listOf(name))
        val item = page.obj["items"]!!.jsonArray.single().jsonObject
        assertShape(item, mapOf("name" to T.STRING, "type" to T.STRING, "messages" to T.NUMBER, "first_timestamp" to T.NUMBER,
            "segments" to T.NUMBER, "leader" to T.STRING, "members" to T.ARRAY, "online" to T.ARRAY, "arguments" to T.OBJECT,
            "consumers" to T.NUMBER, "state" to T.STRING, "node" to T.STRING, "vhost" to T.STRING), "list item")
        // The list item is slimmer than the single-queue object: no policy keys, no consumer_details (use columns=policy).
        assertAbsent(item, listOf("policy", "operator_policy", "consumer_details", "garbage_collection"), "list item")
        val withPolicy = admin.get("/api/queues?page=1&page_size=100&name=$name&columns=name,policy").obj["items"]!!.jsonArray.single().jsonObject
        assertShape(withPolicy, mapOf("policy" to T.NULL), "columns=policy")

        // Non-streams in the same list: type tells them apart; quorum carries message_bytes, the stream does not.
        val quorum = all.arr.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "quorum" }
        assertTrue(quorum.containsKey("message_bytes"), "quorum has message_bytes")
        assertFalse(inAll.containsKey("message_bytes"), "stream has no message_bytes")
        // The stream-specific listing of rabbitmq_stream_management does not list streams.
        assertEquals(404, admin.get("/api/streams").status)
        assertEquals(404, admin.get("/api/stream").status)
    }

    // ================= Question 2: publishers and consumers =================

    @Test
    fun `Q2 empty stream - publisher, consumer, connection and tracking endpoints`() {
        val name = unique("spike-q2e-")
        declareStream(name)
        for ((file, path) in listOf("stream-publishers-empty.json" to "/api/stream/publishers",
            "stream-consumers-empty.json" to "/api/stream/consumers", "stream-connections-empty.json" to "/api/stream/connections")) {
            val r = admin.get(path)
            assertEquals(200, r.status, path)
            assertEquals("[]", r.body, "$path on a broker without stream clients")
            fixture(file, r)
        }
        assertEquals("[]", admin.get("/api/stream/publishers/%2F").body)
        assertEquals("[]", admin.get("/api/stream/consumers/%2F").body)
        val perStream = admin.get("/api/stream/publishers/%2F/$name")
        assertEquals(200, perStream.status)
        assertEquals("[]", perStream.body, "publishers of an existing empty stream: 200 []")
        // There is no per-stream consumers variant: 400 with an empty body (so filter /api/stream/consumers/{vhost} client side).
        val consumersPerStream = admin.get("/api/stream/consumers/%2F/$name")
        assertEquals(400, consumersPerStream.status)
        assertEquals("", consumersPerStream.body)
        val tracking = admin.get("/api/stream/%2F/$name/tracking")
        assertEquals(200, tracking.status)
        assertEquals(setOf("writers", "offsets"), tracking.obj.keys)
        assertEquals(0, tracking.obj["writers"]!!.jsonObject.size)
        assertEquals(0, tracking.obj["offsets"]!!.jsonObject.size)
        assertEquals(404, admin.get("/api/stream/%2F/${name}-nope/tracking").status, "tracking of an unknown stream")
        // Not found variants that do not exist in 4.x.
        assertEquals(404, admin.get("/api/stream/connections/%2F/x/publishers").status)
        val noSuch = admin.get("/api/queues/%2F/$name/publishers")
        assertEquals(400, noSuch.status)
        assertEquals("", noSuch.body)
    }

    @Test
    fun `Q2 publisher and consumer rows created through the stream client - fields and types`() {
        val name = unique("spike-q2-")
        declareStream(name)
        val env = env()
        publishViaClient(env, name, 10, ref = "spike-pub")
        consumeViaClient(env, name, 10, ref = "spike-cons") // never stores an offset

        val pubs = awaitUntil("publisher row with published=10 for $name", fetch = { admin.get("/api/stream/publishers/%2F/$name") },
            done = { it.status == 200 && it.arr.size == 1 && it.arr[0].jsonObject["published"]?.jsonPrimitive?.long == 10L })
        fixture("stream-publishers.json", pubs, listOf(name))
        val pub = pubs.arr.single().jsonObject
        assertShape(pub, mapOf("reference" to T.STRING, "publisher_id" to T.NUMBER, "published" to T.NUMBER, "confirmed" to T.NUMBER,
            "errored" to T.NUMBER, "queue" to T.OBJECT, "connection_details" to T.OBJECT), "publisher row")
        assertEquals("spike-pub", pub["reference"]!!.jsonPrimitive.content)
        assertEquals(10, pub["confirmed"]!!.jsonPrimitive.long)
        assertEquals(0, pub["errored"]!!.jsonPrimitive.long)
        assertEquals(name, pub["queue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/", pub["queue"]!!.jsonObject["vhost"]!!.jsonPrimitive.content)
        assertShape(pub["connection_details"]!!.jsonObject, mapOf("name" to T.STRING, "node" to T.STRING, "user" to T.STRING,
            "peer_host" to T.STRING, "peer_port" to T.NUMBER), "publisher connection_details")
        assertEquals(rabbit.adminUsername, pub["connection_details"]!!.jsonObject["user"]!!.jsonPrimitive.content)

        // Wait for the SETTLED consumer row, not just for consumed=10. `consumed` is live, but `offset` is refreshed with the
        // periodic stats: until the first refresh (about 4-5 s) the row shows offset 10 (the next offset) with lag 0, afterwards
        // 9 (the last delivered offset) and, because of the tracking entry below, lag 1. Asserting on the first row that has
        // consumed=10 raced with that refresh (M2-02c). Same wait for each value, no tolerance in the assertions.
        fun rowOf(r: Resp) = r.arr.map { it.jsonObject }.firstOrNull { it["queue"]!!.jsonObject["name"]!!.jsonPrimitive.content == name }
        val cons = awaitUntil("consumer row consumed=10, offset=9, offset_lag=1 for $name", fetch = { admin.get("/api/stream/consumers/%2F") },
            done = { r -> r.status == 200 && rowOf(r)?.let { it["consumed"]?.jsonPrimitive?.long == 10L && it["offset"]?.jsonPrimitive?.long == 9L && it["offset_lag"]?.jsonPrimitive?.long == 1L } == true })
        fixture("stream-consumers.json", cons, listOf(name))
        val c = rowOf(cons)!!
        assertShape(c, mapOf("subscription_id" to T.NUMBER, "consumed" to T.NUMBER, "credits" to T.NUMBER, "offset" to T.NUMBER,
            "offset_lag" to T.NUMBER, "active" to T.BOOLEAN, "activity_status" to T.STRING, "properties" to T.OBJECT,
            "queue" to T.OBJECT, "connection_details" to T.OBJECT), "consumer row")
        assertEquals("spike-cons", c["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content, "consumer reference is under properties.name")
        assertEquals(9, c["offset"]!!.jsonPrimitive.long, "last delivered offset (10 messages: 0..9), once the row is refreshed")
        // Why there is a tracking entry although this test never stores an offset: the Java client's manual tracking strategy
        // has a safety net (OffsetTrackingCoordinator.ManualTrackingTracker.flushIfNecessary, about 1 s after consuming): a named
        // consumer that never stored gets store(lastRequestedOffset), and that field starts at 0. So `tracking.offsets` holds
        // spike-cons = 0 (the client's artefact, not a delivered offset). Storing an offset is a write to the stream: a tracking
        // chunk takes offset 10, the consumer is not delivered it (last delivered stays 9), committed chunk id becomes 10 and
        // lag = 10 - 9 = 1. Management `messages` counts that entry too (11). Deterministic, only its visibility is delayed.
        assertEquals(1, c["offset_lag"]!!.jsonPrimitive.long, "offset_lag counts the tracking entry after offset 9")
        assertEquals(11, awaitMessages(name, 11).obj["messages"]!!.jsonPrimitive.long, "10 messages + 1 tracking entry")
        val offsets = awaitUntil("tracking.offsets has spike-cons", fetch = { admin.get("/api/stream/%2F/$name/tracking") },
            done = { it.status == 200 && it.obj["offsets"]!!.jsonObject.containsKey("spike-cons") }).obj["offsets"]!!.jsonObject
        assertEquals(0, offsets["spike-cons"]!!.jsonPrimitive.long, "the client's manual-tracking safety net stores its initial lastRequestedOffset 0")
        assertEquals(true, c["active"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("up", c["activity_status"]!!.jsonPrimitive.content)

        // The queue object counts consumers but not publishers; connections show the stream-protocol sockets.
        val q = awaitUntil("queue consumers == 1", fetch = { queue(name) }, done = { it.obj["consumers"]?.jsonPrimitive?.long == 1L })
        assertEquals(1, q.obj["consumer_details"]!!.jsonArray.size)
        assertAbsent(q.obj, listOf("publishers"), "stream queue object")
        val conns = awaitUntil("stream connections", fetch = { admin.get("/api/stream/connections") }, done = { it.status == 200 && it.arr.size >= 3 })
        fixture("stream-connections.json", conns, listOf(name))
        val conn = conns.arr.map { it.jsonObject }.first()
        assertShape(conn, mapOf("name" to T.STRING, "node" to T.STRING, "user" to T.STRING, "vhost" to T.STRING, "protocol" to T.STRING,
            "ssl" to T.BOOLEAN, "connected_at" to T.NUMBER, "user_provided_name" to T.STRING, "client_properties" to T.OBJECT), "stream connection")
        assertEquals("stream", conn["protocol"]!!.jsonPrimitive.content)
        assertEquals(true, conn["ssl"]!!.jsonPrimitive.booleanOrNull, "client connected over TLS")
        // AMQP-side mirror of stream consumers (no offset or lag there).
        val amqpConsumers = admin.get("/api/consumers/%2F")
        assertTrue(amqpConsumers.arr.any { it.jsonObject["queue"]!!.jsonObject["name"]!!.jsonPrimitive.content == name })
        val tracking = admin.get("/api/stream/%2F/$name/tracking")
        assertEquals(10 - 1L, tracking.obj["writers"]!!.jsonObject["spike-pub"]!!.jsonPrimitive.long, "writer reference -> last published publishing id (0-based sequence)")
        fixture("stream-tracking.json", tracking)
    }

    @Test
    fun `Q2 a consumer without tracking leaves no entry - lag 0, messages equals the user messages (what browsing must use)`() {
        val env = env()
        for ((label, configure) in listOf<Pair<String, (com.rabbitmq.stream.ConsumerBuilder) -> com.rabbitmq.stream.ConsumerBuilder>>(
            "unnamed" to { it },
            "named, noTrackingStrategy" to { it.name("spike-notrack").noTrackingStrategy() },
        )) {
            val name = unique("spike-q2n-")
            declareStream(name)
            publishViaClient(env, name, 10, ref = "spike-pub")
            val got = CountDownLatch(10)
            val c = configure(env.consumerBuilder().stream(name).offset(OffsetSpecification.first())).messageHandler { _, _ -> got.countDown() }.build()
            closeables += c
            assertTrue(got.await(30, TimeUnit.SECONDS), "$label: 10 messages not delivered")
            // Settled row: the offset flips from 10 (next) to 9 (last delivered) at the first stats refresh, see the test above.
            val row = awaitUntil("$label: consumer row offset=9", fetch = { admin.get("/api/stream/consumers/%2F") },
                done = { r -> r.status == 200 && r.arr.any { it.jsonObject["queue"]!!.jsonObject["name"]!!.jsonPrimitive.content == name && it.jsonObject["offset"]?.jsonPrimitive?.long == 9L } })
                .arr.map { it.jsonObject }.single { it["queue"]!!.jsonObject["name"]!!.jsonPrimitive.content == name }
            assertEquals(0, row["offset_lag"]!!.jsonPrimitive.long, "$label: caught up means lag 0 when nothing was stored")
            assertEquals(10, awaitMessages(name, 10).obj["messages"]!!.jsonPrimitive.long, "$label: no tracking entry, messages == user messages")
            // The client's tracking safety net runs about 1 s after consuming; the waits above took longer than that.
            assertEquals(0, admin.get("/api/stream/%2F/$name/tracking").obj["offsets"]!!.jsonObject.size, "$label: nothing stored")
            c.close()
        }
    }

    @Test
    fun `Q2 non-admin visibility - monitoring and management see all names of their vhosts, management only its own connections, no tag is refused`() {
        val allowed = unique(ALLOWED)
        val private = unique(PRIVATE)
        val other = unique("spike-in-other-")
        declareStream(allowed); declareStream(private); declareStream(other, vhost = OTHER_VHOST)
        val adminEnv = env()
        publishViaClient(adminEnv, allowed, 5, ref = "admin-pub-a")
        publishViaClient(adminEnv, private, 5, ref = "admin-pub-p")
        // The management-tag user has its own publisher on the stream it may write to.
        publishViaClient(env("spike-mgmt", pw.getValue("spike-mgmt")), allowed, 2, ref = "mgmt-pub")
        awaitUntil("admin + mgmt publishers visible to admin", fetch = { admin.get("/api/stream/publishers") },
            done = { r -> r.status == 200 && r.arr.size == 3 && r.arr.all { it.jsonObject["published"]!!.jsonPrimitive.long > 0 } })

        val mon = mgmt("spike-mon", pw.getValue("spike-mon"))
        val mg = mgmt("spike-mgmt", pw.getValue("spike-mgmt"))
        val plain = mgmt("spike-plain", pw.getValue("spike-plain"))

        // monitoring: whole broker, whatever the name permission of the user
        val monPubs = mon.get("/api/stream/publishers")
        assertEquals(200, monPubs.status)
        assertEquals(setOf("admin-pub-a", "admin-pub-p", "mgmt-pub"), monPubs.arr.map { it.jsonObject["reference"]!!.jsonPrimitive.content }.toSet(),
            "monitoring sees publishers on streams it has no name permission on, and of other users")
        val monList = mon.get("/api/queues?page=1&page_size=100&columns=name,type,messages,vhost")
        assertEquals(200, monList.status)
        val monNames = monList.obj["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(allowed in monNames && private in monNames, "monitoring sees streams without permission: $monNames")
        assertFalse(other in monNames, "even monitoring does not see a vhost it has no permission on: $monNames")
        assertEquals(404, queue(other, OTHER_VHOST, who = mon).status, "monitoring: a stream in a vhost without permission looks like it does not exist (404, not 401)")
        assertEquals(200, queue(private, who = mon).status, "full queue object of a stream the user cannot read")
        assertEquals(200, mon.get("/api/stream/%2F/$allowed/tracking").status)
        val denied = mon.get("/api/stream/%2F/$private/tracking")
        assertEquals(401, denied.status, "tracking needs permission on the stream itself")
        assertEquals("not_authorised", denied.obj["error"]!!.jsonPrimitive.content)
        fixture("non-admin-tracking-denied.json", denied, listOf(private))
        assertEquals(200, mon.get("/api/overview").status)

        // management tag: queues of the vhosts it has access to (all names), connections only its own
        val mgList = mg.get("/api/queues?page=1&page_size=100&columns=name,type")
        assertEquals(200, mgList.status)
        val mgNames = mgList.obj["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(allowed in mgNames && private in mgNames, "management sees streams without name permission in its vhost: $mgNames")
        assertFalse(other in mgNames, "management does not see streams of a vhost it has no access to: $mgNames")
        assertEquals(setOf("mgmt-pub"), mg.get("/api/stream/publishers").arr.map { it.jsonObject["reference"]!!.jsonPrimitive.content }.toSet(),
            "management sees only publishers on its own connections")
        assertEquals(0, mg.get("/api/stream/consumers").arr.size)

        // no tag: refused everywhere, so the app must not call these with such a user
        for (path in listOf("/api/queues", "/api/stream/publishers", "/api/stream/consumers", "/api/stream/connections", "/api/overview", "/api/queues/%2F/$allowed")) {
            val r = plain.get(path)
            assertEquals(401, r.status, "no tag: $path")
            assertEquals("not_authorised", r.obj["error"]!!.jsonPrimitive.content)
            assertEquals("Not management user", r.obj["reason"]!!.jsonPrimitive.content)
            if (path == "/api/queues") fixture("non-admin-no-tag-401.json", r)
        }
        // wrong password
        assertEquals(401, mgmt("spike-mon", "wrong").get("/api/queues").status)
    }

    // ================= Question 3: offsets =================

    @Test
    fun `Q3 management has no offsets - first and last offset come from queryStreamStats, messages is the count`() {
        val name = unique("spike-q3a-")
        declareStream(name)
        publishViaManagement(name, 1000) // each message its own chunk
        val q = awaitMessages(name, 1000)
        assertAbsent(q.obj, listOf("first_offset", "last_offset", "committed_offset", "committed_chunk_id", "first_offset_timestamp", "last_timestamp", "last_offset_timestamp"), "queue object")
        assertEquals(T.NUMBER, q.obj["first_timestamp"]!!.type(), "first_timestamp (ms epoch) is the only timestamp")
        val tracking = admin.get("/api/stream/%2F/$name/tracking")
        assertAbsent(tracking.obj, listOf("first_offset", "last_offset", "committed_offset"), "tracking")

        val stats = env().queryStreamStats(name)
        assertEquals(0L, stats.firstOffset())
        assertEquals(999L, stats.committedChunkId(), "one message per chunk: committed chunk id == last offset")
        assertEquals(q.obj["messages"]!!.jsonPrimitive.long, stats.committedChunkId() - stats.firstOffset() + 1,
            "with single-message chunks the 03 formula (committed - first + 1) equals messages")
        // the first message was published after the stream was created: first_timestamp is a recent epoch in ms
        val now = System.currentTimeMillis()
        assertTrue(q.obj["first_timestamp"]!!.jsonPrimitive.long in (now - 600_000)..(now + 60_000), "first_timestamp ${q.obj["first_timestamp"]} vs now $now")
    }

    @Test
    fun `Q3 batched chunks - committed chunk id is a chunk start so the formula undercounts, and a stored offset adds one entry`() {
        val name = unique("spike-q3b-")
        declareStream(name)
        val env = env()
        publishViaClient(env, name, 100)
        val noConsumer = awaitMessages(name, 100)
        val s1 = env.queryStreamStats(name)
        assertEquals(0L, s1.firstOffset())
        assertTrue(s1.committedChunkId() - s1.firstOffset() + 1 <= 100, "committed chunk id (${s1.committedChunkId()}) is the START of the last chunk")
        assertEquals(100, noConsumer.obj["messages"]!!.jsonPrimitive.long)

        consumeViaClient(env, name, 100, ref = "spike-store", storeAt = 50)
        val withStore = awaitMessages(name, 101)
        assertEquals(101, withStore.obj["messages"]!!.jsonPrimitive.long, "storing a consumer offset appends a tracking entry: messages 100 -> 101")
        val s2 = awaitUntil("committed chunk id 100", fetch = { env.queryStreamStats(name) }, done = { it.committedChunkId() == 100L })
        assertEquals(100L, s2.committedChunkId(), "the tracking entry is its own chunk at offset 100")
        val tracking = admin.get("/api/stream/%2F/$name/tracking")
        assertEquals(50, tracking.obj["offsets"]!!.jsonObject["spike-store"]!!.jsonPrimitive.long, "stored consumer offset is under tracking.offsets")
        val consumers = admin.get("/api/stream/consumers/%2F")
        assertEquals(200, consumers.status)
    }

    @Test
    fun `Q3 retention moves the first offset - messages stays the live count`() {
        val name = unique("spike-q3r-")
        val env = env()
        env.streamCreator().stream(name).maxSegmentSizeBytes(ByteCapacity.kB(500)).maxLengthBytes(ByteCapacity.kB(1000)).create()
        queuesToDelete += "%2F" to name
        val total = 3000
        publishViaClient(env, name, total, size = 2000)
        awaitUntil("retention truncated the stream (first offset > 0)", fetch = { env.queryStreamStats(name) }, done = { it.firstOffset() > 0 })
        // Retention keeps removing segments for a moment, so compare both values on the same poll once they agree.
        val agreed = awaitUntil("messages == published - first offset",
            fetch = { env.queryStreamStats(name).firstOffset() to queue(name) },
            done = { (first, q) -> q.obj["messages"]?.jsonPrimitive?.long == total - first })
        val first = agreed.first
        val q = agreed.second
        assertTrue(first > 0, "first offset moved by retention: $first")
        assertEquals(total - first, q.obj["messages"]!!.jsonPrimitive.long, "messages == published - first offset (first=$first)")
        assertEquals(setOf("x-max-length-bytes", "x-queue-leader-locator", "x-queue-type", "x-stream-max-segment-size-bytes"),
            q.obj["arguments"]!!.jsonObject.keys, "arguments set by the client creator")
    }

    // ================= Question 4: search and paging =================

    @Test
    fun `Q4 server side name filter, regex, pagination fields, page size limit, sort, columns`() {
        val tag = unique("spike-q4-")
        val streams = (1..6).map { "${tag}s$it" }
        val classics = (1..7).map { "${tag}c$it" }
        classics.forEach { declareQueue(it, "classic") }
        streams.forEach { declareStream(it) }
        val prefix = "/api/queues?name=$tag&use_regex=false"

        val p1 = awaitUntil("13 queues indexed", fetch = { admin.get("$prefix&page=1&page_size=5") }, done = { it.status == 200 && it.obj["filtered_count"]?.jsonPrimitive?.long == 13L })
        fixture("queues-search-page.json", p1, listOf(tag))
        assertShape(p1.obj, mapOf("items" to T.ARRAY, "page" to T.NUMBER, "page_size" to T.NUMBER, "page_count" to T.NUMBER,
            "item_count" to T.NUMBER, "filtered_count" to T.NUMBER, "total_count" to T.NUMBER), "pagination")
        assertEquals(setOf("items", "page", "page_size", "page_count", "item_count", "filtered_count", "total_count"), p1.obj.keys)
        assertEquals(1, p1.obj["page"]!!.jsonPrimitive.long)
        assertEquals(5, p1.obj["page_size"]!!.jsonPrimitive.long)
        assertEquals(5, p1.obj["item_count"]!!.jsonPrimitive.long)
        assertEquals(3, p1.obj["page_count"]!!.jsonPrimitive.long, "13 filtered items / 5 per page")
        assertTrue(p1.obj["total_count"]!!.jsonPrimitive.long >= 13, "total_count counts all queues, filtered_count the matches")
        val p3 = admin.get("$prefix&page=3&page_size=5")
        assertEquals(3, p3.obj["item_count"]!!.jsonPrimitive.long, "last page holds the remainder")

        // name is a substring match without use_regex... and a regex with it; both filter on the NAME only
        val streamsOnlyByName = admin.get("/api/queues?page=1&page_size=100&name=${tag}s&use_regex=false")
        assertEquals(6, streamsOnlyByName.obj["filtered_count"]!!.jsonPrimitive.long)
        val regex = admin.get("/api/queues?page=1&page_size=100&name=%5E$tag" + "s%5B0-9%5D%24&use_regex=true")
        assertEquals(6, regex.obj["filtered_count"]!!.jsonPrimitive.long)
        assertTrue(regex.obj["items"]!!.jsonArray.all { it.jsonObject["type"]!!.jsonPrimitive.content == "stream" })
        // invalid regex is not an error: 200 and no match
        val bad = admin.get("/api/queues?page=1&page_size=5&name=%5B&use_regex=true")
        assertEquals(200, bad.status)
        assertEquals(0, bad.obj["filtered_count"]!!.jsonPrimitive.long)

        // no server side "streams only" filter: type / queue_type are ignored, so the app filters on `type` itself
        for (param in listOf("type=stream", "queue_type=stream")) {
            val r = admin.get("$prefix&$param&page=1&page_size=100")
            assertEquals(13, r.obj["filtered_count"]!!.jsonPrimitive.long, "$param is ignored by the server")
        }
        val clientSide = admin.get("$prefix&page=1&page_size=100").obj["items"]!!.jsonArray
            .filter { it.jsonObject["type"]!!.jsonPrimitive.content == "stream" }
        assertEquals(6, clientSide.size)

        // limits
        val max = admin.get("/api/queues?page=1&page_size=500")
        assertEquals(200, max.status, "page_size 500 is accepted")
        assertEquals(500, max.obj["page_size"]!!.jsonPrimitive.long)
        val over = admin.get("/api/queues?page=1&page_size=501")
        assertEquals(400, over.status, "page_size 501 is rejected")
        assertEquals("invalid_pagination_parameters", over.obj["error"]!!.jsonPrimitive.content)
        fixture("queues-page-size-501-error.json", over)
        assertEquals(400, admin.get("/api/queues?page=1&page_size=0").status)
        assertEquals(400, admin.get("/api/queues?page=0&page_size=5").status)
        val outOfRange = admin.get("$prefix&page=99&page_size=5")
        assertEquals(400, outOfRange.status, "a page beyond page_count is an error, not an empty page")
        assertEquals("page_out_of_range", outOfRange.obj["error"]!!.jsonPrimitive.content)

        // sort and columns
        val desc = admin.get("$prefix&page=1&page_size=5&sort=name&sort_reverse=true&columns=name,type,messages")
        val names = desc.obj["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(names.sortedDescending(), names, "sort=name&sort_reverse=true")
        for (it in desc.obj["items"]!!.jsonArray) {
            val keys = it.jsonObject.keys
            assertTrue(keys.containsAll(setOf("name", "type")) && setOf("name", "type", "messages").containsAll(keys), "columns trims each item to the requested fields (a just declared queue has no messages yet): $keys")
        }
    }

    // ================= Question 5: super streams =================

    @Test
    fun `Q5 super stream - partitions are plain streams, the super stream is a direct exchange with partition order bindings`() {
        val name = unique("spike-ss-")
        val r = rabbit.execInContainer("rabbitmq-streams", "add_super_stream", name, "--partitions", "3")
        assertEquals(0, r.exitCode, "rabbitmq-streams add_super_stream exists in the image: ${r.stderr}")
        superStreamsToDelete += name
        exchangesToDelete += name

        val partitions = (0..2).map { "$name-$it" }
        val list = awaitUntil("3 partition queues listed", fetch = { admin.get("/api/queues?page=1&page_size=100&name=$name&use_regex=false") },
            done = { it.status == 200 && it.obj["filtered_count"]?.jsonPrimitive?.long == 3L })
        fixture("queues-super-stream-partitions.json", list, listOf(name))
        for (item in list.obj["items"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals("stream", item["type"]!!.jsonPrimitive.content)
            assertTrue(item["name"]!!.jsonPrimitive.content in partitions)
            assertAbsent(item["arguments"]!!.jsonObject, listOf("x-super-stream", "x-stream-partition-order"), "partition queue arguments: no marker on the queue itself")
            assertEquals("balanced", item["arguments"]!!.jsonObject["x-queue-leader-locator"]!!.jsonPrimitive.content)
        }

        val ex = admin.get("/api/exchanges/%2F/$name")
        assertEquals(200, ex.status)
        fixture("exchange-super-stream.json", ex, listOf(name))
        assertEquals("direct", ex.obj["type"]!!.jsonPrimitive.content, "the super stream exchange is type direct")
        assertEquals(true, ex.obj["arguments"]!!.jsonObject["x-super-stream"]!!.jsonPrimitive.booleanOrNull, "marked by the argument x-super-stream=true")

        val bindings = admin.get("/api/exchanges/%2F/$name/bindings/source")
        fixture("bindings-super-stream.json", bindings, listOf(name))
        val b = bindings.arr.map { it.jsonObject }.sortedBy { it["arguments"]!!.jsonObject["x-stream-partition-order"]!!.jsonPrimitive.long }
        assertEquals(3, b.size)
        for ((i, bind) in b.withIndex()) {
            assertEquals("queue", bind["destination_type"]!!.jsonPrimitive.content)
            assertEquals("$name-$i", bind["destination"]!!.jsonPrimitive.content)
            assertEquals("$i", bind["routing_key"]!!.jsonPrimitive.content, "default binding keys are the partition indexes")
            assertEquals(T.NUMBER, bind["arguments"]!!.jsonObject["x-stream-partition-order"]!!.type())
            assertEquals(i.toLong(), bind["arguments"]!!.jsonObject["x-stream-partition-order"]!!.jsonPrimitive.long)
        }
        // A partition shows the same binding on its own bindings list (besides the default-exchange binding).
        val partitionBindings = admin.get("/api/queues/%2F/${partitions[0]}/bindings").arr.map { it.jsonObject }
        assertTrue(partitionBindings.any { it["source"]!!.jsonPrimitive.content == name && it["arguments"]!!.jsonObject.containsKey("x-stream-partition-order") })
        // Partition streams are ordinary streams for the stream list: management has no partition field.
        assertAbsent(queue(partitions[1]).obj, listOf("super_stream", "partitions", "x-super-stream"), "partition queue object")
    }

    @Test
    fun `Q5 super stream with custom binding keys - the partition name is super stream, dash, binding key`() {
        val name = unique("spike-ssk-")
        val r = rabbit.execInContainer("rabbitmq-streams", "add_super_stream", name, "--binding-keys", "eu,us")
        assertEquals(0, r.exitCode, r.stderr)
        superStreamsToDelete += name
        exchangesToDelete += name
        val b = awaitUntil("2 bindings", fetch = { admin.get("/api/exchanges/%2F/$name/bindings/source") }, done = { it.status == 200 && it.arr.size == 2 })
        val byOrder = b.arr.map { it.jsonObject }.sortedBy { it["arguments"]!!.jsonObject["x-stream-partition-order"]!!.jsonPrimitive.long }
        assertEquals(listOf("eu", "us"), byOrder.map { it["routing_key"]!!.jsonPrimitive.content })
        assertEquals(listOf("$name-eu", "$name-us"), byOrder.map { it["destination"]!!.jsonPrimitive.content })
    }

    // ================= Question 6: Testcontainers deprecation =================

    @Test
    fun `Q6 RabbitMQContainer withPluginsEnabled is annotated Deprecated in the pinned Testcontainers version`() {
        val m = RabbitMQContainer::class.java.getMethod("withPluginsEnabled", Array<String>::class.java)
        assertTrue(m.isAnnotationPresent(java.lang.Deprecated::class.java),
            "the Kotlin compiler warning 'withPluginsEnabled is deprecated. Deprecated in Java.' comes from this annotation; Testcontainers source says: use execInContainer instead")
    }

    // ================= Question 7: plugins and fixture =================

    @Test
    fun `Q7 fixture - overview version, stream TLS listener, extensions and a TLS handshake on the stream port`() {
        val o = admin.get("/api/overview")
        assertEquals(200, o.status)
        fixture("overview.json", o)
        assertTrue(o.obj["rabbitmq_version"]!!.jsonPrimitive.content.startsWith("4."), "RabbitMQ 4: ${o.obj["rabbitmq_version"]}")
        println("M2-02 broker version: ${o.obj["rabbitmq_version"]}")
        val listeners = o.obj["listeners"]!!.jsonArray.map { it.jsonObject }
        val streamTls = listeners.single { it["protocol"]!!.jsonPrimitive.content == "stream/ssl" }
        assertEquals(5551, streamTls["port"]!!.jsonPrimitive.long)
        assertEquals(true, streamTls["tls"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(listeners.any { it["protocol"]!!.jsonPrimitive.content == "https" && it["port"]!!.jsonPrimitive.long == 15671L })
        // The plugin shows up as UI extensions; /api/extensions is a second way to detect it.
        assertTrue(admin.get("/api/extensions").body.contains("stream_management"), "rabbitmq_stream_management registers its UI extension")

        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        (ctx.socketFactory.createSocket(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) as SSLSocket).use {
            it.soTimeout = 10_000
            it.startHandshake()
            assertTrue(it.session.protocol.startsWith("TLSv1."), "stream listener answers TLS: ${it.session.protocol}")
        }
    }

    @Test
    fun `Q7 without rabbitmq_stream_management the stream endpoints are 404 with a JSON body while queues still work`() {
        val noMgmt = newContainer("rabbitmq_stream")
        noMgmt.start()
        try {
            val m = Mgmt("https://${noMgmt.host}:${noMgmt.httpsPort}", noMgmt.adminUsername, noMgmt.adminPassword)
            assertEquals(200, m.get("/api/overview").status, "management itself is up")
            val name = unique("spike-q7-")
            assertEquals(201, m.send("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"stream"}}""").status)
            assertEquals("stream", m.get("/api/queues/%2F/$name").obj["type"]!!.jsonPrimitive.content, "stream queues are listed by the base management plugin")
            for (path in listOf("/api/stream/publishers", "/api/stream/consumers", "/api/stream/connections")) {
                val r = m.get(path)
                assertEquals(404, r.status, path)
                assertEquals("not_found", r.obj["error"]!!.jsonPrimitive.content)
                assertEquals("Not Found", r.obj["reason"]!!.jsonPrimitive.content)
                if (path == "/api/stream/publishers") fixture("plugin-missing-404.json", r)
            }
            // per-stream paths do NOT give a clean signature without the plugin: 400 with an empty body
            val perStream = m.get("/api/stream/publishers/%2F/$name")
            assertEquals(400, perStream.status)
            assertEquals("", perStream.body)
            assertEquals(400, m.get("/api/stream/%2F/$name/tracking").status)
            assertFalse(m.get("/api/extensions").body.contains("stream_management"))
        } finally {
            noMgmt.stop()
        }
    }
}
