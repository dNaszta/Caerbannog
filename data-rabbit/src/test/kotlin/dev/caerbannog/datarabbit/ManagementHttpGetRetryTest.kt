package dev.caerbannog.datarabbit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedChannelException
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * M2-16 (a): a GET that meets a connection the broker just closed is sent once more, on a fresh connection, inside [ManagementHttp]. No Docker:
 * a Ktor mock engine counts the calls and records method, URL and credential of each, and throws or answers as scripted.
 *
 * The rule under test: at most two attempts, only for a GET, only after a failure that is a dropped connection (an `IOException` that is none of
 * a TLS failure, a timeout, an interruption, a refused connection, an unknown host or a missing route); the outcome of the second attempt is final;
 * nothing is kept of the first failure. Writes (POST, PUT, DELETE) are sent once, whatever happens: a lost answer may hide an applied change.
 *
 * Catch order, which the rows below pin: `ConnectException` and `NoRouteToHostException` are `SocketException` subclasses, so a bare
 * `SocketException` (a reset, a broken pipe) is retried while those two are not.
 *
 * Rows that pass against the single-attempt code on purpose (every "not retried" row, writes, cancellation, sentinels) are the guards that a later
 * change which retries too much must turn red.
 */
class ManagementHttpGetRetryTest {

    private val url = "http://broker.example:15672/api/queues/%2F/orders"
    private val body = """{"ok":true}"""
    private val ok = ManagementReply.Response(200, body)
    private val basic = "Basic " + Base64.getEncoder().encodeToString("svc:pw".toByteArray())

    /** What the engine saw for one attempt. */
    private data class Call(val method: String, val url: String, val authorization: String?)

    /** What the engine does on one call. The last step repeats, so a third attempt is visible in the call count, not as a crash. */
    private sealed interface Step {
        class Fail(val error: Throwable) : Step
        class Answer(val status: Int, val body: String = "") : Step
    }

    private class Script(private vararg val steps: Step) {
        val calls = CopyOnWriteArrayList<Call>()
        private val index = AtomicInteger()

        val engine = MockEngine { request ->
            calls += Call(request.method.value, request.url.toString(), request.headers["Authorization"])
            when (val step = steps[minOf(index.getAndIncrement(), steps.size - 1)]) {
                is Step.Fail -> throw step.error
                is Step.Answer -> respond(step.body, HttpStatusCode.fromValue(step.status), headersOf("Content-Type", "application/json"))
            }
        }

        val methods: List<String> get() = calls.map { it.method }
    }

    private fun dropped() = IOException("HTTP/1.1 header parser received no bytes")
    private fun fail(error: Throwable) = Step.Fail(error)
    private fun answer(status: Int = 200, text: String = body) = Step.Answer(status, text)

    private fun <T> withHttp(script: Script, block: suspend (ManagementHttp) -> T): T =
        M508a.http(script.engine).use { http -> runBlocking { withTimeout(10_000) { block(http) } } }

    private fun get(script: Script): ManagementReply = withHttp(script) { it.get(url) }

    // ---- (i), (ii): the retry itself -------------------------------------------------------------------------------------------

    @Test
    fun `a GET that meets a dropped connection is sent once more and the answer is returned`() {
        val script = Script(fail(dropped()), answer())
        assertEquals(ok, get(script), "calls: ${script.calls}")
        assertEquals(listOf("GET", "GET"), script.methods, "two attempts, both GET")
        assertEquals(listOf(url, url), script.calls.map { it.url }, "the same URL both times")
    }

    @Test
    fun `the second attempt carries the credential again`() {
        val script = Script(fail(dropped()), answer())
        get(script)
        assertEquals(listOf(basic, basic), script.calls.map { it.authorization }, "calls: ${script.calls}")
    }

    @Test
    fun `the same failure twice is Unreachable after exactly two attempts, never a third`() {
        val script = Script(fail(dropped()))
        assertEquals(ManagementReply.Unreachable, get(script))
        assertEquals(2, script.calls.size, "a GET is tried twice at most: ${script.calls}")
    }

    @Test
    fun `a GET that is answered the first time is sent once`() {
        val script = Script(answer())
        assertEquals(ok, get(script))
        assertEquals(1, script.calls.size, "an answered GET must not be repeated: ${script.calls}")
    }

