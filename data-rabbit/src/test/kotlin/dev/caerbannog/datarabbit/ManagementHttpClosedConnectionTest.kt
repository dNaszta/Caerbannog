package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Finding of 2026-11-14 (CI run 43640828120, `RealBrokerClientsTest` "the tab view model ..." timed out; reproduced locally 10 of 30 runs with the
 * broker throttled to 0.3 CPU). RabbitMQ 4's management listener (Cowboy) closes a keep-alive connection that has been idle for 5 s (measured: a
 * second request 4.8 s after the first is answered, 5.0 s after it is not). The refresh timers wait the default 5 s after the last load, so every
 * default-interval tick lands on that edge. When the JDK client sends on a pooled connection the broker is just closing, the call fails with
 * `java.io.IOException: HTTP/1.1 header parser received no bytes`. The JDK retries a failed GET exactly once, on the next pooled connection; a
 * client that holds two or more idle connections (two screens polling, a click next to a tick) meets a second dead one, [ManagementHttp] answers
 * [ManagementReply.Unreachable], and the view models turn that into D2 "Connection lost" for good (no retry, the rows go stale until the user
 * reconnects). The 10 of 30 above was not reproduced in the M2-16 verification (the same shape passed 12 of 12 on the code before the fix), so this
 * class, on real sockets, is the proof of the retry.
 *
 * No Docker: a hand-rolled keep-alive HTTP/1.1 server (over TLS like the broker's management port, and plain) drops the request that arrives on a
 * connection it has already served once, without a word, which is what the client saw. The first test is the regression test for the fix chosen
 * in M2-16 (a bounded retry of the GET in [ManagementHttp] plus a client idle timeout below the broker's); the server here drops regardless of
 * idle time, so only the retry turns it green, the idle timeout is proven elsewhere (`TlsSupportKeepAliveTest`). The other two stay true for any
 * fix: a broker that is really gone is still Unreachable (bounded attempts), and a write (POST, PUT, DELETE) is never sent twice.
 */
class ManagementHttpClosedConnectionTest {

    @TempDir
    lateinit var dir: Path

    private data class Seen(val connection: Int, val method: String, val path: String)

    /**
     * Answers `200 {"ok":true}` on any path; [dropOnReuse] reads the next request on an already used connection and closes it without any answer;
     * [dropEverything] does that for every request. [tls] serves over a throw-away self-signed certificate ([certificatePath] is its CA).
     */
    private inner class KeepAliveServer(tls: Boolean, private val dropOnReuse: Boolean, private val dropEverything: Boolean = false, private val answerDelayMillis: Long = 0) : AutoCloseable {
        val seen = CopyOnWriteArrayList<Seen>()
        private val id = java.util.UUID.randomUUID().toString().take(8)
        val certificatePath: String = File(dir.toFile(), "server-$id.pem").path
        val tls: Boolean = tls
        private val connections = AtomicInteger()
        private val server: ServerSocket = if (tls) tlsServerSocket() else ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            Thread {
                try {
                    while (true) {
                        val socket = server.accept()
                        val number = connections.incrementAndGet()
                        Thread { serve(socket, number) }.apply { isDaemon = true; start() }
                    }
                } catch (_: java.io.IOException) {
                    // closed
                }
            }.apply { isDaemon = true; start() }
        }

        private fun tlsServerSocket(): ServerSocket {
            val keystore = File(dir.toFile(), "server-$id.p12")
            keytool("-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-alias", "srv", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "changeit")
            keytool("-exportcert", "-rfc", "-alias", "srv", "-keystore", keystore.path, "-storepass", "changeit", "-file", certificatePath)
            val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            return context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress())
        }

        private fun keytool(vararg args: String) {
            val exe = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed: $out" }
        }

        private fun serve(socket: Socket, number: Int) = socket.use {
            try {
                it.soTimeout = 15_000
                val input = BufferedInputStream(it.getInputStream()).bufferedReader(Charsets.ISO_8859_1)
                var served = 0
                while (true) {
                    val requestLine = input.readLine() ?: return@use
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                    repeat(contentLength) { input.read() }
                    val parts = requestLine.split(' ')
                    seen += Seen(number, parts[0], parts[1])
                    if (dropEverything || (dropOnReuse && served >= 1)) return@use // closed with no bytes sent: the client's "no bytes" case
                    if (answerDelayMillis > 0) Thread.sleep(answerDelayMillis)
                    val body = """{"ok":true}"""
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                        flush()
                    }
                    served++
                }
            } catch (_: java.io.IOException) {
                // the client went away
            }
        }

        override fun close() = server.close()
    }

    private val ok = ManagementReply.Response(200, """{"ok":true}""")

    private fun <T> withHttp(server: KeepAliveServer, block: suspend (ManagementHttp) -> T): T {
        val transport = BrokerTransport.from(
            ConnectionProfile(
                name = "closed", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = 1, managementPort = server.port,
                tls = server.tls, caCertificatePath = if (server.tls) server.certificatePath else null,
            ),
        )
        return ManagementHttp(TlsSupport.buildHttpClient(transport, 5_000), "svc", "pw").use { http ->
            runBlocking { withTimeout(30_000) { block(http) } }
        }
    }

    private fun url(server: KeepAliveServer, path: String = "/api/whoami") = "${if (server.tls) "https" else "http"}://127.0.0.1:${server.port}$path"

    @Test
    fun `a GET is answered when the broker has closed every idle pooled connection, with one, two or three of them`() {
        // The broker closes all idle connections in the same second (5 s), and a client holds more than one when two callers overlapped (a screen's
        // refresh and a click, the first load of two screens, a browse next to a refresh). The JDK client retries a failed GET exactly once, on the
        // next pooled connection: with one stale connection that is a fresh one and it works, with two the retry lands on a dead one too.
        // Measured live (RealBrokerClientsTest, two screens polling one connection): Unreachable, then D2 "Connection lost" for good.
        val failures = mutableListOf<String>()
        for (tls in listOf(false, true)) {
            for (pooled in 1..3) {
                KeepAliveServer(tls = tls, dropOnReuse = true, answerDelayMillis = 300).use { server ->
                    withHttp(server) { http ->
                        val warm = coroutineScope { (1..pooled).map { n -> async { http.get(url(server, "/api/warm$n")) } }.awaitAll() }
                        check(warm.all { it == ok }) { "tls=$tls pooled=$pooled: warming the connections failed: $warm" }
                        val reply = http.get(url(server))
                        if (reply != ok) failures += "tls=$tls, $pooled idle connections closed: $reply (server saw ${server.seen.size} requests)"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), failures, "a GET that only met closed pooled connections must be answered on a fresh one, not reported unreachable")
    }

    @Test
    fun `a broker that really is gone is still Unreachable after at most one more attempt`() {
        for (tls in listOf(false, true)) {
            KeepAliveServer(tls = tls, dropOnReuse = false, dropEverything = true).use { server ->
                withHttp(server) { http ->
                    assertEquals(ManagementReply.Unreachable, http.get(url(server)), "tls=$tls")
                    // M2-16 (architect's ruling): ManagementHttp makes at most two attempts and the JDK client retries a failed GET once inside
                    // each, so a broker that drops every request sees at most 2 x 2 = 4 requests (it was at most 2 when there was one attempt).
                    // A third attempt or a loop puts 6 or more on the wire. The exact number of attempts is pinned without the JDK in
                    // ManagementHttpGetRetryTest (the same failure twice: exactly 2 engine calls); this is the end-to-end bound.
                    assertTrue(server.seen.size in 1..4, "tls=$tls: a GET is attempted at most twice, each time with the JDK client's own single retry (4 requests at most), never in a loop; server saw: ${server.seen}")
                }
            }
        }
    }

    @Test
    fun `a write that finds its pooled connection closed is never sent twice`() {
        // PUT, POST and DELETE change broker state (migration executor, requeue peek): the answer may have been lost after the broker acted,
        // so a second send could apply it twice. Whatever makes the GET above succeed must not touch these.
        for (tls in listOf(false, true)) {
            for (verb in listOf("POST", "PUT", "DELETE")) {
                KeepAliveServer(tls = tls, dropOnReuse = true).use { server ->
                    withHttp(server) { http ->
                        assertEquals(ok, http.get(url(server)), "tls=$tls $verb: the first call opens the connection")
                        val reply = when (verb) {
                            "POST" -> http.post(url(server, "/api/queues/%2F/q/get"), "{}")
                            "PUT" -> http.put(url(server, "/api/queues/%2F/q"), "{}")
                            else -> http.delete(url(server, "/api/queues/%2F/q"))
                        }
                        assertEquals(ManagementReply.Unreachable, reply, "tls=$tls $verb on a closed pooled connection stays Unreachable")
                        assertEquals(1, server.seen.count { it.method == verb }, "tls=$tls $verb must reach the broker once at most; server saw: ${server.seen}")
                    }
                }
            }
        }
    }
}
