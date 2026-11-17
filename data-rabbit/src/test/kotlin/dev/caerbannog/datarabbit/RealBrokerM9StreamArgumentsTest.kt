package dev.caerbannog.datarabbit

import com.rabbitmq.stream.ByteCapacity
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.StreamCreator
import com.rabbitmq.stream.StreamException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.URLEncoder
import java.time.Duration
import java.util.UUID

/**
 * M9-14c step 0 (rows S-14c-1 to S-14c-6 of `docs/tasks/M9-14c-stream-arguments.md`): what the broker really does with the stream settings
 * `x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes` and `x-queue-leader-locator`, MEASURED on a throwaway
 * `rabbitmq:4-management` (plugins `rabbitmq_stream`, `rabbitmq_stream_management`). Results and exact versions: `docs/tasks/M9-14c-spike-results.md`;
 * raw answers in `fixtures/m9-14c/` (written when `RECORD_M9_14C_FIXTURES` is set); facts in `build/m9-14c-facts.txt`.
 *
 * The client route is the stream client 1.12.0 creator on the stream protocol (the call `RabbitMqStreamManager` makes), used directly here: route T is
 * the typed creator methods, route R the raw `argument(String, String)`. The management read is `GET /api/queues/{vhost}/{name}`.
 * Every row holds a positive control (a stream made with the plain `create()` shows the broker's own arguments, so "arguments" is never read from nothing).
 * The image tag floats: a 4.x that answers differently fails here on purpose. Names are unique per run, one virtual host per test, all deleted at the end.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9StreamArgumentsTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.MethodName::class)
class RealBrokerM9StreamArgumentsTest {

    companion object {
        private val rabbit: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { factsFile.delete(); rabbit.start(); api = MgmtApi(rabbit) }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }

        private val record = System.getenv("RECORD_M9_14C_FIXTURES") != null
        private val factsFile = File("build/m9-14c-facts.txt")

        fun fact(item: String, text: String) {
            println("M9-14c $item: $text")
            factsFile.parentFile.mkdirs()
            factsFile.appendText("$item: $text\n")
        }

        fun save(name: String, body: JsonElement) {
            if (!record) return
            val dir = File("src/test/resources/fixtures/m9-14c"); dir.mkdirs()
            File(dir, name).writeText(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), body) + "\n")
        }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private val vhosts = mutableListOf<String>()
    private val policies = mutableListOf<String>()
    private val toClose = mutableListOf<AutoCloseable>()
    private var counter = 0

    @AfterEach
    fun cleanUp() {
        toClose.asReversed().forEach { runCatching { it.close() } }; toClose.clear()
        policies.forEach { runCatching { api.delete(it) } }; policies.clear()
        vhosts.forEach { runCatching { api.delete("/api/vhosts/${enc(it)}") } }; vhosts.clear()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun newVhost(label: String): String {
        val vhost = "m914c-$label-$run"
        assertTrue(api.put("/api/vhosts/$vhost", "{}").ok, "vhost $vhost")
        assertTrue(api.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""").ok)
        vhosts += vhost
        return vhost
    }

    private fun env(vhost: String): Environment {
        val e = Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(5552)).username("guest").password("guest").virtualHost(vhost)
            .addressResolver { com.rabbitmq.stream.Address(rabbit.host, rabbit.getMappedPort(5552)) }.build()
        toClose += e
        return e
    }

    private fun name(slug: String) = "s14c-$slug-${counter++}-$run"

    /** The outcome of one create: accepted, or refused with the exception TYPE and the stream response CODE (never the text). */
    private fun attempt(env: Environment, stream: String, configure: (StreamCreator) -> StreamCreator): String =
        try {
            configure(env.streamCreator().stream(stream)).create()
            "accepted"
        } catch (e: StreamException) {
            "refused ${e.javaClass.simpleName} code=${e.code}"
        } catch (e: Exception) {
            "refused ${e.javaClass.simpleName}"
        }

    private fun read(vhost: String, stream: String) = api.get("/api/queues/${enc(vhost)}/${enc(stream)}")

    /** The `arguments` of an accepted stream (waits until the management API lists it). */
    private fun argumentsOf(vhost: String, stream: String): JsonObject {
        var last: MgmtApi.Reply? = null
        awaitUntil("management lists stream $stream", timeoutSeconds = 30) { last = read(vhost, stream); last!!.status == 200 }
        return last!!.obj["arguments"] as JsonObject
    }

    /** `x-max-age=text:7D, x-queue-type=text:stream` (the JSON type and the exact value of each argument), sorted. */
    private fun shown(args: JsonObject): String = args.entries.sortedBy { it.key }.joinToString(", ") { (k, v) ->
        val p = v as? JsonPrimitive
        val type = when { v is JsonNull -> "null"; p == null -> "structure"; p.isString -> "text"; p.content == "true" || p.content == "false" -> "bool"; else -> "number" }
        "$k=$type:${v.toString().trim('"')}"
    }

    /** Positive control: a stream made by the plain `create()` shows the broker's own arguments, so a read of "arguments" is never an empty read. */
    private fun control(env: Environment, vhost: String) {
        val n = name("control")
        assertEquals("accepted", attempt(env, n) { it })
        val args = argumentsOf(vhost, n)
        assertEquals("x-queue-leader-locator=text:balanced, x-queue-type=text:stream", shown(args), "positive control: arguments of a stream made by plain create()")
    }

    /** Creates [stream] by [configure], and returns "outcome | arguments shown" (for an accepted one) or "outcome | absent" (the read answers 404). */
    private fun probe(env: Environment, vhost: String, slug: String, configure: (StreamCreator) -> StreamCreator, observed: MutableMap<String, String>, rawOut: MutableMap<String, JsonElement>, label: String) {
        val n = name(slug)
        val outcome = attempt(env, n, configure)
        val text = if (outcome == "accepted") "$outcome | ${shown(argumentsOf(vhost, n))}" else {
            val r = read(vhost, n)
            assertEquals(404, r.status, "$label was refused but the management API lists it: $r")
            "$outcome | absent"
        }
        observed[label] = text
        rawOut[label] = buildJsonObject {
            put("outcome", outcome)
            if (outcome == "accepted") put("arguments", argumentsOf(vhost, n))
        }
        fact(label, text)
    }

    private fun finish(row: String, slug: String, observed: Map<String, String>, raw: Map<String, JsonElement>) {
        save("s-14c-$row-$slug.json", JsonObject(raw))
        val unpinned = observed.filter { (k, v) -> PINNED["$row/$k"] != v }
        assertTrue(unpinned.isEmpty(), "S-14c-$row: measured values differ from the pinned ones (or are not pinned yet):\n" +
            unpinned.entries.joinToString("\n") { "  \"$row/${it.key}\" to \"${it.value}\"," })
        val missing = PINNED.keys.filter { it.startsWith("$row/") }.map { it.substringAfter("/") } - observed.keys
        assertTrue(missing.isEmpty(), "S-14c-$row: pinned rows that the test did not measure: $missing")
    }

    // ---- S-14c-0: which broker -----------------------------------------------------------------------------------------------

    @Test
    fun `S0 the broker is a 4x and its versions are recorded`() {
        M9c.assertFourX(api)
        val o = api.get("/api/overview").obj
        fact("S0", "rabbitmq_version=${o["rabbitmq_version"]} erlang_version=${o["erlang_version"]} management_version=${o["management_version"]} " +
            "os.arch=${System.getProperty("os.arch")} date=${java.time.LocalDate.now()}")
        save("overview-versions.json", buildJsonObject {
            listOf("rabbitmq_version", "erlang_version", "management_version").forEach { put(it, o[it] ?: JsonNull) }
        })
        fact("S0 client-leader-locator-enum", StreamCreator.LeaderLocator.values().joinToString { "${it.name}=${it.value()}" })
    }

    // ---- S-14c-1: keys and types, route T and route R --------------------------------------------------------------------------

    @Test
    fun `S1 keys and types - typed route T against raw route R, and the closed set`() {
        val vhost = newVhost("s1"); val env = env(vhost); control(env, vhost)
        val o = linkedMapOf<String, String>(); val raw = linkedMapOf<String, JsonElement>()
        fun p(label: String, slug: String, c: (StreamCreator) -> StreamCreator) = probe(env, vhost, slug, c, o, raw, label)
        p("T x-max-age 7 days", "t-age") { it.maxAge(Duration.ofDays(7)) }
        p("T x-max-length-bytes 1GB", "t-len") { it.maxLengthBytes(ByteCapacity.GB(1)) }
        p("T x-stream-max-segment-size-bytes 100MB", "t-seg") { it.maxSegmentSizeBytes(ByteCapacity.MB(100)) }
        p("T x-queue-leader-locator client-local", "t-loc") { it.leaderLocator(StreamCreator.LeaderLocator.CLIENT_LOCAL) }
        p("T x-queue-leader-locator balanced", "t-locb") { it.leaderLocator(StreamCreator.LeaderLocator.BALANCED) }
        p("R max-age 7D", "r-age") { it.argument("max-age", "7D") }
        p("R max-length-bytes 1000000000", "r-len") { it.argument("max-length-bytes", "1000000000") }
        p("R stream-max-segment-size-bytes 100000000", "r-seg") { it.argument("stream-max-segment-size-bytes", "100000000") }
        p("R queue-leader-locator client-local", "r-loc") { it.argument("queue-leader-locator", "client-local") }
        p("R dead-letter-exchange dlx", "r-dlx") { it.argument("dead-letter-exchange", "dlx") }
        p("R initial-cluster-size 1", "r-ics") { it.argument("initial-cluster-size", "1") }
        p("R queue-type stream", "r-type") { it.argument("queue-type", "stream") }
        p("R queue-type quorum", "r-typeq") { it.argument("queue-type", "quorum") }
        p("R foo bar", "r-foo") { it.argument("foo", "bar") }
        // The names with the x- prefix, as they appear in the management arguments: does the stream protocol take them?
        p("R x-max-age 7D (x- name)", "rx-age") { it.argument("x-max-age", "7D") }
        p("R x-max-length-bytes 1000000000 (x- name)", "rx-len") { it.argument("x-max-length-bytes", "1000000000") }
        p("R x-stream-max-segment-size-bytes 100000000 (x- name)", "rx-seg") { it.argument("x-stream-max-segment-size-bytes", "100000000") }
        p("R x-queue-leader-locator client-local (x- name)", "rx-loc") { it.argument("x-queue-leader-locator", "client-local") }
        p("R x-foo bar (x- name)", "rx-foo") { it.argument("x-foo", "bar") }
        finish("1", "keys-and-types", o, raw)
    }

    // ---- S-14c-2: leader locator values ----------------------------------------------------------------------------------------

    @Test
    fun `S2 leader locator values`() {
        val vhost = newVhost("s2"); val env = env(vhost); control(env, vhost)
        val o = linkedMapOf<String, String>(); val raw = linkedMapOf<String, JsonElement>()
        for (v in listOf("balanced", "client-local", "random", "least-leaders", "nearest", "Balanced", "", "7")) {
            probe(env, vhost, "loc", { it.argument("queue-leader-locator", v) }, o, raw, "R queue-leader-locator '$v'")
        }
        finish("2", "leader-locator-values", o, raw)
    }

    // ---- S-14c-3: repeated create ----------------------------------------------------------------------------------------------

    @Test
    fun `S3 a repeated create never alters the stream`() {
        val vhost = newVhost("s3"); val env = env(vhost); control(env, vhost)
        val o = linkedMapOf<String, String>(); val raw = linkedMapOf<String, JsonElement>()
        val repeats: List<Pair<String, (StreamCreator) -> StreamCreator>> = listOf(
            "a same settings" to { c: StreamCreator -> c.argument("max-age", "7D").argument("queue-leader-locator", "balanced") },
            "b max-age 1D" to { c: StreamCreator -> c.argument("max-age", "1D").argument("queue-leader-locator", "balanced") },
            "c no settings" to { c: StreamCreator -> c },
            "d only max-age 7D" to { c: StreamCreator -> c.argument("max-age", "7D") },
            "e locator client-local" to { c: StreamCreator -> c.argument("max-age", "7D").argument("queue-leader-locator", "client-local") },
            "f superset with max-length-bytes" to { c: StreamCreator -> c.argument("max-age", "7D").argument("queue-leader-locator", "balanced").argument("max-length-bytes", "1000000000") },
            "g typed route T same retention" to { c: StreamCreator -> c.maxAge(Duration.ofDays(7)) },
        )
        for ((label, configure) in repeats) {
            val n = name("repeat")
            assertEquals("accepted", attempt(env, n) { it.argument("max-age", "7D").argument("queue-leader-locator", "balanced") }, "control: A is created")
            val before = argumentsOf(vhost, n)
            val outcome = attempt(env, n, configure)
            val after = argumentsOf(vhost, n)
            assertEquals(before, after, "repeat '$label' ALTERED the stream: before ${shown(before)}, after ${shown(after)} (STOP: surprise)")
            o["repeat $label"] = "$outcome | arguments unchanged: ${shown(after)}"
            raw["repeat $label"] = buildJsonObject { put("outcome", outcome); put("before", before); put("after", after) }
            fact("repeat $label", o["repeat $label"]!!)
        }
        // Control: a quorum queue of the same name answers code 17 (S-G3); here a stream over an existing quorum queue.
        val q = name("quorum")
        assertTrue(api.declareQueue(q, """{"x-queue-type":"quorum"}""", vhost = enc(vhost)).ok, "quorum queue")
        val onQueue = attempt(env, q) { it }
        o["create over an existing quorum queue"] = onQueue
        raw["create over an existing quorum queue"] = buildJsonObject { put("outcome", onQueue) }
        fact("create over an existing quorum queue", onQueue)
        assertEquals("quorum", read(vhost, q).obj["type"].toString().trim('"'), "the quorum queue is still a quorum queue")
        finish("3", "repeated-create", o, raw)
    }

    // ---- S-14c-4: retention by argument and by policy ---------------------------------------------------------------------------

    @Test
    fun `S4 retention by argument against policy - the first offset moves or not`() {
        val vhost = newVhost("s4"); val env = env(vhost); control(env, vhost)
        val o = linkedMapOf<String, String>(); val raw = linkedMapOf<String, JsonElement>()
        fun case(label: String, argBytes: ByteCapacity, policyBytes: Long?) {
            val n = name("ret")
            if (policyBytes != null) {
                val pPath = "/api/policies/${enc(vhost)}/pol-$n"
                assertTrue(api.put(pPath, """{"pattern":"^${n}$","apply-to":"streams","priority":1,"definition":{"max-length-bytes":$policyBytes}}""").ok, "policy")
                policies += pPath
            }
            assertEquals("accepted", attempt(env, n) { it.maxLengthBytes(argBytes).maxSegmentSizeBytes(ByteCapacity.B(500_000)) })
            if (policyBytes != null) awaitUntil("policy shows as effective on $n", 30) { read(vhost, n).obj["effective_policy_definition"]?.toString()?.contains("max-length-bytes") == true }
            val detail = read(vhost, n).obj
            // about 5 MB: 2500 messages of 2000 bytes
            val producer = env.producerBuilder().stream(n).build(); toClose += producer
            val latch = java.util.concurrent.CountDownLatch(2500)
            val body = ByteArray(2000) { 'x'.code.toByte() }
            repeat(2500) { producer.send(producer.messageBuilder().addData(body).build()) { latch.countDown() } }
            assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS), "confirms for 2500 messages on $n")
            var moved = false
            try { awaitUntil("first offset moved on $n", 60) { env.queryStreamStats(n).firstOffset().also { f -> if (f > 0) moved = true } > 0 } } catch (_: IllegalStateException) { }
            val first = env.queryStreamStats(n).firstOffset()
            val text = "first offset moved=$moved | arguments ${shown(detail["arguments"] as JsonObject)} | effective_policy_definition ${detail["effective_policy_definition"]} | policy applied=${detail["policy"] != null}"
            o[label] = text
            raw[label] = buildJsonObject { put("moved", moved); put("firstOffset", first); put("arguments", detail["arguments"]!!); put("effective_policy_definition", detail["effective_policy_definition"] ?: JsonNull); put("policy", detail["policy"] ?: JsonNull) }
            fact(label, "$text (final first offset $first)")
        }
        case("0 control: argument 1,000,000 bytes, no policy", ByteCapacity.B(1_000_000), null)
        case("1 argument 1,000,000 bytes, policy 10,000,000,000", ByteCapacity.B(1_000_000), 10_000_000_000L)
        case("2 argument 10,000,000,000 bytes, policy 1,000,000", ByteCapacity.B(10_000_000_000L), 1_000_000L)
        finish("4", "retention-argument-and-policy", o, raw)
    }

    // ---- S-14c-5: the default locator ------------------------------------------------------------------------------------------

    @Test
    fun `S5 the default leader locator is balanced - arguments equal`() {
        val vhost = newVhost("s5"); val env = env(vhost); control(env, vhost)
        val plain = name("plain"); val bal = name("balanced")
        assertEquals("accepted", attempt(env, plain) { it })
        assertEquals("accepted", attempt(env, bal) { it.leaderLocator(StreamCreator.LeaderLocator.BALANCED) })
        val a = argumentsOf(vhost, plain); val b = argumentsOf(vhost, bal)
        assertEquals(a, b, "STOP: plain create() and leaderLocator(BALANCED) show different arguments: ${shown(a)} against ${shown(b)}")
        val o = linkedMapOf("plain create()" to shown(a), "leaderLocator(BALANCED)" to shown(b))
        o.forEach { (k, v) -> fact(k, v) }
        finish("5", "default-locator", o, mapOf("plain" to a, "balanced" to b))
    }

    // ---- S-14c-6: value shapes ---------------------------------------------------------------------------------------------------

    @Test
    fun `S6 value shapes and bounds`() {
        val vhost = newVhost("s6"); val env = env(vhost); control(env, vhost)
        val o = linkedMapOf<String, String>(); val raw = linkedMapOf<String, JsonElement>()
        fun p(label: String, slug: String, c: (StreamCreator) -> StreamCreator) = probe(env, vhost, slug, c, o, raw, label)
        for (v in listOf("7D", "7d", "1Y", "10m", "0s", "7W", "P7D", "604800s", "1h", "7", "")) p("R max-age '$v'", "age") { it.argument("max-age", v) }
        for (d in listOf(Duration.ofDays(7), Duration.ofSeconds(1), Duration.ZERO, Duration.ofMillis(1500))) p("T x-max-age $d", "tage") { it.maxAge(d) }
        for (v in listOf("0", "1", Long.MAX_VALUE.toString(), "-1", "1.5", "1GB", "abc")) p("R max-length-bytes '$v'", "len") { it.argument("max-length-bytes", v) }
        for (b in listOf(0L, 1L, Long.MAX_VALUE, -1L)) p("T x-max-length-bytes $b", "tlen") { it.maxLengthBytes(ByteCapacity.B(b)) }
        for (v in listOf("0", "1", "3000000000", "4000000000", "-1", "1.5")) p("R stream-max-segment-size-bytes '$v'", "seg") { it.argument("stream-max-segment-size-bytes", v) }
        for (b in listOf(0L, 1L, 3_000_000_000L, 4_000_000_000L)) p("T x-stream-max-segment-size-bytes $b", "tseg") { it.maxSegmentSizeBytes(ByteCapacity.B(b)) }
        finish("6", "value-shapes", o, raw)
    }

    private val PINNED: Map<String, String> = PINNED_FACTS
}