    @Test
    fun `a status other than 2xx is an answer, not a dropped connection, and is not retried`() {
        for (status in listOf(401, 403, 404, 500, 502, 503)) {
            val script = Script(answer(status, "x"))
            assertEquals(ManagementReply.Response(status, "x"), get(script), "status $status")
            assertEquals(1, script.calls.size, "status $status was retried: ${script.calls}")
        }
    }

    @Test
    fun `the answer of the second attempt is final, whatever it is`() {
        val rows = mapOf(
            "a 503" to (answer(503, "busy") to ManagementReply.Response(503, "busy")),
            "a timeout" to (fail(HttpRequestTimeoutException("http://x", 1)) to ManagementReply.Unreachable),
            "an untrusted certificate" to (fail(SSLException("handshake", CertificateException("PKIX"))) to ManagementReply.CertificateRejected),
            "a plain broker" to (fail(SSLException("Unrecognized SSL message, plaintext connection?")) to ManagementReply.NoTlsAnswer),
            "a refused connection" to (fail(ConnectException("refused")) to ManagementReply.Unreachable),
            "an unexpected RuntimeException" to (fail(IllegalStateException("odd")) to ManagementReply.Unreachable),
        )
        val problems = mutableListOf<String>()
        for ((name, row) in rows) {
            val (second, expected) = row
            val script = Script(fail(dropped()), second)
            val reply = get(script)
            if (reply != expected || script.calls.size != 2) problems += "$name on the second attempt: reply $reply (expected $expected), ${script.calls.size} calls (expected 2)"
        }
        assertEquals(emptyList<String>(), problems)
    }

    // ---- (iii): every unlisted dropped-connection shape is retried once ------------------------------------------------------

    @Test
    fun `a reset, a broken pipe, an end of stream and a closed channel are retried once like a plain IOException`() {
        val shapes = mapOf(
            "SocketException (connection reset)" to SocketException("Connection reset"),
            "SocketException (broken pipe)" to SocketException("Broken pipe"),
            "EOFException" to EOFException("EOF reached while reading"),
            "ClosedChannelException" to ClosedChannelException(),
        )
        val problems = mutableListOf<String>()
        for ((name, error) in shapes) {
            val script = Script(fail(error), answer())
            val reply = get(script)
            if (reply != ok || script.calls.size != 2) problems += "$name: reply $reply (expected $ok), ${script.calls.size} calls (expected 2)"
        }
        assertEquals(emptyList<String>(), problems)
    }

    // ---- (iv): what must not be retried, one test per type. Each ends after 1 call with the reply it gets today. -----------------

    private fun assertNotRetried(failure: Throwable, expected: ManagementReply) {
        val script = Script(fail(failure), answer())
        val reply = get(script)
        assertEquals(expected, reply, "${failure::class.qualifiedName} gives the reply it gave before the retry existed")
        assertEquals(1, script.calls.size, "${failure::class.qualifiedName} must not be retried, calls: ${script.calls}")
    }

    @Test
    fun `a JDK HttpTimeoutException is not retried`() = assertNotRetried(HttpTimeoutException("request timed out"), ManagementReply.Unreachable)

    @Test
    fun `a JDK HttpConnectTimeoutException is not retried`() = assertNotRetried(HttpConnectTimeoutException("connect timed out"), ManagementReply.Unreachable)

    @Test
    fun `a SocketTimeoutException is not retried (on the JVM it is Ktor's SocketTimeoutException too)`() =
        assertNotRetried(SocketTimeoutException("Read timed out"), ManagementReply.Unreachable)

    @Test
    fun `an InterruptedIOException is not retried`() = assertNotRetried(InterruptedIOException("interrupted"), ManagementReply.Unreachable)

    @Test
    fun `Ktor's HttpRequestTimeoutException is not retried`() = assertNotRetried(HttpRequestTimeoutException("http://x", 1), ManagementReply.Unreachable)

    @Test
    fun `Ktor's ConnectTimeoutException is not retried`() = assertNotRetried(ConnectTimeoutException("Connect timeout has expired", null), ManagementReply.Unreachable)

    @Test
    fun `a refused connection is not retried`() = assertNotRetried(ConnectException("Connection refused"), ManagementReply.Unreachable)

    @Test
    fun `no route to the host is not retried`() = assertNotRetried(NoRouteToHostException("No route to host"), ManagementReply.Unreachable)

    @Test
    fun `an unknown host is not retried`() = assertNotRetried(UnknownHostException("broker.example"), ManagementReply.Unreachable)

