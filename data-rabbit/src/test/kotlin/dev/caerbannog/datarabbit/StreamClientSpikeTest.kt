package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.StreamStats
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.io.ByteArrayInputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * M2-03a mini-spike (docs/tasks/M2-03a-stream-stats-permissions.md): what does the official stream client 1.12.0
 * (`Environment.queryStreamStats`) do against a real RabbitMQ 4 broker over TLS for admin / monitoring / management /
 * no-tag users on a permitted and a not permitted stream; does it write anything; how does the client trust the profile
 * CA without verifying the host name; how do failed credentials and an unreachable port look; what does one Environment
 * cost. Every finding is an assertion on a class and a stable message fragment or a value; the verbatim texts are in the
 * brief's Status.
 *
 * Test-only: throwaway users, the container, the helper that builds a CA-only netty SslContext. The management HTTP helper
 * below uses trust-all for the management API only (as the M2-02 spike did); the stream client NEVER does, every
 * Environment here trusts the fixture CA only (`tls/ca.pem`) or, for the negative control, a different CA.
 * No named consumer, no browsing, nothing published by a non-admin user, no fixed sleeps (bounded [awaitUntil] polling).
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class StreamClientSpikeTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val ALLOWED = "spike-allowed-"
        private const val PRIVATE = "spike-private-"
        private val pw = mapOf("spike-mon" to "pw-mon", "spike-mgmt" to "pw-mgmt", "spike-plain" to "pw-plain")
        private const val WRONG_PASSWORD = "not-the-password-xyz"

        private val rabbit: RabbitMQContainer =
            brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")
                .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
                .apply { addExposedPorts(STREAM_TLS_PORT) }

        private val caPem: String by lazy {
            checkNotNull(StreamClientSpikeTest::class.java.getResourceAsStream("/tls/ca.pem")) { "tls/ca.pem missing" }
                .use { String(it.readAllBytes()) }
        }

        /** Management API helper only: trust-all, no host name check (same as the M2-02 spike). Never used for the stream client. */
        private val trustAllManagement = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        private val http: HttpClient by lazy {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAllManagement), SecureRandom()) }
            HttpClient.newBuilder().sslContext(ctx)
                .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" }).build()
        }

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
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

    private fun mgmtSend(user: String, pass: String, method: String, path: String): Resp {
        val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
        val b = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path")).header("Authorization", "Basic $auth")
        val r = http.send(b.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body())
    }

    private fun admin(method: String, path: String, body: String? = null): Resp {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val b = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path")).header("Authorization", "Basic $auth")
        if (body != null) b.header("Content-Type", "application/json")
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val r = http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body())
    }

    private fun adminGet(path: String) = admin("GET", path)

    private val streamsToDelete = mutableListOf<String>()
    private val environments = mutableListOf<Environment>()

    @AfterEach fun cleanUp() {
        environments.forEach { runCatching { it.close() } }
        for (s in streamsToDelete) runCatching { admin("DELETE", "/api/queues/%2F/$s") }
        environments.clear(); streamsToDelete.clear()
    }

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun declareStream(name: String) {
        val r = admin("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"stream"}}""")
        assertEquals(201, r.status, "declare stream $name: $r")
        streamsToDelete += name
    }

    private fun publishViaManagement(stream: String, count: Int) {
        for (i in 1..count) {
            val r = admin("POST", "/api/exchanges/%2F/amq.default/publish",
                """{"properties":{},"routing_key":"$stream","payload":"m$i","payload_encoding":"string"}""")
            assertEquals(200, r.status, "publish $i: $r")
        }
    }

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

    private fun messages(stream: String): Long = adminGet("/api/queues/%2F/$stream").obj["messages"]?.jsonPrimitive?.long ?: -1L // absent until the first stats tick

    // ---------- TLS: the product's trust model (profile CA, no host name verification) ----------

    private fun certificateOf(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    /**
     * The candidate product recipe: netty SslContext trusting ONLY [caPem]; [verifyHostName] switches netty's
     * endpoint identification ("HTTPS" = verify the certificate's names, null = off). The chain is always checked.
     */
    private fun caOnlyContext(ca: String = caPem, verifyHostName: Boolean): SslContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", certificateOf(ca))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SslContextBuilder.forClient().trustManager(tmf)
            .endpointIdentificationAlgorithm(if (verifyHostName) "HTTPS" else null)
            .build()
    }

    private fun streamPort() = rabbit.getMappedPort(STREAM_TLS_PORT)

    private fun buildEnv(
        user: String = rabbit.adminUsername, pass: String = rabbit.adminPassword,
        host: String = rabbit.host, port: Int = streamPort(), ssl: SslContext = caOnlyContext(verifyHostName = false),
        rpcTimeout: Duration? = null,
    ): Environment {
        val b = Environment.builder().host(host).port(port).username(user).password(pass)
            .addressResolver { Address(host, port) }
        if (rpcTimeout != null) b.rpcTimeout(rpcTimeout)
        val e = b.tls().sslContext(ssl).environmentBuilder().build()
        environments += e
        return e
    }

    private fun describe(t: Throwable): String {
        val sb = StringBuilder("${t.javaClass.name}: ${t.message}")
        var c = t.cause
        var depth = 0
        while (c != null && depth++ < 4) { sb.append(" <- ${c.javaClass.name}: ${c.message}"); c = c.cause }
        return sb.toString()
    }

    private fun statsOutcome(e: Environment, stream: String): String =
        try { val s: StreamStats = e.queryStreamStats(stream); "OK first=${s.firstOffset()} committedChunkId=${s.committedChunkId()}" }
        catch (t: Throwable) { "CALL " + describe(t) }

    // ================= Item 1: stats matrix =================

    private class Streams(val allowed: String, val private: String)

    private fun twoStreams(): Streams {
        val id = UUID.randomUUID().toString().take(8)
        val s = Streams("$ALLOWED$id", "$PRIVATE$id")
        declareStream(s.allowed); declareStream(s.private)
        publishViaManagement(s.allowed, 3); publishViaManagement(s.private, 3) // 3 publishes by admin = 3 chunks: offsets 0..2
        return s
    }

    private fun assertOk(actual: String, expected: String, cell: String) = assertEquals(expected, actual, "stats outcome of $cell")

    @Test
    fun `stats matrix - every user builds the Environment, a permitted stream gives first offset, a private one gives ACCESS_REFUSED`() {
        val s = twoStreams()
        val users = listOf(rabbit.adminUsername to rabbit.adminPassword) + pw.map { it.key to it.value }
        for ((user, password) in users) {
            // No tag (and every tag) connects: Environment.build() opens the locator connection and authenticates, it does not throw.
            val e = buildEnv(user, password)
            val ok = e.queryStreamStats(s.allowed)
            assertEquals(0L, ok.firstOffset(), "$user: firstOffset on the permitted stream (3 messages published as 3 chunks)")
            assertEquals(2L, ok.committedChunkId(), "$user: committedChunkId on the permitted stream")
            assertEquals(2L, ok.committedOffset(), "$user: committedOffset on the permitted stream")
            if (user == rabbit.adminUsername) {
                assertEquals(0L, e.queryStreamStats(s.private).firstOffset(), "admin (full permissions) can read the private stream too")
            } else {
                val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamException::class.java,
                    { e.queryStreamStats(s.private) }, "$user: stats of a stream without read permission")
                assertEquals(com.rabbitmq.stream.StreamException::class.java, ex.javaClass, "$user: exact class (not a subclass)")
                assertTrue(ex.message!!.contains("16 (ACCESS_REFUSED)"), "$user: message was: ${ex.message}")
                assertTrue(ex.message!!.contains("Error while querying stream stats"), "$user: message was: ${ex.message}")
                // The locator survives a refused call: the same Environment still answers on the permitted stream.
                assertEquals(0L, e.queryStreamStats(s.allowed).firstOffset(), "$user: Environment usable after ACCESS_REFUSED")
            }
        }
    }

    @Test
    fun `stats of an empty stream - NoOffsetException, not a first offset`() {
        val name = unique(ALLOWED); declareStream(name)
        val e = buildEnv()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.NoOffsetException::class.java, { e.queryStreamStats(name).firstOffset() })
        assertTrue(ex.message!!.startsWith("No first offset for stream $name"), "message was: ${ex.message}")
    }

    @Test
    fun `stats of a stream that does not exist - StreamDoesNotExistException, distinct from ACCESS_REFUSED`() {
        val e = buildEnv(rabbit.adminUsername, rabbit.adminPassword)
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamDoesNotExistException::class.java,
            { e.queryStreamStats("$ALLOWED-missing-${UUID.randomUUID()}") })
        assertTrue(ex.message!!.contains("does not exist"), "message was: ${ex.message}")
        // A not permitted user asking for a missing name inside the permitted pattern gets the same "does not exist".
        val plain = buildEnv("spike-plain", pw.getValue("spike-plain"))
        val ex2 = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamDoesNotExistException::class.java,
            { plain.queryStreamStats("${ALLOWED}missing-${UUID.randomUUID()}") })
        assertTrue(ex2.message!!.contains("does not exist"), "message was: ${ex2.message}")
    }

    // ================= Item 2: queryStreamStats writes nothing =================

    private fun tracking(stream: String): Pair<JsonObject, JsonObject> {
        val r = adminGet("/api/stream/%2F/$stream/tracking")
        assertEquals(200, r.status, "tracking $r")
        return r.obj["writers"]!!.jsonObject to r.obj["offsets"]!!.jsonObject
    }

    @Test
    fun `queryStreamStats writes nothing - messages and tracking unchanged, and the check can see a change`() {
        val s = twoStreams()
        awaitUntil("messages==3 on allowed", fetch = { messages(s.allowed) }, done = { it == 3L })
        val (w0, o0) = tracking(s.allowed)
        // The management publisher (AMQP 0.9.1) leaves writer entries in tracking.writers (3 publishes, 3 entries): the baseline
        // is therefore the observed value, not "empty". Offsets are empty (no consumer, nothing stored).
        assertEquals(3, w0.size, "tracking.writers after 3 management publishes: $w0"); assertEquals(0, o0.size)
        for ((user, password) in listOf(rabbit.adminUsername to rabbit.adminPassword) + pw.map { it.key to it.value }) {
            val e = buildEnv(user, password)
            repeat(5) { e.queryStreamStats(s.allowed) }
        }
        // The management value lags up to 5 s. Control: one more publish by admin; once the counter shows exactly 4 (3 + our 1,
        // not 3 + our 1 + a stats-call write) the counter has refreshed after all the calls, so a write would be visible now.
        val (wCalls, oCalls) = tracking(s.allowed)
        assertEquals(w0, wCalls, "tracking.writers unchanged by the calls"); assertEquals(o0, oCalls, "tracking.offsets unchanged by the calls")
        publishViaManagement(s.allowed, 1)
        val after = awaitUntil("messages==4 on allowed", fetch = { messages(s.allowed) }, done = { it >= 4L })
        assertEquals(4L, after, "messages after 20 stats calls and one control publish (3 + 1, nothing from the calls)")
        val (w1, o1) = tracking(s.allowed)
        assertEquals(0, o1.size, "tracking.offsets still empty after the control publish")
        assertEquals(4, w1.size, "the control publish added exactly one writer entry (the check sees changes): $w1")
        for ((k, v) in w0) assertEquals(v, w1[k], "writer $k keeps its value")
        assertEquals(0, adminGet("/api/queues/%2F/${s.allowed}").obj["consumers"]!!.jsonPrimitive.long, "no consumer registered")
        assertEquals("[]", adminGet("/api/stream/consumers/%2F").body, "no stream consumer anywhere")
        assertEquals("[]", adminGet("/api/stream/publishers/%2F/${s.allowed}").body, "no stream publisher on the stream")
    }

    // ================= Item 4: failed credentials =================

    @Test
    fun `failed credentials - stream client wrong password fails at build, no-tag user does not fail at all`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.AuthenticationFailureException::class.java,
            { buildEnv(rabbit.adminUsername, WRONG_PASSWORD) }, "wrong password must fail at Environment.build()")
        assertTrue(ex.message!!.contains("8 (AUTHENTICATION_FAILURE)"), "message was: ${ex.message}")
        assertFalse(describeAll(ex).contains(WRONG_PASSWORD), "the password must not appear in the exception chain")
        // The wrong user name gives the same answer as the wrong password (no user enumeration).
        val ex2 = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.AuthenticationFailureException::class.java,
            { buildEnv("nosuchuser-${UUID.randomUUID()}", WRONG_PASSWORD) })
        assertEquals(ex.message, ex2.message, "unknown user and wrong password are indistinguishable")
        // A valid no-tag user authenticates on the stream port and builds.
        buildEnv("spike-plain", pw.getValue("spike-plain"))
    }

    private fun describeAll(t: Throwable): String = describe(t)

    @Test
    fun `failed credentials - management API 401 body differs for wrong password and for no management tag, on whoami and overview`() {
        for (path in listOf("/api/whoami", "/api/overview")) {
            val noTag = mgmtSend("spike-plain", pw.getValue("spike-plain"), "GET", path)
            assertEquals(401, noTag.status, "$path no-tag")
            assertEquals("not_authorised", noTag.obj["error"]!!.jsonPrimitive.content, "$path no-tag error (British spelling)")
            assertEquals("Not management user", noTag.obj["reason"]!!.jsonPrimitive.content, "$path no-tag reason")
            for ((label, user) in listOf("wrong password" to rabbit.adminUsername, "unknown user" to "nosuchuser-x")) {
                val bad = mgmtSend(user, WRONG_PASSWORD, "GET", path)
                assertEquals(401, bad.status, "$path $label")
                assertEquals("not_authorized", bad.obj["error"]!!.jsonPrimitive.content, "$path $label error (American spelling)")
                assertEquals("Not_Authorized", bad.obj["reason"]!!.jsonPrimitive.content, "$path $label reason")
                assertFalse(bad.body.contains(WRONG_PASSWORD), "password must not be echoed")
            }
        }
        // A management-tagged and a monitoring user are accepted on /api/whoami, so 200 vs the two 401 bodies is a total split.
        for (u in listOf("spike-mgmt", "spike-mon")) assertEquals(200, mgmtSend(u, pw.getValue(u), "GET", "/api/whoami").status, u)
    }

    @Test
    fun `broker version is recorded`() {
        val o = adminGet("/api/overview").obj
        assertEquals("4.", o["rabbitmq_version"]!!.jsonPrimitive.content.take(2), "RabbitMQ 4.x expected, was ${o["rabbitmq_version"]}")
    }

    // ================= Item 3: profile CA, no host name verification =================

    /** Reachable alternative names of this machine for the published port, NOT in the certificate (SAN localhost, 127.0.0.1). */
    private fun alternativeNames(): List<String> {
        val lan = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress }
        return (lan + "127.0.0.2").filter { h ->
            runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, streamPort()), 1000) } }.isSuccess
        }
    }

    private fun causes(t: Throwable): List<Throwable> = generateSequence(t) { it.cause }.take(8).toList()

    @Test
    fun `tls - the API is SslContextBuilder endpointIdentificationAlgorithm on a netty SslContext passed to tls sslContext, CA only`() {
        val stream = unique(ALLOWED); declareStream(stream); publishViaManagement(stream, 2)
        // Control that makes the host name switch meaningful: with verification ON a name that IS in the certificate works ...
        for (h in listOf("localhost", "127.0.0.1")) {
            assertEquals(0L, buildEnv(host = h, ssl = caOnlyContext(verifyHostName = true)).queryStreamStats(stream).firstOffset(), "verify ON, SAN name $h")
            assertEquals(0L, buildEnv(host = h, ssl = caOnlyContext(verifyHostName = false)).queryStreamStats(stream).firstOffset(), "verify OFF, SAN name $h")
        }
    }

    @Test
    fun `tls - (a) CA only, name not in the certificate, verification off succeeds - (b) same with verification on fails`() {
        val names = alternativeNames()
        assumeTrue(names.isNotEmpty(), "GAP: no alternative name reaches the published port on this machine (no site-local IPv4, 127.0.0.2 not routed); item 3 (a)/(b) not proven here")
        val stream = unique(ALLOWED); declareStream(stream); publishViaManagement(stream, 2)
        val h = names.first()
        assertEquals(0L, buildEnv(host = h, ssl = caOnlyContext(verifyHostName = false)).queryStreamStats(stream).firstOffset(),
            "(a) CA only, host $h not in the certificate, verification off")
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamException::class.java,
            { buildEnv(host = h, ssl = caOnlyContext(verifyHostName = true)) }, "(b) same host with verification on must fail at build")
        val ssl = causes(ex).filterIsInstance<javax.net.ssl.SSLHandshakeException>().firstOrNull()
        assertTrue(ssl != null, "(b) a SSLHandshakeException is in the cause chain: ${describe(ex)}")
        assertTrue(ssl!!.message!!.contains("No subject alternative names matching"), "(b) message was: ${ssl.message}")
    }

    @Test
    fun `tls - (c) a wrong CA is refused even with host name verification off, so off is not trust-all`() {
        val wrong = wrongCa() // SAN localhost + 127.0.0.1, so only the chain can be the reason
        for (verify in listOf(false, true)) {
            val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamException::class.java,
                { buildEnv(ssl = caOnlyContext(ca = wrong, verifyHostName = verify)) }, "wrong CA, verification=$verify")
            val ssl = causes(ex).filterIsInstance<javax.net.ssl.SSLHandshakeException>().firstOrNull()
            assertTrue(ssl != null, "SSLHandshakeException in the cause chain: ${describe(ex)}")
            assertTrue(ssl!!.message!!.contains("PKIX path building failed"), "message was: ${ssl.message}")
        }
    }

    @Test
    fun `tls - netty 4_2 default VERIFIES the host name, so the product must set endpointIdentificationAlgorithm to null explicitly`() {
        val names = alternativeNames()
        assumeTrue(names.isNotEmpty(), "GAP: no alternative name on this machine")
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", certificateOf(caPem)) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val r = runCatching { buildEnv(host = names.first(), ssl = SslContextBuilder.forClient().trustManager(tmf).build()) }
        val ex = r.exceptionOrNull()
        assertTrue(ex is com.rabbitmq.stream.StreamException, "expected a failure at build, was: ${r.fold({ "OK" }, { describe(it) })}")
        val ssl = causes(ex!!).filterIsInstance<javax.net.ssl.SSLHandshakeException>().firstOrNull()
        assertTrue(ssl?.message?.contains("No subject alternative names matching") == true, "was: ${describe(ex)}")
        assertEquals("4.2", io.netty.util.Version.identify().values.first { it.artifactId() == "netty-handler" }.artifactVersion().take(3),
            "this default is the netty 4.2 behaviour; if netty changes, re-check")
    }

    // ================= Item 5: unreachable stream port =================

    private fun ms(since: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    @Test
    fun `unreachable - a closed port fails at Environment build with ConnectionStreamException Connection refused, about one second`() {
        val port = ServerSocket(0).use { it.localPort } // bound, then closed: nothing listens, refusal is immediate
        val t0 = System.nanoTime()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamException::class.java,
            { buildEnv(host = "127.0.0.1", port = port) }, "build against a closed port must throw (not defer to the first call)")
        val took = ms(t0)
        // The concrete class is package-private in the client (com.rabbitmq.stream.impl.ConnectionStreamException): match by name.
        assertEquals("com.rabbitmq.stream.impl.ConnectionStreamException", ex.javaClass.name)
        assertTrue(ex.message!!.startsWith("Error while creating stream connection to 127.0.0.1:$port"), "message was: ${ex.message}")
        assertTrue(causes(ex).any { it is java.net.ConnectException && it.message?.startsWith("Connection refused") == true }, "chain: ${describe(ex)}")
        assertTrue(took < 10_000, "blocked $took ms; the default RPC timeout is 10 s")
        println("M2-03a closed port: build threw after $took ms")
    }

    @Test
    fun `unreachable - a non routable address with a 1 s connect timeout set through the netty bootstrap fails with StreamException within seconds`() {
        // Optional and machine dependent (a network without a route may answer 'unreachable' at once): only a bound is asserted.
        val t0 = System.nanoTime()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(com.rabbitmq.stream.StreamException::class.java, {
            val e = Environment.builder().host("10.255.255.1").port(5551).username("x").password("y")
                .addressResolver { Address("10.255.255.1", 5551) }
                .netty().bootstrapCustomizer { it.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000) }.environmentBuilder()
                .tls().sslContext(caOnlyContext(verifyHostName = false)).environmentBuilder().rpcTimeout(Duration.ofSeconds(2)).build()
            environments += e
        })
        val took = ms(t0)
        assertTrue(took < 10_000, "blocked $took ms: ${describe(ex)}")
        assertTrue(ex.javaClass.name in setOf("com.rabbitmq.stream.impl.TimeoutStreamException", "com.rabbitmq.stream.impl.ConnectionStreamException"),
            "class was ${ex.javaClass.name}")
        println("M2-03a non-routable: ${ex.javaClass.simpleName} after $took ms")
    }

    // ================= Item 6: one Environment, many calls =================

    @Test
    fun `one Environment serves 20 sequential and 20 parallel calls and reconnects its locator after the broker closed it`() {
        val stream = unique(ALLOWED); declareStream(stream); publishViaManagement(stream, 2)
        val before = adminGet("/api/stream/connections").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val t0 = System.nanoTime()
        val e = buildEnv()
        val buildMs = ms(t0)
        val times = (1..20).map { val a = System.nanoTime(); assertEquals(0L, e.queryStreamStats(stream).firstOffset()); TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - a) }
        val pool = Executors.newFixedThreadPool(4)
        val a = System.nanoTime()
        try {
            (1..20).map { pool.submit(Callable { e.queryStreamStats(stream).firstOffset() }) }.forEach { assertEquals(0L, it.get(20, TimeUnit.SECONDS)) }
        } finally { pool.shutdown() }
        val parallelMs = ms(a)
        println("M2-03a one Environment: build $buildMs ms, 20 sequential calls (us) $times, 20 calls on 4 threads $parallelMs ms")
        assertTrue(buildMs < 5_000 && parallelMs < 5_000 && times.max() < 2_000_000, "slow: build=$buildMs parallel=$parallelMs seq(us)=$times")

        // Broker closes the locator: find our connection(s) (new since `before`), DELETE them, wait until they are gone, call again.
        fun names() = adminGet("/api/stream/connections").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val mine = awaitUntil("our locator connection is listed", fetch = { names().filter { it !in before } }, done = { it.isNotEmpty() })
        for (n in mine) assertEquals(204, admin("DELETE", "/api/connections/" + java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20")).status, "DELETE $n")
        awaitUntil("our connections are gone", fetch = { adminGet("/api/stream/connections").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content } },
            done = { names -> mine.none { it in names } })
        val r = awaitUntil("Environment answers again", 20_000, fetch = { runCatching { e.queryStreamStats(stream).firstOffset() } }, done = { it.isSuccess })
        assertEquals(0L, r.getOrThrow(), "the Environment recovered its locator by itself")
        val now = awaitUntil("a new connection is listed", fetch = { names().filter { it !in before } }, done = { it.isNotEmpty() })
        assertTrue(now.none { it in mine }, "a NEW connection serves the Environment: old=$mine new=$now")
    }

    /** A different self-signed CA, generated with the JDK's keytool into a temp dir (no dependency). */
    private fun wrongCa(): String {
        val dir = java.nio.file.Files.createTempDirectory("spike-wrong-ca")
        try {
            val keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val ks = dir.resolve("k.p12").toString()
            val pass = "changeit-throwaway"
            fun run(vararg a: String) {
                val p = ProcessBuilder(keytool, *a).redirectErrorStream(true).start()
                val o = p.inputStream.readBytes().decodeToString()
                check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed: $o" }
            }
            run("-genkeypair", "-alias", "wrong", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-keystore", ks, "-storetype", "PKCS12", "-storepass", pass)
            val pem = dir.resolve("wrong.pem").toString()
            run("-exportcert", "-rfc", "-alias", "wrong", "-keystore", ks, "-storepass", pass, "-file", pem)
            return java.nio.file.Files.readString(java.nio.file.Path.of(pem))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