/** The measured values of 2026-11-17 on RabbitMQ 4.4.0 (a changed answer fails its row and prints the new text). Key: row/probe. */
private val PINNED_FACTS: Map<String, String> = mapOf(
    "1/T x-max-age 7 days" to "accepted | x-max-age=text:604800s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/T x-max-length-bytes 1GB" to "accepted | x-max-length-bytes=number:1000000000, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/T x-stream-max-segment-size-bytes 100MB" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:100000000",
    "1/T x-queue-leader-locator client-local" to "accepted | x-queue-leader-locator=text:client-local, x-queue-type=text:stream",
    "1/T x-queue-leader-locator balanced" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R max-age 7D" to "accepted | x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R max-length-bytes 1000000000" to "accepted | x-max-length-bytes=number:1000000000, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R stream-max-segment-size-bytes 100000000" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:100000000",
    "1/R queue-leader-locator client-local" to "accepted | x-queue-leader-locator=text:client-local, x-queue-type=text:stream",
    "1/R dead-letter-exchange dlx" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R initial-cluster-size 1" to "accepted | x-initial-cluster-size=number:1, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R queue-type stream" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R queue-type quorum" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R foo bar" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R x-max-age 7D (x- name)" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R x-max-length-bytes 1000000000 (x- name)" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R x-stream-max-segment-size-bytes 100000000 (x- name)" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R x-queue-leader-locator client-local (x- name)" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "1/R x-foo bar (x- name)" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "2/R queue-leader-locator 'balanced'" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "2/R queue-leader-locator 'client-local'" to "accepted | x-queue-leader-locator=text:client-local, x-queue-type=text:stream",
    "2/R queue-leader-locator 'random'" to "accepted | x-queue-leader-locator=text:random, x-queue-type=text:stream",
    "2/R queue-leader-locator 'least-leaders'" to "accepted | x-queue-leader-locator=text:least-leaders, x-queue-type=text:stream",
    "2/R queue-leader-locator 'nearest'" to "refused StreamException code=17 | absent",
    "2/R queue-leader-locator 'Balanced'" to "refused StreamException code=17 | absent",
    "2/R queue-leader-locator ''" to "refused StreamException code=17 | absent",
    "2/R queue-leader-locator '7'" to "refused StreamException code=17 | absent",
    "3/repeat a same settings" to "accepted | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat b max-age 1D" to "refused StreamException code=17 | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat c no settings" to "refused StreamException code=17 | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat d only max-age 7D" to "accepted | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat e locator client-local" to "refused StreamException code=17 | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat f superset with max-length-bytes" to "refused StreamException code=17 | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/repeat g typed route T same retention" to "accepted | arguments unchanged: x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "3/create over an existing quorum queue" to "refused StreamException code=17",
    "4/0 control: argument 1,000,000 bytes, no policy" to "first offset moved=true | arguments x-max-length-bytes=number:1000000, x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:500000 | effective_policy_definition null | policy applied=false",
    "4/1 argument 1,000,000 bytes, policy 10,000,000,000" to "first offset moved=false | arguments x-max-length-bytes=number:1000000, x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:500000 | effective_policy_definition {\"max-length-bytes\":10000000000} | policy applied=true",
    "4/2 argument 10,000,000,000 bytes, policy 1,000,000" to "first offset moved=true | arguments x-max-length-bytes=number:10000000000, x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:500000 | effective_policy_definition {\"max-length-bytes\":1000000} | policy applied=true",
    "5/plain create()" to "x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "5/leaderLocator(BALANCED)" to "x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '7D'" to "accepted | x-max-age=text:7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '7d'" to "accepted | x-max-age=text:7d, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '1Y'" to "accepted | x-max-age=text:1Y, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '10m'" to "accepted | x-max-age=text:10m, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '0s'" to "accepted | x-max-age=text:0s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '7W'" to "accepted | x-max-age=text:7W, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age 'P7D'" to "accepted | x-max-age=text:P7D, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '604800s'" to "accepted | x-max-age=text:604800s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '1h'" to "accepted | x-max-age=text:1h, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age '7'" to "accepted | x-max-age=text:7, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-age ''" to "accepted | x-max-age=text:, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-age PT168H" to "accepted | x-max-age=text:604800s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-age PT1S" to "accepted | x-max-age=text:1s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-age PT0S" to "refused IllegalArgumentException | absent",
    "6/T x-max-age PT1.5S" to "accepted | x-max-age=text:1s, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-length-bytes '0'" to "accepted | x-max-length-bytes=number:0, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-length-bytes '1'" to "accepted | x-max-length-bytes=number:1, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-length-bytes '9223372036854775807'" to "accepted | x-max-length-bytes=number:9223372036854775807, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-length-bytes '-1'" to "accepted | x-max-length-bytes=number:-1, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R max-length-bytes '1.5'" to "refused StreamException code=-1 | absent",
    "6/R max-length-bytes '1GB'" to "refused StreamException code=-1 | absent",
    "6/R max-length-bytes 'abc'" to "refused StreamException code=-1 | absent",
    "6/T x-max-length-bytes 0" to "accepted | x-max-length-bytes=number:0, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-length-bytes 1" to "accepted | x-max-length-bytes=number:1, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-length-bytes 9223372036854775807" to "accepted | x-max-length-bytes=number:9223372036854775807, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-max-length-bytes -1" to "accepted | x-max-length-bytes=number:-1, x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/R stream-max-segment-size-bytes '0'" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:0",
    "6/R stream-max-segment-size-bytes '1'" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:1",
    "6/R stream-max-segment-size-bytes '3000000000'" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:3000000000",
    "6/R stream-max-segment-size-bytes '4000000000'" to "refused StreamException code=17 | absent",
    "6/R stream-max-segment-size-bytes '-1'" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:-1",
    "6/R stream-max-segment-size-bytes '1.5'" to "refused StreamException code=-1 | absent",
    "6/T x-stream-max-segment-size-bytes 0" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream",
    "6/T x-stream-max-segment-size-bytes 1" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:1",
    "6/T x-stream-max-segment-size-bytes 3000000000" to "accepted | x-queue-leader-locator=text:balanced, x-queue-type=text:stream, x-stream-max-segment-size-bytes=number:3000000000",
    "6/T x-stream-max-segment-size-bytes 4000000000" to "refused IllegalArgumentException | absent",
)