    @Test
    fun `a rejected certificate is not retried and stays CertificateRejected`() =
        assertNotRetried(SSLException("handshake", CertificateException("PKIX path building failed")), ManagementReply.CertificateRejected)

    @Test
    fun `a bare SSLException is not retried and stays NoTlsAnswer`() =
        assertNotRetried(SSLException("Unrecognized SSL message, plaintext connection?"), ManagementReply.NoTlsAnswer)

    @Test
    fun `a failure that is not an IOException is not retried`() {
        val problems = mutableListOf<String>()
        for (failure in listOf<Throwable>(IllegalArgumentException("bad url"), RuntimeException("odd"), URISyntaxException("a b", "illegal character"), URLParserException("http://x", RuntimeException("cause")))) {
            val script = Script(fail(failure), answer())
            val reply = get(script)
            if (reply != ManagementReply.Unreachable || script.calls.size != 1) problems += "${failure::class.simpleName}: reply $reply (expected Unreachable), ${script.calls.size} calls (expected 1)"
        }
        assertEquals(emptyList<String>(), problems)
    }

    // ---- (v): writes are sent once ---------------------------------------------------------------------------------------------

    @Test
    fun `post, put and delete with a dropped connection are Unreachable after exactly one call`() {
        val writes = mapOf<String, suspend (ManagementHttp) -> ManagementReply>(
            "POST" to { it.post(url, "{}") },
            "PUT" to { it.put(url, "{}") },
            "DELETE" to { it.delete(url) },
        )
        val problems = mutableListOf<String>()
        for ((verb, call) in writes) {
            val script = Script(fail(dropped()), answer())
            val reply = withHttp(script, call)
            if (reply != ManagementReply.Unreachable || script.methods != listOf(verb)) problems += "$verb: reply $reply (expected Unreachable), calls ${script.methods} (expected [$verb])"
        }
        assertEquals(emptyList<String>(), problems, "a write that lost its answer may have been applied, so it is never sent twice")
    }

    // ---- (vi): a holder closed between the attempts ----------------------------------------------------------------------------

    @Test
    fun `a holder closed between the attempts sends nothing more and answers Unreachable without an exception`() {
        // The engine closes the holder on the first call and then drops the connection. This can only prove the visible outcome: closing the
        // holder also closes the Ktor client, which refuses a second request before the engine lambda runs, so the `closed` guard of the second
        // attempt is a structural rule checked in review (the second attempt goes through the same guarded entry as the first).
        val calls = AtomicInteger()
        lateinit var http: ManagementHttp
        val engine = MockEngine {
            calls.incrementAndGet()
            http.close()
            throw dropped()
        }
        http = M508a.http(engine)
        val reply = runBlocking { withTimeout(10_000) { http.get(url) } }
        assertEquals(ManagementReply.Unreachable, reply)
        assertEquals(1, calls.get(), "nothing may be sent after the holder was closed")
    }

    // ---- (vii): nothing of the failure is kept ---------------------------------------------------------------------------------

    @Test
    fun `a sentinel in the failure text and the credential appear in no reply`() {
        // Not about the retry result (other tests pin that): the text of the first failure must not survive into the reply of the second attempt either.
        val sentinel = "SENTINEL-url-http://svc:pw@broker.example/api"
        val replies = listOf(
            get(Script(fail(IOException("first $sentinel")), fail(IOException("second $sentinel")))),
            get(Script(fail(IOException("first $sentinel")), answer())),
            get(Script(fail(IOException("first $sentinel")), fail(SSLException("second $sentinel")))),
        )
        for (reply in replies) {
            val text = reply.toString()
            assertFalse(text.contains("SENTINEL") || text.contains("hunter2") || text.contains("svc:pw") || text.contains(basic), "a reply keeps text of a failure or the credential: $text")
        }
    }

    // ---- (viii): cancellation ----------------------------------------------------------------------------------------------------

    @Test
    fun `a cancelled caller still gets the CancellationException, after one call`() {
        val script = Script(fail(CancellationException("the caller left")), answer())
        val caught = withHttp(script) { http ->
            try {
                http.get(url)
                null
            } catch (e: CancellationException) {
                e
            }
        }
        assertNotNull(caught, "a CancellationException was swallowed")
        assertEquals(1, script.calls.size, "a cancelled call must not be retried: ${script.calls}")
    }
}
